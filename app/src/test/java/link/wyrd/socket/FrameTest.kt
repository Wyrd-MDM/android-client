package link.wyrd.socket

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import link.wyrd.security.CommandVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class FrameTest {

    private fun parse(text: String): JsonObject = Frame.json.parseToJsonElement(text).jsonObject

    @Test
    fun aSignedMessageCarriesTheBodyItWasSignedOver() {
        val sig = byteArrayOf(1, 2, 3)
        val ack = Frame.ClientFrame.Ack("cmd-1", true, """{"r":"рабочий"}""", sig)
        val msg = parse(ack.encode())
        assertEquals("ack", msg["type"]!!.jsonPrimitive.content)
        assertEquals(ack.body(), msg["body"]!!.jsonPrimitive.content)
        assertArrayEquals(sig, Base64.getDecoder().decode(msg["sig"]!!.jsonPrimitive.content))

        val body = parse(ack.body())
        assertEquals("cmd-1", body["id"]!!.jsonPrimitive.content)
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        assertEquals("""{"r":"рабочий"}""", body["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun theHelloBodyNamesWhatTheServerReads() {
        val body = parse(Frame.ClientFrame.Hello("uuid-x", "tok", 1_700_000_000L, bfu = true).body())
        assertEquals(setOf("device_id", "token", "ts", "bfu", "eph", "kem", "expiry_seq", "server_url"), body.keys)
        assertEquals(1_700_000_000L, body["ts"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun telemetryIsItsDataAsTheBody() {
        val data = buildJsonObject {
            put("ts", 1_700_000_000L)
            put("battery", buildJsonObject { put("pct", 80) })
        }
        val msg = parse(Frame.ClientFrame.Telemetry(data).encode())
        assertEquals("telemetry", msg["type"]!!.jsonPrimitive.content)
        assertEquals(data, parse(msg["body"]!!.jsonPrimitive.content))
    }

    @Test
    fun anUnsignedMessageGoesWithoutSig() {
        assertFalse("sig" in parse(Frame.ClientFrame.Ack("c", false, "").encode()))
        assertEquals(setOf("type"), parse(Frame.ClientFrame.Pong.encode()).keys)
    }

    @Test
    fun aCommandFromTheServerVerifies() {
        val frame = Frame.decodeServer(SERVER_COMMAND) as Frame.ServerFrame.Command
        assertEquals("4f1c2b8e-0d6a-4e57-9b1a-2c3d4e5f6a7b", frame.id)
        assertEquals("SET_USER_RESTRICTION", frame.type)
        assertEquals("рабочий \"профиль\"", frame.payload["note"]!!.jsonPrimitive.content)
        assertEquals(
            parse(SERVER_COMMAND)["body"]!!.jsonPrimitive.content,
            String(frame.signedBytes, Charsets.UTF_8),
        )

        val key = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(ED25519_SPKI_PREFIX + Base64.getDecoder().decode(SERVER_KEY)))
        val verify = { input: ByteArray -> CommandVerifier.evaluate(key, input, frame.signature) }
        assertEquals(CommandVerifier.Outcome.Ok, verify(CommandVerifier.COMMAND_SIGN_CONTEXT + frame.signedBytes))
        assertEquals(CommandVerifier.Outcome.BadSignature, verify(frame.signedBytes))
    }

    @Test
    fun handshakeAndKeepaliveMessages() {
        assertEquals(
            Frame.ServerFrame.HelloOk("{}", byteArrayOf(1, 2)),
            Frame.decodeServer("""{"type":"hello_ok","body":"{}","sig":"AQI="}"""),
        )
        assertEquals(
            Frame.ServerFrame.HelloErr("hello_replay"),
            Frame.decodeServer("""{"type":"hello_err","message":"hello_replay"}"""),
        )
        assertTrue(Frame.decodeServer("""{"type":"ping"}""") is Frame.ServerFrame.Ping)
        assertTrue(Frame.decodeServer("""{"type":"pong"}""") is Frame.ServerFrame.Pong)
    }

    @Test
    fun aCommandWithoutSigDecodesUnsigned() {
        val text = buildJsonObject {
            put("type", "command")
            put("body", """{"id":"a","type":"LOCK_NOW","payload":{}}""")
        }.toString()
        assertNull((Frame.decodeServer(text) as Frame.ServerFrame.Command).signature)
    }

    @Test
    fun malformedServerMessagesAreRefused() {
        val bad = listOf(
            "not json",
            """{"type":"nope"}""",
            """{"type":"command"}""",
            """{"type":"command","body":"{\"id\":\"a\",\"type\":\"X\",\"payload\":null}"}""",
            """{"type":"command","body":"{\"type\":\"X\",\"payload\":{}}"}""",
            """{"type":"command","body":"[1]"}""",
        )
        for (text in bad) {
            try {
                Frame.decodeServer(text)
                fail("accepted: $text")
            } catch (_: Frame.FrameException) {
            }
        }
    }

    @Test
    fun equalityLooksIntoTheSignature() {
        val a = Frame.ClientFrame.Ack("c", true, "", byteArrayOf(1))
        assertEquals(a, a.copy(signature = byteArrayOf(1)))
        assertFalse(a == a.copy(signature = byteArrayOf(2)))
        assertFalse(
            Frame.ClientFrame.Telemetry(JsonPrimitive(1)) == Frame.ClientFrame.Telemetry(JsonPrimitive(2))
        )
    }

    private companion object {
        val ED25519_SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        const val SERVER_KEY = "6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iw="

        const val SERVER_COMMAND = """{"body":"{\"id\":\"4f1c2b8e-0d6a-4e57-9b1a-2c3d4e5f6a7b\",\"payload\":{\"enabled\":true,\"note\":\"рабочий \\\"профиль\\\"\",\"restriction\":\"no_install_apps\"},\"type\":\"SET_USER_RESTRICTION\"}","sig":"DBnFyHXqdA8f6n7EZ24noAxI7/rurMmrPltBNEhGwxMNz0zt/NnmWxNlHyqcR5IWcZ3EEqYz98Ui5WfdQSyzBA==","type":"command"}"""
    }
}
