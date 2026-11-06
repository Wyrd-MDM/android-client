package link.wyrd.managedconfig

import android.content.Context
import android.content.RestrictionEntry
import android.content.RestrictionsManager
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

object SchemaCodec {

    fun fetchAndEncode(context: Context, packageName: String): JsonObject {
        val rm = context.getSystemService(RestrictionsManager::class.java)
            ?: return buildJsonObject {
                put("packageName", JsonPrimitive(packageName))
                put("restrictions", buildJsonArray { })
            }

        val entries: List<RestrictionEntry> =
            rm.getManifestRestrictions(packageName) ?: emptyList()

        val versionCode: Long? = runCatching {
            val pi = context.packageManager.getPackageInfo(packageName, 0)
            pi.longVersionCode
        }.getOrNull()

        return buildJsonObject {
            put("packageName", JsonPrimitive(packageName))
            if (versionCode != null) put("versionCode", JsonPrimitive(versionCode))
            put("restrictions", buildJsonArray { entries.forEach { add(entryToJson(it)) } })
        }
    }

    private fun entryToJson(e: RestrictionEntry): JsonObject = buildJsonObject {
        put("key", JsonPrimitive(e.key))
        put("type", JsonPrimitive(wireType(e.type)))
        e.title?.let { put("title", JsonPrimitive(it)) }
        e.description?.let { put("description", JsonPrimitive(it)) }

        when (e.type) {
            RestrictionEntry.TYPE_BOOLEAN ->
                put("defaultValue", JsonPrimitive(e.selectedState))
            RestrictionEntry.TYPE_INTEGER, RestrictionEntry.TYPE_CHOICE ->
                put("defaultValue", JsonPrimitive(e.intValue))
            RestrictionEntry.TYPE_STRING ->
                put("defaultValue", e.selectedString?.let { JsonPrimitive(it) } ?: JsonNull)
            RestrictionEntry.TYPE_MULTI_SELECT ->
                put("defaultValue", buildJsonArray {
                    e.allSelectedStrings?.forEach { add(JsonPrimitive(it)) }
                })
            RestrictionEntry.TYPE_BUNDLE, RestrictionEntry.TYPE_BUNDLE_ARRAY -> {
                val nested = e.restrictions?.toList() ?: emptyList()
                put("restrictions", buildJsonArray { nested.forEach { add(entryToJson(it)) } })
            }
        }

        if (e.type == RestrictionEntry.TYPE_CHOICE || e.type == RestrictionEntry.TYPE_MULTI_SELECT) {
            e.choiceEntries?.let { labels ->
                put("entries", buildJsonArray { labels.forEach { add(JsonPrimitive(it)) } })
            }
            e.choiceValues?.let { values ->
                put("entryValues", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
            }
        }
    }

    private fun wireType(t: Int): String = when (t) {
        RestrictionEntry.TYPE_BOOLEAN -> "bool"
        RestrictionEntry.TYPE_CHOICE -> "choice"
        RestrictionEntry.TYPE_MULTI_SELECT -> "multi-select"
        RestrictionEntry.TYPE_INTEGER -> "integer"
        RestrictionEntry.TYPE_STRING -> "string"
        RestrictionEntry.TYPE_BUNDLE -> "bundle"
        RestrictionEntry.TYPE_BUNDLE_ARRAY -> "bundle_array"
        else -> "unknown"
    }

}
