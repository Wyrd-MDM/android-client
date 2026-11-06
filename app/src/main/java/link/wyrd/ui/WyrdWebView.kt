package link.wyrd.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import link.wyrd.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class WyrdWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class State { READY, LINKING, LINKED }

    var linkState: State = State.READY
        set(value) {
            if (field != value) {
                field = value
                syncStateAnimators()
                invalidate()
            }
        }

    var progress: Float = 0f
        set(value) {
            val coerced = value.coerceIn(0f, 1f)
            if (field != coerced) {
                field = coerced
                invalidate()
            }
        }

    private val colMarkWeb = ContextCompat.getColor(context, R.color.wyrd_mark_web)
    private val colMarkDim = ContextCompat.getColor(context, R.color.wyrd_blue_dk)
    private val colGreen = ContextCompat.getColor(context, R.color.wyrd_green)
    private val colSteel = ContextCompat.getColor(context, R.color.wyrd_steel)
    private val colVoid = ContextCompat.getColor(context, R.color.wyrd_void)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var spinStartUptimeMs: Long = 0L
    private val spinTick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttachedToWindow) return
            invalidate()
            Choreographer.getInstance().postFrameCallbackDelayed(this, SPIN_TICK_MS)
        }
    }
    private val pulseAnim = ValueAnimator.ofFloat(0.5f, 1f).apply {
        duration = 1_200L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { invalidate() }
    }
    private val webPath = Path()
    private val ringPath = Path()
    private val nodeRects = Array(6) { RectF() }
    private val spokeEnds = FloatArray(12)

    private var cx = 0f
    private var cy = 0f
    private var rOuter = 0f
    private var rNode = 0f
    private var coreR = 0f
    private var nodeHalf = 0f
    private var strokeBase = 0f
    private var ready = false

    private var dashEffect: DashPathEffect? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spinStartUptimeMs = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(spinTick)
        syncStateAnimators()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(spinTick)
        pulseAnim.cancel()
        super.onDetachedFromWindow()
    }

    private fun syncStateAnimators() {
        if (!isAttachedToWindow) return
        if (linkState == State.LINKING) {
            if (!pulseAnim.isStarted) pulseAnim.start()
        } else pulseAnim.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) { ready = false; return }
        val size = minOf(w, h).toFloat()
        cx = w / 2f
        cy = h / 2f
        rOuter = size * 0.46f
        rNode = size * 0.30f
        coreR = size * 0.045f
        nodeHalf = size * 0.040f
        strokeBase = size * 0.01f

        webPath.reset()
        for (i in 0..5) {
            val a = (PI / 3.0 * i - PI / 2.0).toFloat()
            val nx = cx + cos(a) * rNode
            val ny = cy + sin(a) * rNode
            nodeRects[i].set(nx - nodeHalf, ny - nodeHalf, nx + nodeHalf, ny + nodeHalf)
            spokeEnds[i * 2] = cx + cos(a) * rOuter
            spokeEnds[i * 2 + 1] = cy + sin(a) * rOuter
            if (i == 0) webPath.moveTo(nx, ny) else webPath.lineTo(nx, ny)
        }
        webPath.close()

        ringPath.reset()
        ringPath.addCircle(cx, cy, rOuter * 1.06f, Path.Direction.CW)

        dashEffect = DashPathEffect(floatArrayOf(size * 0.012f, size * 0.03f), 0f)
        ready = true
    }

    override fun onDraw(canvas: Canvas) {
        if (!ready) return
        val linked = linkState == State.LINKED

        val elapsed = SystemClock.uptimeMillis() - spinStartUptimeMs
        val ringAngle = (elapsed % RING_PERIOD_MS) / RING_PERIOD_MS.toFloat() * 360f
        canvas.save()
        canvas.rotate(ringAngle, cx, cy)
        strokePaint.pathEffect = dashEffect
        strokePaint.color = colMarkDim
        strokePaint.alpha = 90
        strokePaint.strokeWidth = strokeBase * 0.6f
        canvas.drawPath(ringPath, strokePaint)
        canvas.restore()
        strokePaint.pathEffect = null

        strokePaint.color = colMarkWeb
        strokePaint.alpha = if (linked) 255 else 165
        strokePaint.strokeWidth = strokeBase
        canvas.drawCircle(cx, cy, rOuter, strokePaint)
        strokePaint.strokeWidth = strokeBase * 0.7f
        for (i in 0..5) {
            canvas.drawLine(cx, cy, spokeEnds[i * 2], spokeEnds[i * 2 + 1], strokePaint)
        }
        strokePaint.strokeWidth = strokeBase * 0.8f
        canvas.drawPath(webPath, strokePaint)

        val litNodes = when (linkState) {
            State.LINKED -> 6
            State.LINKING -> floor(progress * 6f).toInt().coerceIn(0, 6)
            else -> 0
        }
        val rad = nodeHalf * 0.35f
        for (i in 0..5) {
            val r = nodeRects[i]
            if (i < litNodes) {
                fillPaint.color = colGreen
                canvas.drawRoundRect(r, rad, rad, fillPaint)
            } else {
                fillPaint.color = colVoid
                canvas.drawRoundRect(r, rad, rad, fillPaint)
                strokePaint.color = colSteel
                strokePaint.alpha = 255
                strokePaint.strokeWidth = strokeBase * 0.5f
                canvas.drawRoundRect(r, rad, rad, strokePaint)
            }
        }

        val corePulse = if (linkState == State.LINKING) pulseAnim.animatedValue as Float else 1f
        fillPaint.color = if (litNodes > 0) colGreen else colSteel
        fillPaint.alpha = (corePulse * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, if (linked) coreR else coreR * 0.8f, fillPaint)
        fillPaint.alpha = 255
    }

    companion object {
        private const val SPIN_TICK_MS = 33L
        private const val RING_PERIOD_MS = 18_000L
    }
}
