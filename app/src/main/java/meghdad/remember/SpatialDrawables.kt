package meghdad.remember

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.*

enum class DialMode { READY, RECORDING, PAUSED, PLAYBACK, LIBRARY }

/** Time and edge instruments share one fixed dial behind the scrolling controls. */
class SpatialDialDrawable : Drawable() {
    var mode = DialMode.READY
    var elapsed = 0L
    var duration = 0L
    var level = 0f
    var marks: List<Long> = emptyList()
    var freeStorage = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lime = Color.rgb(199, 234, 24)
    private val track = Color.rgb(61, 73, 42)
    private fun line(canvas: Canvas, ring: RectF, angle: Float, sweep: Float, color: Int, width: Float) {
        paint.shader = null; paint.color = color; paint.style = Paint.Style.STROKE
        paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(ring, angle, sweep, false, paint)
        paint.style = Paint.Style.FILL
    }
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat(); val size = minOf(w, h)
        val cx = w / 2; val cy = h / 2
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.drawColor(Color.rgb(13, 17, 11))
        paint.shader = RadialGradient(w * .65f, h * .3f, size * .8f,
            intArrayOf(Color.rgb(48, 57, 35), Color.rgb(22, 29, 18), Color.rgb(13, 17, 11)),
            floatArrayOf(0f, .65f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
        val seconds = (elapsed.coerceAtLeast(0) % 60_000) / 60_000f
        val face = RectF(cx - size * .49f, cy - size * .49f, cx + size * .49f, cy + size * .49f)
        // One half of the dial fills in 60 seconds, then starts again at the next minute.
        paint.color = Color.argb(32, 156, 174, 105)
        canvas.drawArc(face, -90f, 180f * seconds, true, paint)
        if (mode == DialMode.LIBRARY) { canvas.restore(); return }
        val radius = size * .46f
        val ring = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val stroke = size * .014f
        // Left: the real microphone peak, or remaining storage before capture.
        for (i in 0..11) {
            val lit = when (mode) {
                DialMode.READY -> i < (freeStorage * 12).roundToInt()
                DialMode.RECORDING -> i < (level * 12).roundToInt()
                DialMode.PLAYBACK -> duration > 0 && i < (12 * (1f - elapsed.toFloat() / duration).coerceIn(0f, 1f)).roundToInt()
                else -> false
            }
            line(canvas, ring, 211f - i * 5.3f, 2.4f, if (lit) lime else track, stroke)
        }
        // Right: bookmark locations; in playback the arc also shows total progress.
        line(canvas, ring, -32f, 64f, track, stroke)
        if (mode == DialMode.PLAYBACK && duration > 0) {
            line(canvas, ring, -32f, 64f * (elapsed.toFloat() / duration).coerceIn(0f, 1f), lime, stroke)
        }
        val span = if (mode == DialMode.PLAYBACK) duration else elapsed
        marks.forEach { mark ->
            val angle = Math.toRadians((-32 + 64 * (mark.toFloat() / span.coerceAtLeast(1)).coerceIn(0f, 1f)).toDouble())
            val x = cx + cos(angle).toFloat() * radius
            val y = cy + sin(angle).toFloat() * radius
            paint.color = Color.WHITE; paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, stroke * .85f, paint)
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.OPAQUE
}
