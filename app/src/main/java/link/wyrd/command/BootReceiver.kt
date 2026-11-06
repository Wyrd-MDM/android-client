package link.wyrd.command

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import link.wyrd.logging.LogUploadWorker
import link.wyrd.prefs.WyrdPrefs
import link.wyrd.socket.SocketService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val unlockTransition = action == Intent.ACTION_BOOT_COMPLETED
        if (unlockTransition) WyrdPrefs.reload(context)

        runCatching { ProfileExpiry.service(context) }
        val prefs = WyrdPrefs.get(context)
        if (!prefs.enrolled) return
        runCatching { AutoWipe.checkAndWipe(context) }
        val svc = Intent(context, SocketService::class.java)
        if (unlockTransition) svc.action = SocketService.ACTION_RELOAD
        ContextCompat.startForegroundService(context, svc)
        if (unlockTransition) {
            link.wyrd.inventory.LocationSampleWorker.enqueue(context)
            runCatching { link.wyrd.geo.GeofenceEnforcer.checkSoon(context) }
        }
        if (prefs.securityLoggingEnabled && action == Intent.ACTION_BOOT_COMPLETED) {
            LogUploadWorker.enqueueSecurityPreReboot(context)
        }
    }
}
