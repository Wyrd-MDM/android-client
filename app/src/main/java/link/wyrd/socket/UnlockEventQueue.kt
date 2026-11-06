package link.wyrd.socket

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

object UnlockEventQueue {

    data class Entry(val success: Boolean, val at: Long)

    fun recordAndFlush(context: Context, success: Boolean) {
        append(context, success, System.currentTimeMillis() / 1000)
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SocketService::class.java)
                    .setAction(SocketService.ACTION_FLUSH_UNLOCKS),
            )
        }.onFailure { Log.w(TAG, "could not poke SocketService to flush unlock events", it) }
    }

    @Synchronized
    fun append(context: Context, success: Boolean, atEpochSec: Long) {
        val store = store(context)
        val arr = readArray(store)
        arr.put(JSONObject().put(KEY_SUCCESS, success).put(KEY_AT, atEpochSec))
        store.edit { putString(KEY_QUEUE, arr.toString()) }
    }

    @Synchronized
    fun drainAll(context: Context): List<Entry> {
        val store = store(context)
        val arr = readArray(store)
        if (arr.length() == 0) return emptyList()
        store.edit { remove(KEY_QUEUE) }
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Entry(o.optBoolean(KEY_SUCCESS, false), o.optLong(KEY_AT, 0L)))
        }
        return out
    }

    private fun readArray(store: SharedPreferences): JSONArray {
        val raw = store.getString(KEY_QUEUE, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun store(context: Context): SharedPreferences =
        context.applicationContext
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private const val TAG = "UnlockEventQueue"
    private const val PREFS_FILE = "wyrd_unlock_queue"
    private const val KEY_QUEUE = "queue"
    private const val KEY_SUCCESS = "success"
    private const val KEY_AT = "at"
}
