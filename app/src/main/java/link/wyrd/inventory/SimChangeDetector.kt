package link.wyrd.inventory

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager
import link.wyrd.prefs.WyrdPrefs
import java.security.MessageDigest

class SimChangeDetector(private val context: Context) {

    @SuppressLint("HardwareIds")
    fun currentHash(): String? {
        val iccids = try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
            sm.activeSubscriptionInfoList
                ?.mapNotNull { it.iccId?.takeIf { s -> s.isNotBlank() } }
                ?.sorted()
                ?: emptyList()
        } catch (_: SecurityException) {
            return null
        }
        if (iccids.isEmpty()) return null

        val salt = WyrdPrefs.get(context).simHashSalt
        val input = (salt + "|" + iccids.joinToString("|")).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(input)
            .joinToString("") { "%02x".format(it) }
    }
}
