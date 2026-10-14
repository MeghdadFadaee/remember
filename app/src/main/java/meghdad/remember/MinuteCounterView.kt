package meghdad.remember

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/** Rounded numeral artwork sits behind the controls; its value is always measured minutes. */
class MinuteCounterView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; strokeWidth = 30f
    }
    private val digits = (0..9).map { MinuteNumeralArtwork.path(it) }
    private var elapsed = 0L
    private var saved = false
    private var floating = false
    private val drift = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 9000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            val phase = it.animatedValue as Float
            translationX = sin(phase) * width * .016f
            translationY = sin(phase + .8f) * height * .012f
            rotation = sin(phase) * .8f
        }
    }
    fun setFloating(enabled: Boolean) {
        floating = enabled
        if (enabled && isAttachedToWindow && !drift.isStarted) drift.start()
        else if (!enabled) {
            drift.cancel(); translationX = 0f; translationY = 0f; rotation = 0f
        }
    }
    fun update(time: Long, savedTotal: Boolean = false) {
        val numberChanged = elapsed / 60_000 != time / 60_000 || saved != savedTotal
        val secondsChanged = elapsed / 1000 != time / 1000 || saved != savedTotal
        elapsed = time.coerceAtLeast(0); saved = savedTotal
        if (secondsChanged || contentDescription == null) {
            contentDescription = if (saved) "${elapsed / 60_000} saved minutes" else "${elapsed / 60_000} minutes, ${elapsed / 1000 % 60} seconds"
        }
        if (numberChanged) invalidate()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); setFloating(floating) }
    override fun onDetachedFromWindow() { drift.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val numeral = (elapsed / 60_000).toString()
        val artworkWidth = numeral.length * 100f - 4f
        val scale = minOf(width * .67f / artworkWidth, height * .73f / 150f)
        canvas.save()
        canvas.translate((width - artworkWidth * scale) / 2f + width * .02f, (height - 150 * scale) / 2f - height * .025f)
        canvas.scale(scale, scale)
        paint.shader = LinearGradient(0f, 15f, 75f, 143f,
            intArrayOf(Color.rgb(145, 157, 126), Color.rgb(197, 211, 155), Color.rgb(204, 235, 40), Color.rgb(162, 203, 0)),
            floatArrayOf(0f, .3f, .5f, 1f), Shader.TileMode.CLAMP)
        numeral.forEach { character ->
            canvas.drawPath(digits[character.digitToInt()], paint)
            canvas.translate(100f, 0f)
        }
        paint.shader = null
        canvas.restore()
    }
}
