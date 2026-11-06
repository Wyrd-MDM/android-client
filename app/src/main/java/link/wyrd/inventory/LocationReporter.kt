package link.wyrd.inventory

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LocationReporter(private val context: Context) {

    private companion object {
        const val TAG = "LocationReporter"
        const val PER_PROVIDER_TIMEOUT_SEC = 15L

        const val SAMPLE_PER_PROVIDER_TIMEOUT_SEC = 8L

        const val ON_DEMAND_MAX_AGE_MS = 5 * 60 * 1000L

        const val SAMPLE_MAX_AGE_MS = 15 * 60 * 1000L
    }

    fun getLocation(): JsonObject {
        val best = acquireFix(
            listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
            ),
            maxAgeMs = ON_DEMAND_MAX_AGE_MS,
            perProviderTimeoutSec = PER_PROVIDER_TIMEOUT_SEC,
        )
        if (best == null) {
            val cached = LocationCache(context).read()
            if (cached != null) {
                Log.i(TAG, "no live fix; returning cached location")
                return cached
            }
            return buildJsonObject {
                put("error", noFixReason())
            }
        }

        val json = locationToJson(best)
        LocationCache(context).save(json)
        return json
    }

    private fun noFixReason(): String {
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null || !lm.isLocationEnabled) return "location_switched_off"
        val gps = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val network = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        Log.i(TAG, "no fix: gps=$gps network=$network")
        if (!gps && !network) return "location_no_providers"
        if (!network) return "location_no_fix_satellite_only"
        return "location_no_fix"
    }

    fun sampleAndCache(): Boolean {
        val best = acquireFix(
            listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
            ),
            maxAgeMs = SAMPLE_MAX_AGE_MS,
            perProviderTimeoutSec = SAMPLE_PER_PROVIDER_TIMEOUT_SEC,
        )
        if (best == null) {
            Log.i(TAG, "low-power sample: no fix")
            return false
        }
        LocationCache(context).save(locationToJson(best))
        Log.i(TAG, "low-power sample cached fix from ${best.provider}")
        return true
    }

    fun sampleForGeofence(maxAgeMs: Long): Boolean {
        val best = acquireFix(
            listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
            ),
            maxAgeMs = maxAgeMs,
            perProviderTimeoutSec = SAMPLE_PER_PROVIDER_TIMEOUT_SEC,
        )
        if (best == null || System.currentTimeMillis() - best.time > maxAgeMs) {
            Log.i(TAG, "geofence sample: no fix within ${maxAgeMs / 1000}s")
            return false
        }
        LocationCache(context).save(locationToJson(best))
        return true
    }

    @SuppressLint("MissingPermission")
    private fun acquireFix(
        providers: List<String>,
        maxAgeMs: Long,
        perProviderTimeoutSec: Long,
    ): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null

        Log.i(TAG, "providers enabled: " + providers.joinToString {
            "$it=${runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)}"
        })

        var best: Location? = null
        for (provider in providers) {
            try {
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null && (best == null || loc.time > best.time)) best = loc
            } catch (_: Exception) {
            }
        }
        if (best == null || System.currentTimeMillis() - best.time > maxAgeMs) {
            val fresh = requestFreshLocation(lm, providers, perProviderTimeoutSec)
            if (fresh != null) best = fresh
        }
        return best
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(
        lm: LocationManager,
        providers: List<String>,
        timeoutSec: Long,
    ): Location? {
        for (provider in providers) {
            val enabled = runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
            if (!enabled) {
                Log.i(TAG, "skip $provider: not enabled")
                continue
            }

            Log.i(TAG, "requesting fresh fix from $provider (timeout ${timeoutSec}s)")
            val latch = CountDownLatch(1)
            val result = AtomicReference<Location?>(null)
            val listener = LocationListener { loc ->
                result.set(loc)
                latch.countDown()
            }

            try {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            } catch (t: Exception) {
                Log.w(TAG, "requestLocationUpdates $provider threw", t)
                continue
            }

            try {
                val signaled = latch.await(timeoutSec, TimeUnit.SECONDS)
                if (!signaled) Log.i(TAG, "no fix from $provider within ${timeoutSec}s")
            } finally {
                runCatching { lm.removeUpdates(listener) }
            }

            val fix = result.get()
            if (fix != null) {
                Log.i(TAG, "got fix from $provider")
                return fix
            }
        }
        return null
    }

    private fun locationToJson(loc: Location): JsonObject = buildJsonObject {
        put("latitude", loc.latitude)
        put("longitude", loc.longitude)
        if (loc.hasAccuracy()) put("accuracy", loc.accuracy.toDouble())
        if (loc.hasAltitude()) put("altitude", loc.altitude)
        put("provider", loc.provider ?: "unknown")
        put("timestamp", loc.time / 1000)
    }
}
