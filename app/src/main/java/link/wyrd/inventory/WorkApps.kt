package link.wyrd.inventory

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WorkApps(private val context: Context) {

    data class App(
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val system: Boolean,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("packageName", packageName)
            put("label", label)
            put("versionName", versionName)
            put("versionCode", versionCode)
            put("system", system)
        }
    }

    fun list(): List<App> {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(0),
        ).mapTo(HashSet()) { it.activityInfo.packageName }
        return pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)).mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val system = isSystem(ai)
            if (system && pi.packageName !in launchable) return@mapNotNull null
            App(
                packageName = pi.packageName,
                label = ai.loadLabel(pm).toString(),
                versionName = pi.versionName ?: "",
                versionCode = pi.longVersionCode,
                system = system,
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun json(): JsonArray = JsonArray(list().map { it.toJson() })

    private fun isSystem(ai: ApplicationInfo): Boolean =
        ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}
