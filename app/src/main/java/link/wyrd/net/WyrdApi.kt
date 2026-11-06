package link.wyrd.net

import android.content.Context
import link.wyrd.prefs.WyrdPrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.wyrd.socket.Channel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class WyrdApi(private val context: Context) {

    private val prefs get() = WyrdPrefs.get(context)

    private val client: OkHttpClient get() = sharedClient

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun sealed(inner: JsonObject, purpose: Channel.Purpose): Channel.Envelope =
        Channel.seal(sealingKey(context), inner.toString(), purpose)

    fun enroll(req: EnrollRequest): EnrollResponse {
        val envelope = sealed(buildJsonObject {
            put("token", prefs.enrollToken.orEmpty())
            put("request", json.encodeToJsonElement(EnrollRequest.serializer(), req))
        }, Channel.Purpose.ENROLL)
        val http = Request.Builder()
            .url(url("/enroll"))
            .post(envelope.body.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(http).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val text = runCatching { envelope.open(raw) }.getOrElse { if (resp.isSuccessful) throw it else raw }
            if (!resp.isSuccessful) {
                val answer = runCatching { json.decodeFromString(EnrollResponse.serializer(), text) }.getOrNull()
                throw EnrollRefused(resp.code, text, answer)
            }
            return json.decodeFromString(EnrollResponse.serializer(), text)
        }
    }

    class EnrollRefused(code: Int, text: String, val answer: EnrollResponse?) :
        IllegalStateException("enroll failed: HTTP $code — $text")

    fun uploadLogs(kind: String, gzippedBody: ByteArray): UploadResult {
        return try {
            val envelope = sealed(buildJsonObject {
                put("token", prefs.enrollToken.orEmpty())
                put("seq", prefs.nextLogsSeq())
                put("kind", kind)
                put("data", java.util.Base64.getEncoder().encodeToString(gzippedBody))
            }, Channel.Purpose.LOGS)
            val http = Request.Builder()
                .url(url("/device-api/logs"))
                .post(envelope.body.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(http).execute().use { resp ->
                when {
                    resp.isSuccessful -> UploadResult.Success
                    resp.code in 500..599 || resp.code == 429 -> UploadResult.Transient
                    else -> UploadResult.Permanent(resp.code)
                }
            }
        } catch (e: Exception) {
            UploadResult.Transient
        }
    }

    sealed class UploadResult {
        object Success : UploadResult()
        object Transient : UploadResult()
        data class Permanent(val code: Int) : UploadResult()
    }

    private fun url(path: String): String {
        val base = prefs.serverUrl?.trimEnd('/')
            ?: throw IllegalStateException("server_url is not configured")
        return base + path
    }

    companion object {
        fun sealingKey(context: Context): ByteArray =
            WyrdPrefs.get(context).serverSealingKey?.takeIf { it.isNotBlank() }
                ?.let { java.util.Base64.getDecoder().decode(it) }
                ?: throw IllegalStateException("no server sealing key")

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
