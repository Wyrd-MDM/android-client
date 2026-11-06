package link.wyrd.socket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import link.wyrd.prefs.WyrdPrefs

class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return

        if (!WyrdPrefs.get(context).enrolled) {
            Log.d(TAG, "keepalive: not enrolled — not re-arming")
            return
        }
        KeepAliveScheduler.schedule(context)
        runCatching { link.wyrd.command.AutoWipe.tick(context) }
            .onFailure { Log.w(TAG, "unlock-timer tick failed", it) }
        runCatching { link.wyrd.command.ProfileExpiry.service(context) }
            .onFailure { Log.w(TAG, "profile end date check failed", it) }
        runCatching { link.wyrd.geo.LocationShare.reconcile(context) }
            .onFailure { Log.w(TAG, "location share check failed", it) }
        runCatching { link.wyrd.admin.ClockLock.reconcile(context) }
            .onFailure { Log.w(TAG, "clock lock check failed", it) }
        runCatching { link.wyrd.geo.GeofenceEnforcer.check(context) }
            .onFailure { Log.w(TAG, "geofence check failed", it) }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wyrd:keepalive")
        wl.setReferenceCounted(false)
        wl.acquire(WAKE_LOCK_TIMEOUT_MS)

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SocketService::class.java).apply {
                    action = SocketService.ACTION_KEEPALIVE
                }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "failed to forward keepalive", t)
        }
    }

    companion object {
        const val ACTION_FIRE = "link.wyrd.action.KEEPALIVE_FIRE"
        private const val TAG = "KeepAliveReceiver"
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
    }
}
