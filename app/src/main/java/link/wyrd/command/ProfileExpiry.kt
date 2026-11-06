package link.wyrd.command

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import link.wyrd.admin.ClockLock
import link.wyrd.admin.ProfileWipe
import link.wyrd.prefs.WyrdPrefs

object ProfileExpiry {

    private const val TAG = "ProfileExpiry"
    private const val MILLIS_PER_SECOND = 1_000L
    private const val NO_DEADLINE = 0L
    private const val NO_NUMBER = 0L

    private const val LATEST_AT = Long.MAX_VALUE / MILLIS_PER_SECOND

    private const val REQUEST_CODE = 0x5045
    const val ACTION_EXPIRE = "link.wyrd.action.PROFILE_EXPIRE"

    internal interface Handset {
        var expiresAt: Long
        var expirySeq: Long
        fun armAlarm(at: Long)
        fun removeProfile()
    }

    internal fun receive(handset: Handset, at: Long?, seq: Long, now: Long): Boolean {
        if (at != null && (at <= NO_DEADLINE || at > LATEST_AT)) return false
        if (seq <= handset.expirySeq) return true
        handset.expirySeq = seq
        handset.expiresAt = at ?: NO_DEADLINE
        service(handset, now)
        return true
    }

    internal fun receiveAtEnrollment(handset: Handset, at: Long?, now: Long): Boolean {
        if (at == null || at <= NO_DEADLINE || at > LATEST_AT) return false
        if (handset.expirySeq > NO_NUMBER) return true
        handset.expiresAt = at
        service(handset, now)
        return true
    }

    internal fun service(handset: Handset, now: Long) {
        val at = handset.expiresAt
        when {
            at <= NO_DEADLINE -> return
            now >= at -> handset.removeProfile()
            else -> handset.armAlarm(at)
        }
    }

    fun receive(context: Context, at: Long?, seq: Long): Boolean {
        val taken = receive(AndroidHandset(context), at, seq, nowSeconds())
        if (taken) ClockLock.reconcile(context)
        return taken
    }

    fun receiveAtEnrollment(context: Context, at: Long?): Boolean {
        val taken = receiveAtEnrollment(AndroidHandset(context), at, nowSeconds())
        if (taken) ClockLock.reconcile(context)
        return taken
    }

    fun service(context: Context) = service(AndroidHandset(context), nowSeconds())

    fun deadlineAt(context: Context): Long? =
        WyrdPrefs.get(context).profileExpiresAt.takeIf { it > NO_DEADLINE }

    private fun nowSeconds(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND

    private class AndroidHandset(private val context: Context) : Handset {
        override var expiresAt: Long
            get() = WyrdPrefs.get(context).profileExpiresAt
            set(value) {
                WyrdPrefs.get(context).profileExpiresAt = value
            }

        override var expirySeq: Long
            get() = WyrdPrefs.get(context).profileExpirySeq
            set(value) {
                WyrdPrefs.get(context).profileExpirySeq = value
            }

        override fun armAlarm(at: Long) {
            val intent = Intent(context, ProfileExpiryReceiver::class.java).apply {
                action = ACTION_EXPIRE
                setPackage(context.packageName)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
            runCatching {
                context.getSystemService(AlarmManager::class.java)
                    ?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at * MILLIS_PER_SECOND, pi)
            }.onFailure { Log.w(TAG, "could not set the alarm; the keepalive tick still checks", it) }
        }

        override fun removeProfile() = ProfileWipe.wipe(context, "profile end date reached")
    }
}
