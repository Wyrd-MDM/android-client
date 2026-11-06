package link.wyrd.security

import android.content.Context
import android.util.Base64
import link.wyrd.prefs.WyrdPrefs
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

object ServerKeyStore {

    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a,
        0x30, 0x05,
        0x06, 0x03, 0x2b, 0x65.toByte(), 0x70,
        0x03, 0x21, 0x00
    )

    fun loadPublicKey(context: Context): PublicKey? {
        val b64 = WyrdPrefs.get(context).serverPublicKey?.takeIf { it.isNotBlank() } ?: return null
        return parse(b64)
    }

    fun parse(base64Raw: String): PublicKey {
        BouncyCastleInit.ensureInstalled()
        val raw = Base64.decode(base64Raw, Base64.DEFAULT)
        require(raw.size == 32) { "Ed25519 pubkey must be 32 bytes, got ${raw.size}" }
        val der = ED25519_SPKI_PREFIX + raw
        val kf = KeyFactory.getInstance("Ed25519", BouncyCastleInit.PROVIDER_NAME)
        return kf.generatePublic(X509EncodedKeySpec(der))
    }
}
