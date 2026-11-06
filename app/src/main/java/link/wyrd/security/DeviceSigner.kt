package link.wyrd.security

import android.content.Context
import android.util.Log
import link.wyrd.prefs.WyrdPrefs
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

class DeviceSigner(private val context: Context) {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun signAck(body: ByteArray): ByteArray? =
        signWithContext(ACK_SIGN_CONTEXT, body)

    fun signTelemetry(body: ByteArray): ByteArray? =
        signWithContext(TELEM_SIGN_CONTEXT, body)

    fun signHello(body: ByteArray): ByteArray? =
        signWithContext(HELLO_SIGN_CONTEXT, body)

    private fun signWithContext(prefix: ByteArray, body: ByteArray): ByteArray? {
        val priv = loadPrivateKey() ?: return null
        return try {
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(priv)
            signer.update(prefix)
            signer.update(body)
            signer.sign()
        } catch (t: Throwable) {
            Log.w(TAG, "frame signing failed", t)
            null
        }
    }

    private fun loadPrivateKey(): PrivateKey? {
        val alias = WyrdPrefs.get(context).attestationAlias
        return try {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            entry?.privateKey
        } catch (t: Throwable) {
            Log.w(TAG, "keystore lookup failed for alias=$alias", t)
            null
        }
    }

    companion object {
        private const val TAG = "DeviceSigner"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        val HELLO_SIGN_CONTEXT: ByteArray = "wyrd/hello".toByteArray(Charsets.US_ASCII) + 0.toByte()
        val ACK_SIGN_CONTEXT: ByteArray = "wyrd/ack".toByteArray(Charsets.US_ASCII) + 0.toByte()
        val TELEM_SIGN_CONTEXT: ByteArray = "wyrd/telem".toByteArray(Charsets.US_ASCII) + 0.toByte()
    }
}
