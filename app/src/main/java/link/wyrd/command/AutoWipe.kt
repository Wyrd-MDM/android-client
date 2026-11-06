package link.wyrd.command

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import link.wyrd.admin.ProfileWipe
import link.wyrd.prefs.WyrdPrefs
import link.wyrd.socket.SocketService

object AutoWipe {

    private const val TAG = "AutoWipe"
    private const val SECONDS_PER_DAY = 24 * 60 * 60L

    fun deadlineAt(context: Context): Long? {
        val prefs = WyrdPrefs.get(context)
        return deadline(prefs.lastUnlockAt, prefs.autoWipeArmedAt, prefs.autoWipeDays)
    }

    fun deadline(lastUnlockAt: Long, armedAt: Long, days: Int): Long? {
        if (days <= 0) return null
        val since = maxOf(lastUnlockAt, armedAt)
        if (since <= 0L) return null
        return since + days * SECONDS_PER_DAY
    }

    fun noteUnlock(context: Context) {
        val at = System.currentTimeMillis() / 1000
        WyrdPrefs.get(context).lastUnlockAt = at
        Log.i(TAG, "unlock noted at=$at")
    }

    fun tick(context: Context) {
        if (openNow(context)) noteUnlock(context)
        checkAndWipe(context)
    }

    private fun openNow(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
        return inUse(power.isInteractive, keyguard.isKeyguardLocked)
    }

    fun inUse(interactive: Boolean, keyguardLocked: Boolean): Boolean =
        interactive && !keyguardLocked

    fun checkAndWipe(context: Context) {
        val prefs = WyrdPrefs.get(context)
        val days = prefs.autoWipeDays
        if (days <= 0) return
        val deadline = deadlineAt(context) ?: return
        val now = System.currentTimeMillis() / 1000
        if (now < deadline) return

        Log.w(TAG, "no unlock for $days days — removing the work profile")
        runCatching { SocketService.reportAutoWipe(days, prefs.lastUnlockAt) }
            .onFailure { Log.w(TAG, "could not tell the server; wiping anyway", it) }
        runCatching { Thread.sleep(SocketService.LAST_WORD_GRACE_MS) }

        ProfileWipe.wipe(context, "no unlock for $days days")
    }
}
