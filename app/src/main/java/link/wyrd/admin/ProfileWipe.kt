package link.wyrd.admin

import android.content.Context
import android.util.Log
import link.wyrd.prefs.WyrdPrefs

object ProfileWipe {

    private const val TAG = "ProfileWipe"

    fun wipe(context: Context, reason: String) {
        val alsoDevice = WyrdPrefs.get(context).wipeDeviceWithProfile
        Log.w(TAG, "$reason: removing the work profile, whole device as well = $alsoDevice")
        val dpm = DpmActions(context)
        if (alsoDevice) {
            try {
                dpm.wipeDevice()
                return
            } catch (t: Throwable) {
                Log.w(TAG, "whole-device reset refused; removing the profile only", t)
            }
        }
        dpm.wipeProfile()
    }
}
