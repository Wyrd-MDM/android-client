package link.wyrd.socket

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class ChannelTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun agree(secret: X25519PrivateKeyParameters, peer: ByteArray) = ByteArray(32).also {
        X25519Agreement().apply { init(secret) }.calculateAgreement(X25519PublicKeyParameters(peer, 0), it, 0)
    }
    private val ikm = Channel.hybrid(ByteArray(32) { 1 }, ByteArray(32) { 2 })

    @Test
    fun aKnownAnswerSharedWithTheServer() {
        val (toPhone, toServer) = Channel.keys(ikm, "hello_ok".toByteArray())
        assertEquals("8cd77523ce8094dd950c2eaf8e04d5d36513b56e65d71d3acbc17902ec8b6d95", hex(toPhone))
        assertEquals("4ae9329df48eb0106a99563d216d79f28da82887fd9407a4ad5e0e24b24d78f3", hex(toServer))
        val d = Channel.Direction(toPhone)
        assertEquals("YYpJpEjFx6w9xhRAamUBWL21ZRY=", d.seal("ping"))
        assertEquals("9h44rqrS3w4NL6DHVgApzDR2Pioa+aBg6j4=", d.seal("слово"))
    }

    @Test
    fun envelopeKeysSharedWithTheServer() {
        val (request, reply) =
            Channel.envelopeKeys(ikm, ByteArray(32) { 3 }, ByteArray(32) { 4 }, ByteArray(1088) { 5 })
        assertEquals("bd04e71e268cd208326a9af98e8e9eb6c0ec7d7059b9ffe1701bb29b994c7d99", hex(request))
        assertEquals("e2200437ef659a3822de81c1b9df3c2b95da487801c831fd16ba60c0d2aa4fa5", hex(reply))
        assertEquals(
            "liZz0suYmop4UutETa13suppp3kF4huQesSTvT4=",
            Channel.Direction(request, Channel.Purpose.LOGS.aad).seal("""{"token":"t"}"""),
        )
    }

    @Test
    fun mlkemAgreesWithTheServer() {
        val pair = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, ByteArray(64) { 6 })
        val public = pair.getPublicKeyParameters()
        assertEquals("5fe09f109d4ccd040e5dc170e337361bdd0092739ca71d3d56c9c7eea95ddc37", hex(sha256(public.encoded)))
        val encapsulated = MLKEMGenerator.internalGenerateEncapsulated(public, ByteArray(32) { 7 })
        assertEquals("52c493d43969c5980aab35b28a171f8fe41346b51533ee3d9395bfa0a2f8796b", hex(sha256(encapsulated.encapsulation)))
        assertEquals("9266ab52cdd6812edf508f968202ec18223aebc89b9bfbea273af0248f3665fa", hex(encapsulated.secret))
    }

    @Test
    fun anEnvelopeOpensOnlyAtTheServerAndOnlyForItsPurpose() {
        val server = X25519PrivateKeyParameters(SecureRandom())
        val serverKem = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, ByteArray(64).also { SecureRandom().nextBytes(it) })
        val serverX25519 = server.generatePublicKey().encoded
        val envelope = Channel.seal(serverX25519 + serverKem.getPublicKeyParameters().encoded, "request", Channel.Purpose.LOGS)

        val wire = Frame.json.parseToJsonElement(envelope.body).jsonObject
        fun field(name: String) = Base64.getDecoder().decode(wire[name]!!.jsonPrimitive.content)
        val mlkem = MLKEMExtractor(serverKem).extractSecret(field("kem"))
        val (request, reply) =
            Channel.envelopeKeys(Channel.hybrid(mlkem, agree(server, field("eph"))), field("eph"), serverX25519, field("kem"))
        val sealed = wire["sealed"]!!.jsonPrimitive.content
        assertEquals("request", Channel.Direction(request, Channel.Purpose.LOGS.aad).open(sealed))
        assertThrows(IOException::class.java) { Channel.Direction(request, Channel.Purpose.ENROLL.aad).open(sealed) }

        val answer = buildJsonObject { put("sealed", Channel.Direction(reply, Channel.Purpose.LOGS.aad).seal("answer")) }
        assertEquals("answer", envelope.open(answer.toString()))
        assertThrows(IOException::class.java) { envelope.open("""{"ok":true}""") }
    }

    @Test
    fun aMessageOpensOnceInOrderAndUnaltered() {
        val key = ByteArray(32) { 5 }
        val a = Channel.Direction(key)
        val b = Channel.Direction(key)
        val first = a.seal("one")
        val second = a.seal("two")
        assertEquals("one", b.open(first))
        assertThrows(IOException::class.java) { b.open(first) }
        assertThrows(IOException::class.java) { Channel.Direction(key).open(second) }
        val tampered = Base64.getDecoder().decode(first).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { Channel.Direction(key).open(Base64.getEncoder().encodeToString(tampered)) }
    }

    private class Server(val hello: ByteArray, phone: Channel.Ephemeral) {
        val key = Ed25519PrivateKeyParameters(SecureRandom())
        private val secret = X25519PrivateKeyParameters(SecureRandom())
        private val x25519 = ByteArray(32).also {
            X25519Agreement().apply { init(secret) }.calculateAgreement(X25519PublicKeyParameters(phone.public, 0), it, 0)
        }
        private val encapsulated = MLKEMGenerator(SecureRandom())
            .generateEncapsulated(MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, phone.kem))
        val ikm = Channel.hybrid(encapsulated.secret, x25519)

        fun helloOk(answering: ByteArray = hello, signWith: Ed25519PrivateKeyParameters = key): Frame.ServerFrame.HelloOk {
            val body = buildJsonObject {
                put("eph", Base64.getEncoder().encodeToString(secret.generatePublicKey().encoded))
                put("kem", Base64.getEncoder().encodeToString(encapsulated.encapsulation))
                put("hello", Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(answering)))
            }.toString()
            val input = Channel.HELLO_OK_SIGN_CONTEXT + body.toByteArray()
            val sig = Ed25519Signer().apply { init(true, signWith); update(input, 0, input.size) }.generateSignature()
            return Frame.ServerFrame.HelloOk(body, sig)
        }
    }

    @Test
    fun theHandshakeAgreesWithTheServerAndRefusesAnythingElse() {
        val eph = Channel.Ephemeral()
        val hello = "hello body".toByteArray()
        val server = Server(hello, eph)
        val serverKey = server.key.generatePublicKey().encoded

        val helloOk = server.helloOk()
        val sealed = eph.finish(helloOk, hello, serverKey)
        val (toPhone, toServer) = Channel.keys(server.ikm, helloOk.body.toByteArray())
        assertEquals("lock", sealed.fromServer.open(Channel.Direction(toPhone).seal("lock")))
        assertEquals("ack", Channel.Direction(toServer).open(sealed.toServer.seal("ack")))

        assertThrows(IOException::class.java) {
            eph.finish(server.helloOk(signWith = Ed25519PrivateKeyParameters(SecureRandom())), hello, serverKey)
        }
        assertThrows(IOException::class.java) {
            eph.finish(server.helloOk(answering = "another".toByteArray()), hello, serverKey)
        }
        assertThrows(IOException::class.java) {
            eph.finish(helloOk.copy(signature = null), hello, serverKey)
        }
    }
}
