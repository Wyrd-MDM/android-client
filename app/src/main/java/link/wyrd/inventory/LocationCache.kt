package link.wyrd.inventory

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import link.wyrd.prefs.WyrdPrefs

class LocationCache(context: Context) {

    private val appCtx = context.applicationContext

    private val ce: SharedPreferences? =
        if (appCtx.getSystemService(UserManager::class.java)?.isUserUnlocked != false) {
            appCtx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        } else {
            null
        }

    fun save(location: JsonObject) {
        if (!WyrdPrefs.get(appCtx).shareLocation) return
        ce?.edit { putString(KEY_LOCATION, location.toString()) }
    }

    fun read(): JsonObject? {
        val text = ce?.getString(KEY_LOCATION, null) ?: return null
        return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    fun clear() {
        ce?.edit { remove(KEY_LOCATION) }
    }

    private companion object {
        const val FILE = "wyrd_loc_cache"
        const val KEY_LOCATION = "last_location_json"
    }
}
