package link.wyrd.security

import android.content.Context
import java.security.PublicKey
import java.security.Signature

class CommandVerifier(private val context: Context) {

    enum class Outcome {
        Ok,
        BadSignature,
        Unsigned,
        NoKey,
    }

    fun verify(signedBytes: ByteArray, signature: ByteArray?): Outcome {
        val input = COMMAND_SIGN_CONTEXT + signedBytes
        return evaluate(
            publicKey = ServerKeyStore.loadPublicKey(context),
            signedBytes = input,
            signature = signature,
        )
    }

    companion object {
        val COMMAND_SIGN_CONTEXT: ByteArray = "wyrd/cmd".toByteArray(Charsets.US_ASCII) + 0.toByte()

        fun evaluate(
            publicKey: PublicKey?,
            signedBytes: ByteArray,
            signature: ByteArray?,
        ): Outcome {
            if (signature == null || signature.isEmpty()) return Outcome.Unsigned
            val key = publicKey ?: return Outcome.NoKey
            return try {
                BouncyCastleInit.ensureInstalled()
                val sig = Signature.getInstance("Ed25519", BouncyCastleInit.PROVIDER_NAME)
                sig.initVerify(key)
                sig.update(signedBytes)
                if (sig.verify(signature)) Outcome.Ok else Outcome.BadSignature
            } catch (_: Throwable) {
                Outcome.BadSignature
            }
        }
    }
}
