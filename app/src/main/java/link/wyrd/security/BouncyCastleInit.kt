package link.wyrd.security

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

internal object BouncyCastleInit {

    const val PROVIDER_NAME: String = BouncyCastleProvider.PROVIDER_NAME

    @Volatile
    private var installed = false

    fun ensureInstalled() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            Security.removeProvider(PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            installed = true
        }
    }
}
