package link.wyrd.admin

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import link.wyrd.prefs.WyrdPrefs

object BatteryExemption {

    fun granted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun askOnce(activity: Activity) {
        val owner = activity.getSystemService(DevicePolicyManager::class.java)
            ?.isProfileOwnerApp(activity.packageName) == true
        if (!owner || granted(activity)) return
        val prefs = WyrdPrefs.get(activity)
        if (prefs.askedBatteryExemption) return
        prefs.askedBatteryExemption = true
        request(activity)
    }

    @SuppressLint("BatteryLife")
    fun request(activity: Activity) {
        val ask = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", activity.packageName, null),
        )
        if (ask.resolveActivity(activity.packageManager) != null) {
            runCatching { activity.startActivity(ask) }
            return
        }
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
            )
        }
    }
}
