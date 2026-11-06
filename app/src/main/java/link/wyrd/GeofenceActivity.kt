package link.wyrd

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.LocationManager
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import link.wyrd.geo.GeoZone
import link.wyrd.geo.Geofence
import link.wyrd.net.onServer
import link.wyrd.prefs.WyrdPrefs
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

class GeofenceActivity : AppCompatActivity() {

    private var geofence: Geofence? = null
    private var position: DoubleArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gf = Geofence.current(this)
        if (gf == null) {
            finish()
            return
        }
        geofence = gf
        position = lastKnownPosition()

        val base = gf.tilesBase?.takeIf { onServer(it, WyrdPrefs.get(this).serverUrl) }
        if (base == null) renderSchematic() else renderMap(base, gf)
    }

    private fun renderMap(base: String, gf: Geofence) {
        val web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        web.setBackgroundColor(color(R.color.wyrd_void))
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = true

            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) renderSchematic()
            }
        }
        val holder = android.widget.FrameLayout(this).apply { setBackgroundColor(color(R.color.wyrd_void)) }
        holder.addView(web, android.widget.FrameLayout.LayoutParams(MP, MP))
        ViewCompat.setOnApplyWindowInsetsListener(holder) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(holder)
        web.loadUrl("${base.trimEnd('/')}/device-geomap.html#d=${encodeData(gf)}")
    }

    private fun encodeData(gf: Geofence): String {
        val obj = buildJsonObject {
            gf.tilesToken?.let { put("token", it) }
            position?.let { p -> put("pos", buildJsonArray { add(p[0]); add(p[1]) }) }
            put("zones", buildJsonArray {
                for (z in gf.zones) addJsonObject {
                    put("name", z.name)
                    put("kind", z.kind)
                    put("action", z.action)
                    put("polygon", buildJsonArray {
                        for (v in z.polygon) add(buildJsonArray { add(v[0]); add(v[1]) })
                    })
                }
            })
        }
        return Base64.encodeToString(obj.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun renderSchematic() {
        val gf = geofence ?: return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.wyrd_void))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(16), bottom = bars.bottom + dp(16))
            insets
        }
        root.addView(title(getString(R.string.geofence_title)))
        root.addView(caption(getString(R.string.geofence_intro)))
        root.addView(
            GeofenceMapView(this, gf.zones, position),
            LinearLayout.LayoutParams(MP, dp(340)).apply { topMargin = dp(14) },
        )
        if (position == null) root.addView(caption(getString(R.string.geofence_no_fix)))
        for (z in gf.zones) root.addView(zoneRow(z))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun lastKnownPosition(): DoubleArray? {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
        var best: android.location.Location? = null
        for (p in providers) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.time > best!!.time) best = l
        }
        return best?.let { doubleArrayOf(it.latitude, it.longitude) }
    }

    private fun zoneRow(z: GeoZone): View {
        val kind = getString(if (z.kind == "keep_out") R.string.geofence_keep_out else R.string.geofence_keep_in)
        val action = getString(if (z.action == "wipe") R.string.geofence_action_wipe else R.string.geofence_action_lock)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(dotView(GeofenceMapView.zoneColor(z.kind)))
            addView(TextView(this@GeofenceActivity).apply {
                text = "${z.name} · $kind · $action"
                setTextColor(color(R.color.wyrd_fog))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(WC, WC).apply { marginStart = dp(8) }
            })
        }
    }

    private fun dotView(c: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(c)
        }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.wyrd_fog))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.wyrd_ash))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        layoutParams = LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(6) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private companion object {
        private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

@SuppressLint("ViewConstructor")
class GeofenceMapView(
    context: Context,
    private val zones: List<GeoZone>,
    private val position: DoubleArray?,
) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = POSITION_COLOR }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); color = (POSITION_COLOR and 0x00FFFFFF) or 0x55000000
    }

    private val lats = mutableListOf<Double>()
    private val lngs = mutableListOf<Double>()

    init {
        for (z in zones) for (v in z.polygon) { lats += v[0]; lngs += v[1] }
        position?.let { lats += it[0]; lngs += it[1] }
    }

    override fun onDraw(canvas: Canvas) {
        if (lats.isEmpty()) return
        val pad = dp(16f)
        val minLat = lats.min(); val maxLat = lats.max()
        val minLng = lngs.min(); val maxLng = lngs.max()
        val midLatRad = Math.toRadians((minLat + maxLat) / 2)
        val lngScale = max(cos(midLatRad), 0.01)

        val spanX = max((maxLng - minLng) * lngScale, 1e-6)
        val spanY = max(maxLat - minLat, 1e-6)
        val w = width - 2 * pad; val h = height - 2 * pad
        val scale = min(w / spanX, h / spanY).toFloat()
        val drawnW = (spanX * scale).toFloat()
        val drawnH = (spanY * scale).toFloat()
        val offX = pad + (w - drawnW) / 2f
        val offY = pad + (h - drawnH) / 2f

        fun x(lng: Double): Float = offX + ((lng - minLng) * lngScale).toFloat() * scale
        fun y(lat: Double): Float = offY + (maxLat - lat).toFloat() * scale

        for (z in zones) {
            if (z.polygon.size < 3) continue
            val path = Path()
            z.polygon.forEachIndexed { i, v ->
                val px = x(v[1]); val py = y(v[0])
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            val c = zoneColor(z.kind)
            fill.color = (c and 0x00FFFFFF) or FILL_ALPHA
            line.color = c
            canvas.drawPath(path, fill)
            canvas.drawPath(path, line)
        }
        position?.let {
            val px = x(it[1]); val py = y(it[0])
            canvas.drawCircle(px, py, dp(6f), dot)
            canvas.drawCircle(px, py, dp(10f), dotRing)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private const val KEEP_IN_COLOR = 0xFF3A7EBB.toInt()
        private const val KEEP_OUT_COLOR = 0xFFD64152.toInt()
        private const val POSITION_COLOR = 0xFF86B86A.toInt()
        private const val FILL_ALPHA = 0x26000000

        fun zoneColor(kind: String): Int = if (kind == "keep_out") KEEP_OUT_COLOR else KEEP_IN_COLOR
    }
}
