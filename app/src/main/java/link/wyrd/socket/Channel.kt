package link.wyrd.socket

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Channel {

    val HELLO_OK_SIGN_CONTEXT: ByteArray = "wyrd/hello_ok".toByteArray(Charsets.US_ASCII) + 0.toByte()
    private val TO_PHONE_INFO = "wyrd/channel/to-phone".toByteArray(Charsets.US_ASCII)
    private val TO_SERVER_INFO = "wyrd/channel/to-server".toByteArray(Charsets.US_ASCII)
    private val REQUEST_INFO = "wyrd/envelope/request".toByteArray(Charsets.US_ASCII)
    private val REPLY_INFO = "wyrd/envelope/reply".toByteArray(Charsets.US_ASCII)
    private val MLKEM = MLKEMParameters.ml_kem_768
    private const val X25519_LEN = 32
    private const val MLKEM_SEED_LEN = 64
    private const val KEY_LEN = 32
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val COUNTER_LEN = 8

    enum class Purpose(label: String) {
        ENROLL("wyrd/enroll"),
        LOGS("wyrd/logs"),
        HELLO("wyrd/hello");

        val aad: ByteArray = label.toByteArray(Charsets.US_ASCII)
    }

    class Sealed(val toServer: Direction, val fromServer: Direction)

    class Ephemeral(random: SecureRandom = SecureRandom()) {
        private val secret = X25519PrivateKeyParameters(random)
        private val kemSecret = MLKEMPrivateKeyParameters(MLKEM, ByteArray(MLKEM_SEED_LEN).also { random.nextBytes(it) })
        val public: ByteArray = secret.generatePublicKey().encoded
        val kem: ByteArray = kemSecret.getPublicKeyParameters().encoded

        fun finish(helloOk: Frame.ServerFrame.HelloOk, helloBody: ByteArray, serverKey: ByteArray): Sealed {
            val signature = helloOk.signature ?: throw IOException("hello_ok is not signed")
            val signer = Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(serverKey, 0))
                val input = HELLO_OK_SIGN_CONTEXT + helloOk.body.toByteArray(Charsets.UTF_8)
                update(input, 0, input.size)
            }
            if (!signer.verifySignature(signature)) throw IOException("hello_ok signature does not verify")

            val body = Frame.json.parseToJsonElement(helloOk.body) as? JsonObject
                ?: throw IOException("hello_ok body is not an object")
            fun field(name: String) = (body[name] as? JsonPrimitive)?.content?.let { Base64.getDecoder().decode(it) }
                ?: throw IOException("hello_ok without $name")
            if (!MessageDigest.isEqual(field("hello"), sha256(helloBody))) throw IOException("hello_ok answers another HELLO")

            val x25519 = agree(secret, field("eph"))
            val mlkem = runCatching { MLKEMExtractor(kemSecret).extractSecret(field("kem")) }
                .getOrElse { throw IOException("hello_ok carries a malformed ML-KEM ciphertext", it) }
            val (toPhone, toServer) = keys(hybrid(mlkem, x25519), helloOk.body.toByteArray(Charsets.UTF_8))
            return Sealed(Direction(toServer), Direction(toPhone))
        }
    }

    fun hybrid(mlkem: ByteArray, x25519: ByteArray): ByteArray = mlkem + x25519

    fun keys(ikm: ByteArray, helloOkBody: ByteArray): Pair<ByteArray, ByteArray> =
        hkdf(ikm, sha256(helloOkBody), TO_PHONE_INFO) to hkdf(ikm, sha256(helloOkBody), TO_SERVER_INFO)

    fun envelopeKeys(ikm: ByteArray, eph: ByteArray, server: ByteArray, kem: ByteArray): Pair<ByteArray, ByteArray> {
        val salt = sha256(eph + server + kem)
        return hkdf(ikm, salt, REQUEST_INFO) to hkdf(ikm, salt, REPLY_INFO)
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray) = ByteArray(KEY_LEN).also {
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ikm, salt, info)) }
            .generateBytes(it, 0, it.size)
    }

    private fun agree(secret: X25519PrivateKeyParameters, peer: ByteArray) = ByteArray(X25519_LEN).also {
        X25519Agreement().apply { init(secret) }.calculateAgreement(X25519PublicKeyParameters(peer, 0), it, 0)
    }

    class Envelope(val body: String, private val reply: Direction) {
        fun open(answer: String): String {
            val sealed = (Frame.json.parseToJsonElement(answer) as? JsonObject)?.get("sealed") as? JsonPrimitive
                ?: throw IOException("the answer is not sealed")
            return reply.open(sealed.content)
        }
    }

    fun seal(serverSealingKey: ByteArray, text: String, purpose: Purpose, random: SecureRandom = SecureRandom()): Envelope {
        val serverX25519 = serverSealingKey.copyOfRange(0, X25519_LEN)
        val serverKem = MLKEMPublicKeyParameters(MLKEM, serverSealingKey.copyOfRange(X25519_LEN, serverSealingKey.size))
        val secret = X25519PrivateKeyParameters(random)
        val eph = secret.generatePublicKey().encoded
        val encapsulated = MLKEMGenerator(random).generateEncapsulated(serverKem)
        val kem = encapsulated.encapsulation
        val ikm = hybrid(encapsulated.secret, agree(secret, serverX25519))
        val (request, reply) = envelopeKeys(ikm, eph, serverX25519, kem)
        val body = buildJsonObject {
            put("eph", Base64.getEncoder().encodeToString(eph))
            put("kem", Base64.getEncoder().encodeToString(kem))
            put("sealed", Direction(request, purpose.aad).seal(text))
        }.toString()
        return Envelope(body, Direction(reply, purpose.aad))
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    class Direction(private val key: ByteArray, private val aad: ByteArray = ByteArray(0)) {
        private var count = 0L

        @Synchronized
        fun seal(text: String): String = Base64.getEncoder().encodeToString(run(true, text.toByteArray(Charsets.UTF_8)))

        @Synchronized
        fun open(wire: String): String {
            val sealed = runCatching { Base64.getDecoder().decode(wire) }.getOrElse { throw IOException("not base64", it) }
            val plain = runCatching { run(false, sealed) }.getOrElse { throw IOException("a message did not open", it) }
            return String(plain, Charsets.UTF_8)
        }

        private fun run(encrypt: Boolean, input: ByteArray): ByteArray {
            val nonce = ByteArray(NONCE_LEN)
            for (i in 0 until COUNTER_LEN) {
                nonce[NONCE_LEN - 1 - i] = (count ushr (8 * i)).toByte()
            }
            count++
            val aead = ChaCha20Poly1305().apply { init(encrypt, AEADParameters(KeyParameter(key), TAG_BITS, nonce, aad)) }
            val out = ByteArray(aead.getOutputSize(input.size))
            val n = aead.processBytes(input, 0, input.size, out, 0)
            aead.doFinal(out, n)
            return out
        }
    }
}
