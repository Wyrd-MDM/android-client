package link.wyrd.inventory

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import link.wyrd.prefs.WyrdPrefs
import java.util.concurrent.TimeUnit

class LocationSampleWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val prefs = WyrdPrefs.get(applicationContext)
        if (!prefs.enrolled || !prefs.shareLocation) return Result.success()
        return try {
            val got = LocationReporter(applicationContext).sampleAndCache()
            Log.i(TAG, "periodic location sample: cached=$got")
            runCatching { link.wyrd.geo.GeofenceEnforcer.check(applicationContext) }
                .onFailure { Log.w(TAG, "geofence check after sample failed", it) }
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "location sample failed", t)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "LocSampleWorker"
        private const val WORK_NAME = "wyrd-location-sample"

        private const val INTERVAL_MINUTES = 15L

        fun enqueue(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!WyrdPrefs.get(context).shareLocation) {
                Log.i(TAG, "location not shared — sampler off")
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<LocationSampleWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
