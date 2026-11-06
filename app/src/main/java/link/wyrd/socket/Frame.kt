package link.wyrd.socket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

object Frame {

    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    sealed class ClientFrame {
        data class Hello(
            val deviceId: String,
            val token: String,
            val ts: Long = 0L,
            val bfu: Boolean = false,
            val eph: ByteArray = ByteArray(0),
            val kem: ByteArray = ByteArray(0),
            val expirySeq: Long = 0L,
            val serverUrl: String = "",
            val signature: ByteArray? = null,
        ) : ClientFrame() {
            fun body(): String = buildJsonObject {
                put("device_id", deviceId)
                put("token", token)
                put("ts", ts)
                put("bfu", bfu)
                put("eph", Base64.getEncoder().encodeToString(eph))
                put("kem", Base64.getEncoder().encodeToString(kem))
                put("expiry_seq", expirySeq)
                put("server_url", serverUrl)
            }.toString()
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Hello) return false
                return deviceId == other.deviceId && token == other.token &&
                    ts == other.ts && bfu == other.bfu && eph.contentEquals(other.eph) &&
                    kem.contentEquals(other.kem) &&
                    expirySeq == other.expirySeq && serverUrl == other.serverUrl &&
                    signature.contentEqualsNullable(other.signature)
            }
            override fun hashCode(): Int {
                var r = deviceId.hashCode()
                r = 31 * r + token.hashCode()
                r = 31 * r + ts.hashCode()
                r = 31 * r + bfu.hashCode()
                r = 31 * r + eph.contentHashCode()
                r = 31 * r + kem.contentHashCode()
                r = 31 * r + expirySeq.hashCode()
                r = 31 * r + serverUrl.hashCode()
                r = 31 * r + (signature?.contentHashCode() ?: 0)
                return r
            }
        }
        object Ping : ClientFrame()
        object Pong : ClientFrame()
        data class Ack(
            val id: String,
            val ok: Boolean,
            val message: String,
            val signature: ByteArray? = null,
        ) : ClientFrame() {
            fun body(): String = buildJsonObject {
                put("id", id)
                put("ok", ok)
                put("message", message)
            }.toString()
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Ack) return false
                return id == other.id && ok == other.ok && message == other.message &&
                    signature.contentEqualsNullable(other.signature)
            }
            override fun hashCode(): Int {
                var r = id.hashCode()
                r = 31 * r + ok.hashCode()
                r = 31 * r + message.hashCode()
                r = 31 * r + (signature?.contentHashCode() ?: 0)
                return r
            }
        }
        data class Telemetry(
            val data: JsonElement,
            val signature: ByteArray? = null,
        ) : ClientFrame() {
            fun body(): String = json.encodeToString(JsonElement.serializer(), data)
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Telemetry) return false
                return data == other.data && signature.contentEqualsNullable(other.signature)
            }
            override fun hashCode(): Int {
                var r = data.hashCode()
                r = 31 * r + (signature?.contentHashCode() ?: 0)
                return r
            }
        }

        fun encode(): String = when (this) {
            is Hello -> signed("hello", body(), signature)
            is Ping -> buildJsonObject { put("type", "ping") }.toString()
            is Pong -> buildJsonObject { put("type", "pong") }.toString()
            is Ack -> signed("ack", body(), signature)
            is Telemetry -> signed("telemetry", body(), signature)
        }
    }

    sealed class ServerFrame {
        data class HelloOk(val body: String, val signature: ByteArray?) : ServerFrame() {
            override fun equals(other: Any?): Boolean =
                other is HelloOk && body == other.body && signature.contentEqualsNullable(other.signature)
            override fun hashCode(): Int = 31 * body.hashCode() + (signature?.contentHashCode() ?: 0)
        }
        data class HelloErr(val message: String) : ServerFrame()
        object Ping : ServerFrame()
        object Pong : ServerFrame()
        data class Command(
            val id: String,
            val type: String,
            val payload: JsonObject,
            val signature: ByteArray?,
            val signedBytes: ByteArray
        ) : ServerFrame() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Command) return false
                return id == other.id && type == other.type && payload == other.payload &&
                    signature.contentEqualsNullable(other.signature) &&
                    signedBytes.contentEquals(other.signedBytes)
            }
            override fun hashCode(): Int {
                var r = id.hashCode()
                r = 31 * r + type.hashCode()
                r = 31 * r + payload.hashCode()
                r = 31 * r + (signature?.contentHashCode() ?: 0)
                r = 31 * r + signedBytes.contentHashCode()
                return r
            }
        }
    }

    class FrameException(msg: String, cause: Throwable? = null) : RuntimeException(msg, cause)

    fun decodeServer(text: String): ServerFrame {
        val msg = runCatching { json.parseToJsonElement(text) as? JsonObject }
            .getOrElse { throw FrameException("server message is not JSON: ${it.message}", it) }
            ?: throw FrameException("server message is not a JSON object")
        return when (val type = msg.string("type")) {
            "hello_ok" -> ServerFrame.HelloOk(
                body = msg.string("body") ?: throw FrameException("hello_ok without body"),
                signature = msg.string("sig")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() },
            )
            "hello_err" -> ServerFrame.HelloErr(msg.string("message") ?: "")
            "ping" -> ServerFrame.Ping
            "pong" -> ServerFrame.Pong
            "command" -> {
                val body = msg.string("body") ?: throw FrameException("command without body")
                val signature = msg.string("sig")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                val parsed = runCatching { json.parseToJsonElement(body) as? JsonObject }
                    .getOrElse { throw FrameException("command body json: ${it.message}", it) }
                    ?: throw FrameException("command body not a JSON object")
                ServerFrame.Command(
                    id = parsed.string("id") ?: throw FrameException("command without id"),
                    type = parsed.string("type") ?: throw FrameException("command without type"),
                    payload = parsed["payload"] as? JsonObject
                        ?: throw FrameException("command payload not JSON object"),
                    signature = signature,
                    signedBytes = body.toByteArray(Charsets.UTF_8),
                )
            }
            else -> throw FrameException("unknown server message type $type")
        }
    }

    private fun signed(type: String, body: String, signature: ByteArray?): String = buildJsonObject {
        put("type", type)
        put("body", body)
        if (signature != null) put("sig", Base64.getEncoder().encodeToString(signature))
    }.toString()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean {
        if (this == null && other == null) return true
        if (this == null || other == null) return false
        return this.contentEquals(other)
    }
}
