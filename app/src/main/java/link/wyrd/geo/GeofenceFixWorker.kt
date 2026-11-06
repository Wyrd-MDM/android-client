package link.wyrd.geo

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import link.wyrd.inventory.LocationReporter
import link.wyrd.prefs.WyrdPrefs

class GeofenceFixWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val maxAgeMs = inputData.getLong(KEY_MAX_AGE_MS, 0L)
        if (WyrdPrefs.get(applicationContext).shareLocation) {
            runCatching { LocationReporter(applicationContext).sampleForGeofence(maxAgeMs) }
                .onFailure { Log.w(TAG, "live fix for the geofence failed", it) }
        }
        runCatching { GeofenceEnforcer.check(applicationContext, afterLiveFix = true) }
            .onFailure { Log.w(TAG, "geofence check after a live fix failed", it) }
        return Result.success()
    }

    companion object {
        private const val TAG = "GeofenceFixWorker"
        private const val WORK_NAME = "wyrd-geofence-fix"
        private const val KEY_MAX_AGE_MS = "max_age_ms"

        fun enqueue(context: Context, maxAgeMs: Long) {
            val req = OneTimeWorkRequestBuilder<GeofenceFixWorker>()
                .setInputData(Data.Builder().putLong(KEY_MAX_AGE_MS, maxAgeMs).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, req)
        }
    }
}
