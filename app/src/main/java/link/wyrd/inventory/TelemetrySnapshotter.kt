package link.wyrd.inventory

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import link.wyrd.prefs.WyrdPrefs

class TelemetrySnapshotter(private val context: Context) {

    private val version: Pair<String, Long>? = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        (pi.versionName ?: return@runCatching null) to pi.longVersionCode
    }.getOrNull()

    fun snapshot(): JsonObject = buildJsonObject {
        put("kind", "status")
        put("ts", System.currentTimeMillis() / 1000)
        version?.let { (name, code) ->
            put("dpcVersion", name)
            put("dpcVersionCode", code)
        }
        BatteryReader.read(context)?.let { b ->
            put("battery", buildJsonObject {
                b.pct?.let { put("pct", it) }
                put("charging", b.charging)
                put("health", b.health)
            })
        }
        put("locationShare", WyrdPrefs.get(context).shareLocation)
        latestUnsentLocation()?.let { put("location", it) }
    }

    private fun latestUnsentLocation(): JsonObject? {
        val prefs = WyrdPrefs.get(context)
        if (!prefs.shareLocation) return null
        val cached = LocationCache(context).read() ?: return null
        val ts = cached["timestamp"]?.jsonPrimitive?.longOrNull ?: return null
        if (ts <= prefs.lastLocationSentTs) return null
        prefs.lastLocationSentTs = ts
        return cached
    }
}
