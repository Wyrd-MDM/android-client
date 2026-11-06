package link.wyrd.enroll

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import link.wyrd.net.EnrollRequest
import link.wyrd.prefs.WyrdPrefs
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

object AttestationManager {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val REATTEST_ALIAS = "wyrd_reattest"

    fun buildEnrollmentPayload(context: Context): EnrollRequest {
        val prefs = WyrdPrefs.get(context)
        val token = prefs.enrollToken
            ?: error("missing enrollment token — cannot build attestation")
        val chain = freshChain(prefs.attestationAlias, token)
        return EnrollRequest(
            deviceId = prefs.deviceId,
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            androidSdk = Build.VERSION.SDK_INT,
            attestationChain = chain.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) },
        )
    }

    private fun freshChain(alias: String, challenge: String): List<X509Certificate> {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        generateKey(alias, challenge)
        return readChain(ks, alias)
    }

    fun produceAttestationChain(challenge: String): List<X509Certificate> {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val alias = REATTEST_ALIAS
        generateKey(alias, challenge)
        return readChain(ks, alias)
    }

    private fun generateKey(alias: String, challenge: String) {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge.toByteArray(Charsets.UTF_8))
            .setDevicePropertiesAttestationIncluded(true)
            .build()

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        kpg.initialize(spec)
        kpg.generateKeyPair()
    }

    private fun readChain(ks: KeyStore, alias: String): List<X509Certificate> {
        val chain = ks.getCertificateChain(alias) ?: emptyArray()
        return chain.filterIsInstance<X509Certificate>()
    }
}
