package link.wyrd.geo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.PowerManager
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import link.wyrd.R
import link.wyrd.admin.DpmActions
import link.wyrd.inventory.LocationCache
import link.wyrd.inventory.LocationSampleWorker
import link.wyrd.prefs.WyrdPrefs
import link.wyrd.socket.SocketService

enum class Requirement {
    LOCATION_ON,
    WORK_PROFILE_LOCATION,
    NETWORK_LOCATION,
    PRECISE_PERMISSION,
    BACKGROUND_PERMISSION,
    UNRESTRICTED_BACKGROUND,
}

data class LocationState(
    val locationOn: Boolean,
    val workProfileLocation: Boolean,
    val networkLocation: Boolean,
    val precise: Boolean,
    val background: Boolean,
    val unrestricted: Boolean,
) {
    fun unmet(): List<Requirement> = buildList {
        if (!locationOn) add(Requirement.LOCATION_ON)
        if (!workProfileLocation) add(Requirement.WORK_PROFILE_LOCATION)
        if (!networkLocation) add(Requirement.NETWORK_LOCATION)
        if (!precise) add(Requirement.PRECISE_PERMISSION)
        if (!background) add(Requirement.BACKGROUND_PERMISSION)
        if (!unrestricted) add(Requirement.UNRESTRICTED_BACKGROUND)
    }
}

object LocationShare {

    private const val TAG = "LocationShare"
    private const val ALERT_CHANNEL_ID = "wyrd_alerts"
    private const val SWITCHED_OFF_NOTIFICATION_ID = 2
    private const val GEOFENCE_CONDITION_NOTIFICATION_ID = 3

    sealed interface Outcome {
        data object On : Outcome
        data class Unmet(val missing: List<Requirement>) : Outcome
        data object LockRefused : Outcome
    }

    fun read(context: Context): LocationState {
        val lm = context.getSystemService(LocationManager::class.java)
        val um = context.getSystemService(UserManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        return LocationState(
            locationOn = lm?.isLocationEnabled == true,
            workProfileLocation = um?.hasUserRestriction(UserManager.DISALLOW_SHARE_LOCATION) != true,
            networkLocation = runCatching { lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true }
                .getOrDefault(false),
            precise = granted(context, Manifest.permission.ACCESS_FINE_LOCATION),
            background = granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            unrestricted = pm?.isIgnoringBatteryOptimizations(context.packageName) == true,
        )
    }

    fun enable(context: Context): Outcome {
        val missing = read(context).unmet()
        if (missing.isNotEmpty()) return Outcome.Unmet(missing)
        try {
            lock(context, true)
        } catch (t: Throwable) {
            Log.w(TAG, "location settings lock refused", t)
            runCatching { lock(context, false) }
            return Outcome.LockRefused
        }
        setOn(context, true)
        return Outcome.On
    }

    fun disable(context: Context) {
        runCatching { lock(context, false) }.onFailure { Log.w(TAG, "could not unlock location settings", it) }
        setOn(context, false)
    }

    fun reconcile(context: Context): List<Requirement>? {
        if (!WyrdPrefs.get(context).shareLocation) return null
        val missing = read(context).unmet()
        if (missing.isEmpty()) {
            runCatching { lock(context, true) }.onFailure { Log.w(TAG, "could not renew the lock", it) }
            val prefs = WyrdPrefs.get(context)
            if (prefs.geofenceConditionMissingAt != 0L) prefs.geofenceConditionMissingAt = 0L
            return null
        }
        if (Geofence.isActive(context)) {
            Log.w(TAG, "condition missing under an active geofence; keeping sharing on: $missing")
            runCatching { lock(context, true) }
            GeofenceEnforcer.conditionMissing(context, missing)
            return missing
        }
        Log.w(TAG, "sharing switched off, missing $missing")
        disable(context)
        notifySwitchedOff(context, missing)
        return missing
    }

    private fun setOn(context: Context, on: Boolean) {
        WyrdPrefs.get(context).shareLocation = on
        if (!on) LocationCache(context).clear()
        LocationSampleWorker.enqueue(context)
        SocketService.reportLocationShare(on)
    }

    private fun lock(context: Context, on: Boolean) {
        val dpm = DpmActions(context)
        for (onParent in listOf(false, true)) {
            if (dpm.hasUserRestriction(UserManager.DISALLOW_CONFIG_LOCATION, onParent) == on) continue
            dpm.setUserRestriction(UserManager.DISALLOW_CONFIG_LOCATION, on, onParent)
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun notifySwitchedOff(context: Context, missing: List<Requirement>) {
        val text = missing.joinToString("\n") { context.getString(label(it)) }
        notify(context, SWITCHED_OFF_NOTIFICATION_ID, context.getString(R.string.share_switched_off), text)
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.alerts_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val n = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
        runCatching { nm.notify(id, n) }
    }

    fun warnBeforeWipe(context: Context, missing: List<Requirement>) {
        val text = context.getString(
            R.string.geofence_condition_text,
            missing.joinToString("\n") { context.getString(label(it)) },
        )
        notify(context, GEOFENCE_CONDITION_NOTIFICATION_ID, context.getString(R.string.geofence_condition_title), text)
    }

    fun label(r: Requirement): Int = when (r) {
        Requirement.LOCATION_ON -> R.string.req_location_on
        Requirement.WORK_PROFILE_LOCATION -> R.string.req_work_profile_location
        Requirement.NETWORK_LOCATION -> R.string.req_network_location
        Requirement.PRECISE_PERMISSION -> R.string.req_precise_permission
        Requirement.BACKGROUND_PERMISSION -> R.string.req_background_permission
        Requirement.UNRESTRICTED_BACKGROUND -> R.string.req_unrestricted_background
    }
}
