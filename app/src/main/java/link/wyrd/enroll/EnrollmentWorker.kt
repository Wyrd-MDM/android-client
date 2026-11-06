package link.wyrd.enroll

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import link.wyrd.admin.DpmActions
import link.wyrd.command.ProfileExpiry
import link.wyrd.net.EnrollResponse
import link.wyrd.net.WyrdApi
import link.wyrd.prefs.WyrdPrefs

class EnrollmentWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = WyrdPrefs.get(applicationContext)
        val url = prefs.serverUrl
        val token = prefs.enrollToken
        if (url.isNullOrBlank() || token.isNullOrBlank()) {
            Log.w(TAG, "missing url/token — failing")
            return Result.failure()
        }
        return try {
            val req = AttestationManager.buildEnrollmentPayload(applicationContext)
            val api = WyrdApi(applicationContext)
            val resp = api.enroll(req)
            takeProfileExpiry(resp)
            if (!resp.ok) {
                Log.w(TAG, "server rejected enrollment: ${resp.message}")
                return Result.retry()
            }
            if (resp.serverPublicKey != prefs.serverPublicKey) {
                Log.w(TAG, "enrollment answer names another server key")
                return Result.failure()
            }
            resp.rotatedToken?.takeIf { it.isNotBlank() }?.let { prefs.enrollToken = it }
            prefs.enrolled = true
            prefs.syncToDe()
            link.wyrd.inventory.LocationSampleWorker.enqueue(applicationContext)
            Log.i(TAG, "enrolled")
            runCatching { DpmActions(applicationContext).lockSelfRuntimePermissions() }
                .onFailure { Log.w(TAG, "lockSelfRuntimePermissions failed", it) }
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "enrollment failed", t)
            (t as? WyrdApi.EnrollRefused)?.answer?.let { takeProfileExpiry(it) }
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    private fun takeProfileExpiry(resp: EnrollResponse) {
        val at = resp.profileExpiresAt ?: return
        runCatching { ProfileExpiry.receiveAtEnrollment(applicationContext, at) }
            .onSuccess { taken -> if (!taken) Log.w(TAG, "ignored a profile end date of $at") }
            .onFailure { Log.w(TAG, "could not take the profile end date", it) }
    }

    companion object {
        private const val TAG = "EnrollmentWorker"
        private const val MAX_ATTEMPTS = 10
    }
}
