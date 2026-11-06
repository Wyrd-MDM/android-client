package link.wyrd.security

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature

class CommandVerifierTest {

    private lateinit var keyPair: KeyPair

    @Before
    fun setUp() {
        val kpg = try {
            KeyPairGenerator.getInstance("Ed25519")
        } catch (_: Throwable) {
            assumeTrue("Ed25519 not available on this JVM", false); return
        }
        keyPair = kpg.generateKeyPair()
    }

    private fun sign(data: ByteArray): ByteArray {
        val s = Signature.getInstance("Ed25519")
        s.initSign(keyPair.private)
        s.update(data)
        return s.sign()
    }

    @Test
    fun validSignature_Ok() {
        val data = "hello".toByteArray()
        val sig = sign(data)
        assertEquals(
            CommandVerifier.Outcome.Ok,
            CommandVerifier.evaluate(publicKey = keyPair.public, signedBytes = data, signature = sig)
        )
    }

    @Test
    fun tamperedPayload_BadSignature() {
        val data = "hello".toByteArray()
        val sig = sign(data)
        val tampered = data.copyOf().also { it[0] = 'H'.code.toByte() }
        assertEquals(
            CommandVerifier.Outcome.BadSignature,
            CommandVerifier.evaluate(keyPair.public, tampered, sig)
        )
    }

    @Test
    fun missingSignatureWhenRequired_Unsigned() {
        assertEquals(
            CommandVerifier.Outcome.Unsigned,
            CommandVerifier.evaluate(publicKey = keyPair.public, signedBytes = "x".toByteArray(), signature = null)
        )
    }

    @Test
    fun requiredButNoKey_NoKey() {
        val data = "x".toByteArray()
        val sig = sign(data)
        assertEquals(
            CommandVerifier.Outcome.NoKey,
            CommandVerifier.evaluate(publicKey = null, signedBytes = data, signature = sig)
        )
    }

    @Test
    fun emptySignatureTreatedAsMissing() {
        assertEquals(
            CommandVerifier.Outcome.Unsigned,
            CommandVerifier.evaluate(keyPair.public, "x".toByteArray(), ByteArray(0))
        )
    }
}
