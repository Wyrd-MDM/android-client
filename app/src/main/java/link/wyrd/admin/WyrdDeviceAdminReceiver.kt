package link.wyrd.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.os.UserHandle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import link.wyrd.MainActivity
import link.wyrd.enroll.EnrollmentWorker
import link.wyrd.logging.LogUploadWorker
import link.wyrd.prefs.WyrdPrefs
import link.wyrd.socket.SocketService
import link.wyrd.socket.UnlockEventQueue

class WyrdDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "onEnabled — device admin active")
        val extras = readAdminExtras(intent)
        if (extras != null) {
            applyProvisioningExtras(context, extras)
        } else {
            Log.w(TAG, "no admin extras retrievable in onEnabled")
        }
        enableProfileIfOurs(context)
        kickOffEnrollment(context)
        launchMainActivity(context)
    }

    private fun launchMainActivity(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "could not launch MainActivity: $it") }
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.i(TAG, "onProfileProvisioningComplete")
        val extras = readAdminExtras(intent)
        if (extras != null) {
            applyProvisioningExtras(context, extras)
        }

        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = component(context)
        if (dpm.isProfileOwnerApp(context.packageName)) {
            runCatching { dpm.setProfileName(admin, "Wyrd") }
            runCatching { dpm.setProfileEnabled(admin) }
        }

        kickOffEnrollment(context)
        launchMainActivity(context)
    }

    override fun onSecurityLogsAvailable(context: Context, intent: Intent) {
        Log.i(TAG, "onSecurityLogsAvailable")
        LogUploadWorker.enqueueSecurity(context)
    }

    override fun onNetworkLogsAvailable(context: Context, intent: Intent, batchToken: Long, networkLogsCount: Int) {
        Log.i(TAG, "onNetworkLogsAvailable batchToken=$batchToken count=$networkLogsCount")
        LogUploadWorker.enqueueNetwork(context, batchToken)
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        Log.i(TAG, "onPasswordSucceeded")
        UnlockEventQueue.recordAndFlush(context, success = true)
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        Log.i(TAG, "onPasswordFailed")
        UnlockEventQueue.recordAndFlush(context, success = false)
    }

    private fun readAdminExtras(intent: Intent): PersistableBundle? {
        intent.getParcelableExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
            PersistableBundle::class.java,
        )?.let { return it }

        val asBundle: Bundle? = runCatching {
            intent.getBundleExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        }.getOrNull()
        if (asBundle != null) {
            Log.i(TAG, "admin extras retrieved as Bundle, converting to PersistableBundle")
            return bundleToPersistable(asBundle)
        }
        return null
    }

    private fun bundleToPersistable(bundle: Bundle): PersistableBundle {
        val pb = PersistableBundle()
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION")
            when (val v = bundle.get(key)) {
                is String -> pb.putString(key, v)
                is Int -> pb.putInt(key, v)
                is Long -> pb.putLong(key, v)
                is Double -> pb.putDouble(key, v)
                is Boolean -> pb.putBoolean(key, v)
                else -> {  }
            }
        }
        return pb
    }

    private fun applyProvisioningExtras(context: Context, extras: PersistableBundle) {
        val url = extras.getString(KEY_SERVER_URL)
        val token = extras.getString(KEY_TOKEN)
        Log.i(TAG, "provisioning extras: token present=${token != null}")
        val prefs = WyrdPrefs.get(context)
        if (!url.isNullOrBlank()) prefs.serverUrl = url
        if (!token.isNullOrBlank()) prefs.enrollToken = token
        extras.getString(KEY_SERVER_KEY)?.takeIf { it.isNotBlank() }?.let { prefs.serverPublicKey = it }
        extras.getString(KEY_SERVER_SEALING_KEY)?.takeIf { it.isNotBlank() }?.let { prefs.serverSealingKey = it }
    }

    private fun enableProfileIfOurs(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isProfileOwnerApp(context.packageName)) return
        val ok = runCatching { dpm.setProfileEnabled(component(context)) }.isSuccess
        Log.i(TAG, "enableProfileIfOurs: $ok")
    }

    private fun kickOffEnrollment(context: Context) {
        val prefs = WyrdPrefs.get(context)
        if (prefs.serverUrl.isNullOrBlank() || prefs.enrollToken.isNullOrBlank()) {
            Log.w(TAG, "skip enrollment — missing server_url/token")
            return
        }
        if (prefs.enrolled) {
            Log.i(TAG, "already enrolled — not enrolling again")
            return
        }
        val req = OneTimeWorkRequestBuilder<EnrollmentWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("wyrd-enroll", ExistingWorkPolicy.REPLACE, req)
        ContextCompat.startForegroundService(
            context, Intent(context, SocketService::class.java)
        )
    }

    companion object {
        private const val TAG = "WyrdAdmin"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_SERVER_KEY = "server_key"
        const val KEY_SERVER_SEALING_KEY = "server_sealing_key"

        fun component(context: Context): ComponentName =
            ComponentName(context, WyrdDeviceAdminReceiver::class.java)
    }
}
