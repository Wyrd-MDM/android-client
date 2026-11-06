package link.wyrd.geo

import android.content.Context
import link.wyrd.prefs.WyrdPrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GeoZone(
    val name: String,
    val kind: String,
    val action: String,
    val polygon: List<DoubleArray>,
)

class Geofence(
    val zones: List<GeoZone>,
    val tilesBase: String? = null,
    val tilesToken: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun current(context: Context): Geofence? =
            WyrdPrefs.get(context).geofence?.let { fromJson(it) }

        fun isActive(context: Context): Boolean = current(context) != null

        fun fromJson(raw: String): Geofence? =
            runCatching { parse(raw) }.getOrNull()?.takeIf { it.zones.isNotEmpty() }

        private fun parse(raw: String): Geofence {
            val obj = json.parseToJsonElement(raw).jsonObject
            if (obj["enabled"]?.jsonPrimitive?.booleanOrNull != true) return Geofence(emptyList())
            val tiles = obj["tiles"] as? kotlinx.serialization.json.JsonObject
            val tilesBase = tiles?.get("base")?.jsonPrimitive?.contentOrNull
            val tilesToken = tiles?.get("token")?.jsonPrimitive?.contentOrNull
            val zones = (obj["zones"] as? JsonArray).orEmpty().map { z ->
                val o = z.jsonObject
                GeoZone(
                    name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: "keep_in",
                    action = o["action"]?.jsonPrimitive?.contentOrNull ?: "lock",
                    polygon = (o["polygon"] as? JsonArray).orEmpty().mapNotNull { pt ->
                        val pair = pt as? JsonArray ?: return@mapNotNull null
                        val lat = pair.getOrNull(0)?.jsonPrimitive?.doubleOrNull
                        val lng = pair.getOrNull(1)?.jsonPrimitive?.doubleOrNull
                        if (lat != null && lng != null) doubleArrayOf(lat, lng) else null
                    },
                )
            }
            return Geofence(zones, tilesBase, tilesToken)
        }

        private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
    }
}
