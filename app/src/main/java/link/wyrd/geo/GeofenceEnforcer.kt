package link.wyrd.geo

import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import link.wyrd.admin.DpmActions
import link.wyrd.admin.ProfileWipe
import link.wyrd.inventory.LocationCache
import link.wyrd.prefs.WyrdPrefs
import link.wyrd.socket.KeepAliveScheduler
import link.wyrd.socket.SocketService
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

object GeofenceEnforcer {

    private const val TAG = "GeofenceEnforcer"

    private const val SECOND_MS = 1_000L
    private const val MINUTE_MS = 60 * SECOND_MS

    private const val MAX_FIX_AGE_MS = 20 * MINUTE_MS

    private const val LONGEST_QUIET_MS = 2 * KeepAliveScheduler.MAX_INTERVAL_MS

    private const val CONDITION_CONFIRM_MS = MINUTE_MS

    fun check(context: Context, afterLiveFix: Boolean = false) {
        val geofence = Geofence.current(context) ?: return
        val prefs = WyrdPrefs.get(context)
        if (!prefs.shareLocation) return
        val now = System.currentTimeMillis()
        if (prefs.geofenceSince == 0L) prefs.geofenceSince = now
        val timerMs = prefs.geofenceUnknownMinutes * MINUTE_MS
        val quietMs = quietFor(context, prefs)
        if (pausedTooLong(quietMs, timerMs)) {
            val action = strictest(geofence)
            Log.w(TAG, "work apps paused past the timer (${quietMs?.div(MINUTE_MS)} min), action=$action")
            enforce(context, action) {
                SocketService.reportGeofenceWipe(CAUSE_PAUSED, null, quietMs?.div(MINUTE_MS), null)
            }
            return
        }
        val fix = newestFix(context)
        if (fix != null && now - fix.time <= freshFor(timerMs)) {
            val b = breach(geofence, fix.lat, fix.lng) ?: return
            Log.w(TAG, "geofence breached, action=${b.action}")
            enforce(context, b.action) {
                SocketService.reportGeofenceWipe(if (b.zone != null) CAUSE_ENTERED else CAUSE_LEFT, b.zone, null, null)
            }
            return
        }
        if (!afterLiveFix) {
            GeofenceFixWorker.enqueue(context, freshFor(timerMs))
            return
        }
        if (!unknownTooLong(now, fix?.time, maxOf(prefs.geofenceSince, prefs.geofenceTimerSince), timerMs)) {
            Log.i(TAG, "no fresh fix to be had; not enforcing")
            return
        }
        val action = strictest(geofence)
        Log.w(TAG, "position unknown past the timer, action=$action")
        enforce(context, action) {
            SocketService.reportGeofenceWipe(
                CAUSE_UNKNOWN, null, prefs.geofenceUnknownMinutes, fix?.time?.let { it / SECOND_MS },
            )
        }
    }

    fun checkSoon(context: Context) {
        val timerMs = WyrdPrefs.get(context).geofenceUnknownMinutes * MINUTE_MS
        GeofenceFixWorker.enqueue(context, freshFor(timerMs))
    }

    fun conditionMissing(context: Context, missing: List<Requirement>) {
        if (!Geofence.isActive(context)) return
        val prefs = WyrdPrefs.get(context)
        val now = System.currentTimeMillis()
        val first = prefs.geofenceConditionMissingAt
        if (first == 0L) {
            prefs.geofenceConditionMissingAt = now
            LocationShare.warnBeforeWipe(context, missing)
            return
        }
        if (!conditionConfirmed(first, now)) return
        Log.w(TAG, "condition still missing under the geofence, erasing: $missing")
        enforce(context, ACTION_WIPE) {
            SocketService.reportGeofenceWipe(CAUSE_CONDITION, null, null, null, missing.map { it.name })
        }
    }

    fun conditionConfirmed(firstSeen: Long, now: Long): Boolean =
        firstSeen != 0L && now - firstSeen >= CONDITION_CONFIRM_MS

    fun pausedTooLong(quietMs: Long?, timerMs: Long): Boolean =
        timerMs > 0 && quietMs != null && quietMs > maxOf(timerMs, LONGEST_QUIET_MS)

    private fun quietFor(context: Context, prefs: WyrdPrefs): Long? {
        val elapsed = SystemClock.elapsedRealtime()
        val boot = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()
        val last = prefs.geofenceSeenElapsed
        val sameBoot = boot != null && boot == prefs.geofenceSeenBoot
        prefs.geofenceSeenElapsed = elapsed
        prefs.geofenceSeenBoot = boot ?: NO_BOOT_COUNT
        return if (last > 0 && sameBoot && elapsed >= last) elapsed - last else null
    }

    fun freshFor(timerMs: Long): Long = if (timerMs > 0) minOf(MAX_FIX_AGE_MS, timerMs) else MAX_FIX_AGE_MS

    fun unknownTooLong(now: Long, newestFix: Long?, since: Long, timerMs: Long): Boolean =
        timerMs > 0 && now - maxOf(newestFix ?: 0L, since) > timerMs

    fun strictest(geofence: Geofence): String =
        geofence.zones.fold(null as String?) { acc, z -> stricter(acc, z.action) } ?: ACTION_LOCK

    data class Breach(val action: String, val zone: String? = null)

    fun breach(geofence: Geofence, lat: Double, lng: Double): Breach? {
        var found: Breach? = null
        val keepIn = geofence.zones.filter { it.kind == KEEP_IN }
        if (keepIn.isNotEmpty() && keepIn.none { contains(it.polygon, lat, lng) }) {
            found = Breach(keepIn.map { it.action }.maxByOrNull { rank(it) } ?: ACTION_LOCK)
        }
        for (z in geofence.zones) {
            if (z.kind == KEEP_OUT && contains(z.polygon, lat, lng) &&
                (found == null || rank(z.action) > rank(found.action))
            ) {
                found = Breach(z.action, z.name)
            }
        }
        return found
    }

    private fun enforce(context: Context, action: String, report: () -> Unit) {
        if (action == ACTION_WIPE) {
            runCatching(report).onFailure { Log.w(TAG, "could not tell the server; wiping anyway", it) }
            runCatching { Thread.sleep(SocketService.LAST_WORD_GRACE_MS) }
            runCatching { ProfileWipe.wipe(context, "geofence breach") }
                .onFailure { Log.w(TAG, "wipe on breach failed", it) }
        } else {
            runCatching { DpmActions(context).lockNow() }.onFailure { Log.w(TAG, "lock on breach failed", it) }
        }
    }

    private class Fix(val lat: Double, val lng: Double, val time: Long)

    private fun newestFix(context: Context): Fix? {
        val lm = context.getSystemService(LocationManager::class.java)
        var best: Fix? = null
        if (lm != null && hasLocationPermission(context)) {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)) {
                val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
                if (best == null || l.time > best.time) best = Fix(l.latitude, l.longitude, l.time)
            }
        }
        val cached = LocationCache(context).read() ?: return best
        val lat = cached["latitude"]?.jsonPrimitive?.doubleOrNull
        val lng = cached["longitude"]?.jsonPrimitive?.doubleOrNull
        val ts = cached["timestamp"]?.jsonPrimitive?.doubleOrNull?.times(1000)?.toLong()
        if (lat != null && lng != null && ts != null && (best == null || ts > best.time)) {
            best = Fix(lat, lng, ts)
        }
        return best
    }

    private fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun contains(polygon: List<DoubleArray>, lat: Double, lng: Double): Boolean {
        val n = polygon.size
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val (latI, lngI) = polygon[i]
            val (latJ, lngJ) = polygon[j]
            if ((latI > lat) != (latJ > lat) &&
                lng < (lngJ - lngI) * (lat - latI) / (latJ - latI) + lngI
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun rank(action: String): Int = if (action == ACTION_WIPE) 1 else 0
    private fun stricter(a: String?, b: String): String =
        if (a == null || rank(b) > rank(a)) b else a

    private const val KEEP_IN = "keep_in"
    private const val KEEP_OUT = "keep_out"
    private const val ACTION_LOCK = "lock"
    private const val ACTION_WIPE = "wipe"
    private const val CAUSE_LEFT = "left"
    private const val CAUSE_ENTERED = "entered"
    private const val CAUSE_UNKNOWN = "unknown"
    private const val CAUSE_PAUSED = "paused"
    private const val CAUSE_CONDITION = "condition"
    private const val NO_BOOT_COUNT = -1
}

private operator fun DoubleArray.component1() = this[0]
private operator fun DoubleArray.component2() = this[1]
