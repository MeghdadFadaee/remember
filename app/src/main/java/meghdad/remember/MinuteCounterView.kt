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
    private val digits = (0..9).map { digitPath(it) }
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
    private fun digitPath(digit: Int) = Path().apply {
        when (digit) {
            0 -> addRoundRect(RectF(23f, 25f, 73f, 125f), 25f, 25f, Path.Direction.CW)
            1 -> { moveTo(31f, 43f); lineTo(58f, 25f); lineTo(58f, 125f) }
            2 -> { moveTo(23f, 41f); cubicTo(27f, 14f, 73f, 17f, 73f, 47f); cubicTo(73f, 65f, 47f, 82f, 24f, 112f); quadTo(19f, 125f, 32f, 125f); lineTo(75f, 125f) }
            3 -> { moveTo(24f, 29f); cubicTo(77f, 7f, 90f, 72f, 48f, 73f); cubicTo(94f, 75f, 76f, 142f, 23f, 121f) }
            4 -> { moveTo(52f, 25f); lineTo(22f, 84f); quadTo(17f, 92f, 29f, 92f); lineTo(77f, 92f); moveTo(66f, 25f); lineTo(66f, 125f) }
            5 -> { moveTo(73f, 25f); lineTo(26f, 25f); lineTo(23f, 70f); cubicTo(84f, 45f, 96f, 141f, 24f, 121f) }
            6 -> { moveTo(68f, 26f); cubicTo(21f, 14f, 15f, 72f, 24f, 106f); cubicTo(35f, 145f, 86f, 125f, 72f, 89f); cubicTo(62f, 65f, 28f, 73f, 22f, 92f) }
            7 -> { moveTo(22f, 25f); lineTo(76f, 25f); lineTo(31f, 125f) }
            8 -> {
                addRoundRect(RectF(25f, 24f, 71f, 74f), 23f, 23f, Path.Direction.CW)
                addRoundRect(RectF(22f, 74f, 74f, 126f), 26f, 26f, Path.Direction.CW)
            }
            9 -> { moveTo(27f, 124f); cubicTo(75f, 139f, 84f, 79f, 72f, 44f); cubicTo(57f, 6f, 10f, 28f, 25f, 64f); cubicTo(36f, 87f, 69f, 77f, 75f, 61f) }
        }
    }
}
