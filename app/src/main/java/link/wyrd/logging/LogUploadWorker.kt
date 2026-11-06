package link.wyrd.logging

import android.app.admin.ConnectEvent
import android.app.admin.DnsEvent
import android.app.admin.NetworkEvent
import android.app.admin.SecurityLog
import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import link.wyrd.admin.DpmActions
import link.wyrd.net.WyrdApi
import link.wyrd.prefs.WyrdPrefs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class LogUploadWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: return Result.failure()
        val prefs = WyrdPrefs.get(applicationContext)
        if (!prefs.enrolled || prefs.serverUrl.isNullOrBlank() || prefs.enrollToken.isNullOrBlank()) {
            return Result.success()
        }
        val dpm = DpmActions(applicationContext)

        val (uploadKind, ndjson) = when (kind) {
            KIND_SECURITY -> "security" to securityEventsToNdjson(dpm.retrieveSecurityLogs())
            KIND_SECURITY_PREREBOOT -> "security" to securityEventsToNdjson(dpm.retrievePreRebootSecurityLogs())
            KIND_NETWORK -> {
                val token = inputData.getLong(KEY_BATCH_TOKEN, -1L)
                if (token < 0L) {
                    Log.w(TAG, "network upload missing batch token")
                    return Result.failure()
                }
                "network" to networkEventsToNdjson(dpm.retrieveNetworkLogs(token))
            }
            else -> {
                Log.w(TAG, "unknown kind=$kind")
                return Result.failure()
            }
        }

        if (ndjson.isEmpty()) {
            return Result.success()
        }

        val gzipped = gzip(ndjson.toByteArray(Charsets.UTF_8))
        val api = WyrdApi(applicationContext)
        return when (val r = api.uploadLogs(uploadKind, gzipped)) {
            WyrdApi.UploadResult.Success -> Result.success()
            WyrdApi.UploadResult.Transient -> {
                Log.w(TAG, "upload transient failure; retrying via WorkManager backoff")
                Result.retry()
            }
            is WyrdApi.UploadResult.Permanent -> {
                Log.w(TAG, "upload permanent failure HTTP ${r.code}; dropping batch")
                Result.success()
            }
        }
    }

    private fun securityEventsToNdjson(events: List<SecurityLog.SecurityEvent>): String {
        if (events.isEmpty()) return ""
        val sb = StringBuilder()
        for (e in events) {
            if (e.tag in PERSONAL_SIDE_TAGS) continue
            val obj = buildJsonObject {
                put("t", JsonPrimitive(e.timeNanos))
                put("id", JsonPrimitive(e.id))
                put("tag", JsonPrimitive(securityTagName(e.tag)))
                put("level", JsonPrimitive(levelName(e.logLevel)))
                val data = e.data
                if (data != null) put("data", securityDataToJson(data))
            }
            sb.append(JSON.encodeToString(JsonObject.serializer(), obj))
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun networkEventsToNdjson(events: List<NetworkEvent>): String {
        if (events.isEmpty()) return ""
        val sb = StringBuilder()
        for (e in events) {
            val obj = buildJsonObject {
                put("t_ms", JsonPrimitive(e.timestamp))
                put("id", JsonPrimitive(e.id))
                put("pkg", JsonPrimitive(e.packageName ?: ""))
                when (e) {
                    is DnsEvent -> {
                        put("kind", JsonPrimitive("dns"))
                        put("host", JsonPrimitive(e.hostname ?: ""))
                        put("hits", JsonPrimitive(e.totalResolvedAddressCount))
                        val ips = e.inetAddresses
                        if (ips != null) {
                            put("ips", JsonArray(ips.map { JsonPrimitive(it.hostAddress ?: "") }))
                        }
                    }
                    is ConnectEvent -> {
                        put("kind", JsonPrimitive("connect"))
                        put("host", JsonPrimitive(e.inetAddress?.hostAddress ?: ""))
                        put("port", JsonPrimitive(e.port))
                    }
                    else -> put("kind", JsonPrimitive("unknown"))
                }
            }
            sb.append(JSON.encodeToString(JsonObject.serializer(), obj))
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun securityDataToJson(data: Any?): kotlinx.serialization.json.JsonElement {
        if (data == null) return JsonPrimitive("")
        return when (data) {
            is Array<*> -> buildJsonArray { for (item in data) add(securityDataToJson(item)) }
            is Long -> JsonPrimitive(data)
            is Int -> JsonPrimitive(data)
            is Boolean -> JsonPrimitive(data)
            is String -> JsonPrimitive(data)
            is Float -> JsonPrimitive(data.toDouble())
            is Double -> JsonPrimitive(data)
            else -> JsonPrimitive(data.toString())
        }
    }

    private fun securityTagName(tag: Int): String {
        return SECURITY_TAG_NAMES[tag] ?: "TAG_$tag"
    }

    private fun levelName(level: Int): String = when (level) {
        SecurityLog.LEVEL_INFO -> "INFO"
        SecurityLog.LEVEL_WARNING -> "WARNING"
        SecurityLog.LEVEL_ERROR -> "ERROR"
        else -> "LEVEL_$level"
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "LogUploadWorker"
        private const val UNIQUE_WORK = "log-upload"

        const val KEY_KIND = "kind"
        const val KEY_BATCH_TOKEN = "batch_token"

        const val KIND_SECURITY = "security"
        const val KIND_SECURITY_PREREBOOT = "security_prereboot"
        const val KIND_NETWORK = "network"

        private val JSON = kotlinx.serialization.json.Json { encodeDefaults = false }

        private val PERSONAL_SIDE_TAGS = setOf(
            SecurityLog.TAG_WIFI_CONNECTION,
            SecurityLog.TAG_WIFI_DISCONNECTION,
            SecurityLog.TAG_BLUETOOTH_CONNECTION,
            SecurityLog.TAG_BLUETOOTH_DISCONNECTION,
        )

        private val SECURITY_TAG_NAMES: Map<Int, String> by lazy {
            val out = HashMap<Int, String>()
            try {
                for (f in SecurityLog::class.java.declaredFields) {
                    if (f.name.startsWith("TAG_") && f.type == Int::class.javaPrimitiveType) {
                        try {
                            out[f.getInt(null)] = f.name
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            out
        }

        fun enqueueSecurity(context: Context) {
            val req = OneTimeWorkRequestBuilder<LogUploadWorker>()
                .setInputData(Data.Builder().putString(KEY_KIND, KIND_SECURITY).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                req
            )
        }

        fun enqueueSecurityPreReboot(context: Context) {
            val req = OneTimeWorkRequestBuilder<LogUploadWorker>()
                .setInputData(Data.Builder().putString(KEY_KIND, KIND_SECURITY_PREREBOOT).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                req
            )
        }

        fun enqueueNetwork(context: Context, batchToken: Long) {
            val req = OneTimeWorkRequestBuilder<LogUploadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_KIND, KIND_NETWORK)
                        .putLong(KEY_BATCH_TOKEN, batchToken)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                req
            )
        }
    }
}
