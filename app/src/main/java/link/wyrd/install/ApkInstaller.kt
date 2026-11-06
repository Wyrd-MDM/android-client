package link.wyrd.install

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import link.wyrd.R
import link.wyrd.admin.DpmActions
import link.wyrd.net.WyrdApi
import link.wyrd.prefs.WyrdPrefs
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ApkInstaller(private val context: Context) {

    private val http: OkHttpClient = WyrdApi.sharedClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    sealed class Result {
        object Success : Result()
        data class Failure(val reason: String) : Result()
    }

    fun installFromUrl(
        url: String,
        packageName: String,
        commandId: String? = null,
        expectedVersionCode: Long? = null,
    ): Result {
        val dpm = DpmActions(context)
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(packageName)
                setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
                setInstallReason(PackageManager.INSTALL_REASON_POLICY)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        if (commandId != null && expectedVersionCode != null) {
            WyrdPrefs.get(context).pendingInstall = "$commandId|$packageName|$expectedVersionCode"
        }
        val window = CloseOnce(dpm.suspendInstallRestrictions())
        var waitingForPerson = false
        try {
            val outcome = install(pi, params, url, packageName, window)
            waitingForPerson = outcome.awaitingConfirmation
            return outcome.result
        } finally {
            if (!waitingForPerson) window.close()
        }
    }

    private data class Outcome(val result: Result, val awaitingConfirmation: Boolean)

    private val confirmWindowMs = TimeUnit.MINUTES.toMillis(15)

    private class CloseOnce(private val inner: AutoCloseable) : AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val isClosed: Boolean get() = closed.get()
        override fun close() {
            if (closed.compareAndSet(false, true)) runCatching { inner.close() }
        }
    }

    private fun install(
        pi: PackageInstaller,
        params: PackageInstaller.SessionParams,
        url: String,
        packageName: String,
        window: CloseOnce,
    ): Outcome {
        val sessionId = pi.createSession(params)
        Log.i(TAG, "install session $sessionId for $packageName")

        var committed = false
        try {
            val downloadResult = downloadInto(pi, sessionId, url)
            if (downloadResult is Result.Failure) return Outcome(downloadResult, false)

            val action = "$ACTION_PREFIX.$sessionId"
            val done = CountDownLatch(1)
            val result = AtomicReference<Result>()
            lateinit var receiver: BroadcastReceiver
            receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.i(TAG, "session $sessionId broadcast: status=$status msg=$msg")
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        result.compareAndSet(null, handleStatus(intent, packageName))
                        done.countDown()
                        return
                    }
                    WyrdPrefs.get(context).pendingInstall = null
                    window.close()
                    runCatching { context.unregisterReceiver(receiver) }
                    result.compareAndSet(null, handleStatus(intent, packageName))
                    done.countDown()
                }
            }
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val intent = Intent(action).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            pi.openSession(sessionId).use { it.commit(pending.intentSender) }
            committed = true
            Log.i(TAG, "session $sessionId committed, awaiting result")
            val answered = done.await(INSTALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!answered) {
                Log.w(TAG, "session $sessionId timed out waiting for install broadcast")
                runCatching { context.unregisterReceiver(receiver) }
                return Outcome(Result.Failure("install_timeout"), false)
            }
            val outcome = result.get() ?: Result.Failure("install_no_status")
            val awaitingPerson = outcome is Result.Failure &&
                outcome.reason == REASON_USER_ACTION
            if (!awaitingPerson) {
                runCatching { context.unregisterReceiver(receiver) }
                return Outcome(outcome, false)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!window.isClosed) {
                    Log.i(TAG, "session $sessionId: nobody answered, restoring policy")
                }
                window.close()
                runCatching { context.unregisterReceiver(receiver) }
            }, confirmWindowMs)
            return Outcome(outcome, true)
        } finally {
            if (!committed) runCatching { pi.abandonSession(sessionId) }
        }
    }

    private fun downloadInto(pi: PackageInstaller, sessionId: Int, url: String): Result {
        return try {
            pi.openSession(sessionId).use { session ->
                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return Result.Failure("download_failed_${resp.code}")
                    val length = resp.body?.contentLength() ?: -1L
                    val input = resp.body?.byteStream() ?: return Result.Failure("empty_apk_body")
                    session.openWrite("base.apk", 0, length).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                Result.Success
            }
        } catch (t: Throwable) {
            Log.w(TAG, "download into session $sessionId failed", t)
            Result.Failure("download_error: ${t.message ?: t::class.java.simpleName}")
        }
    }

    private fun handleStatus(intent: Intent, packageName: String): Result {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        return when (status) {
            PackageInstaller.STATUS_SUCCESS -> Result.Success
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    postConfirmNotification(packageName, confirm)
                    Result.Failure(REASON_USER_ACTION)
                } else {
                    Result.Failure("user_action_required_no_intent")
                }
            }
            else -> Result.Failure(
                "install_failed_$status" + (msg?.let { ": $it" } ?: "")
            )
        }
    }

    private fun postConfirmNotification(packageName: String, confirm: Intent) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_installs),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val text = context.getString(R.string.install_tap_to_confirm, packageName)
        val pending = PendingIntent.getActivity(
            context, packageName.hashCode(), confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        nm.notify(NOTIFICATION_TAG, packageName.hashCode(), n)
        Log.i(TAG, "posted install-confirm notification for $packageName")
    }

    companion object {
        private const val TAG = "ApkInstaller"
        private const val ACTION_PREFIX = "link.wyrd.INSTALL_RESULT"
        private const val CHANNEL_ID = "wyrd_install"
        private const val NOTIFICATION_TAG = "wyrd_install"
        private const val REASON_USER_ACTION = "user_action_required"
        private val INSTALL_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(2)
    }
}
