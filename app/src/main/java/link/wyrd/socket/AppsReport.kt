package link.wyrd.socket

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.wyrd.inventory.WorkApps
import java.util.concurrent.LinkedBlockingQueue

internal class AppsReport(context: Context) {

    private val apps = WorkApps(context)
    private var lastSent: JsonArray? = null

    @Synchronized
    fun send(outgoing: LinkedBlockingQueue<Frame.ClientFrame>) {
        val list = runCatching { apps.json() }
            .getOrElse { Log.w(TAG, "could not list the apps", it); return }
        if (list == lastSent) return
        val data = buildJsonObject {
            put("ts", System.currentTimeMillis() / 1000)
            put("apps", list)
        }
        if (outgoing.offer(Frame.ClientFrame.Telemetry(data))) lastSent = list
    }

    private companion object {
        const val TAG = "AppsReport"
    }
}
