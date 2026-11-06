package link.wyrd.admin

import android.content.Context
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import link.wyrd.command.ProfileExpiry
import link.wyrd.geo.Geofence
import link.wyrd.prefs.WyrdPrefs

object ClockLock {

    private const val TAG = "ClockLock"

    fun reconcile(context: Context) {
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        val wanted = Geofence.isActive(context) || WyrdPrefs.get(context).autoWipeDays > 0 ||
            ProfileExpiry.deadlineAt(context) != null
        val dpm = DpmActions(context)
        runCatching {
            if (wanted && !autoTimeOn(context)) dpm.setAutoTimeEnabled(true)
            if (dpm.hasUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME, onParent = true) != wanted) {
                dpm.setUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME, wanted, onParent = true)
            }
        }.onFailure { Log.w(TAG, "could not hold the clock, wanted=$wanted", it) }
    }

    private fun autoTimeOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 0) != 0
}
