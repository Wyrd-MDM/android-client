package link.wyrd.inventory

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.TelephonyManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class InventoryCollector(private val context: Context) {

    fun collect(): JsonObject = buildJsonObject {
        put("model", Build.MODEL)
        put("manufacturer", Build.MANUFACTURER)
        put("device", Build.DEVICE)
        put("androidVersion", Build.VERSION.RELEASE)
        put("androidSdk", Build.VERSION.SDK_INT)
        put("securityPatch", Build.VERSION.SECURITY_PATCH)
        put("serial", getSerial())
        put("imei", getImei())

        val stat = StatFs(Environment.getDataDirectory().path)
        val totalMb = stat.totalBytes / (1024 * 1024)
        val freeMb = stat.availableBytes / (1024 * 1024)
        put("storageTotalMb", totalMb)
        put("storageFreeMb", freeMb)

        BatteryReader.read(context)?.let { b ->
            b.pct?.let { put("batteryPct", it) }
            put("batteryCharging", b.charging)
            put("batteryHealth", b.health)
        }
    }

    @SuppressLint("HardwareIds")
    private fun getSerial(): String {
        return try {
            Build.getSerial()
        } catch (_: SecurityException) {
            "unavailable"
        }
    }

    @SuppressLint("HardwareIds")
    private fun getImei(): String {
        return try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            tm.imei ?: "n/a"
        } catch (_: SecurityException) {
            "unavailable"
        }
    }

}
