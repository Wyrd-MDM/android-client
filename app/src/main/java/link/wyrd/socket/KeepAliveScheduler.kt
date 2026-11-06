package link.wyrd.socket

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

object KeepAliveScheduler {
    private const val TAG = "KeepAliveScheduler"
    private const val REQUEST_CODE = 0x4C49

    const val WIFI_INTERVAL_MS: Long = 15 * 60_000L

    const val CELLULAR_INTERVAL_MS: Long = 5 * 60_000L

    const val MAX_INTERVAL_MS: Long = WIFI_INTERVAL_MS

    fun intervalMs(context: Context): Long {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return CELLULAR_INTERVAL_MS
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            ?: return CELLULAR_INTERVAL_MS
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) WIFI_INTERVAL_MS else CELLULAR_INTERVAL_MS
    }

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + intervalMs(context)
        val pi = pendingIntent(context)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to schedule keepalive alarm", t)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, KeepAliveReceiver::class.java).apply {
            action = KeepAliveReceiver.ACTION_FIRE
            setPackage(context.packageName)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
