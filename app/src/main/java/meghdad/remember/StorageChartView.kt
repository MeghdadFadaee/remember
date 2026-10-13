package meghdad.remember

import android.content.Context
import android.graphics.*
import android.text.format.Formatter
import android.view.View
import kotlin.math.*

/** A proportional device-storage ring. Tiny app slices get a marker, never an inflated wedge. */
class StorageChartView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var usage: StorageUsage? = null
    private var failed = false
    private val lime = Color.rgb(199, 234, 24)
    fun update(value: StorageUsage) {
        usage = value; failed = false
        val available = Formatter.formatShortFileSize(context, value.available)
        val total = Formatter.formatShortFileSize(context, value.total)
        val app = Formatter.formatShortFileSize(context, value.appUsed)
        contentDescription = "${percent(value)} percent available. $available available out of $total. Remember uses ${if (value.estimated) "approximately " else ""}$app. Gray is other used storage."
        invalidate()
    }
    fun showFailure() { usage = null; failed = true; contentDescription = "Storage unavailable. Tap Refresh."; invalidate() }
    private fun percent(value: StorageUsage) = (value.available.toDouble() / value.total.coerceAtLeast(1) * 100).roundToInt().coerceIn(0, 100)
    init { contentDescription = "Loading device storage" }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        val radius = size * .405f
        val thickness = size * .085f
        val ring = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = thickness; paint.strokeCap = Paint.Cap.BUTT
        paint.color = Color.rgb(101, 114, 81)
        canvas.drawOval(ring, paint)
        val data = usage
        if (data != null && data.total > 0) {
            val free = (data.available.toDouble() / data.total).coerceIn(0.0, 1.0)
            val app = (data.appUsed.toDouble() / data.total).coerceIn(0.0, 1.0 - free)
            paint.color = lime
            canvas.drawArc(ring, -90f, (free * 360).toFloat(), false, paint)
            if (app > 0) {
                paint.color = Color.WHITE
                canvas.drawArc(ring, (-90 + free * 360).toFloat(), (app * 360).toFloat(), false, paint)
                // The dot makes even a few megabytes discoverable without distorting the proportions.
                val angle = Math.toRadians(-90 + (free + app / 2) * 360)
                val markerRadius = radius + thickness / 2 + size * .031f
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx + cos(angle).toFloat() * markerRadius, cy + sin(angle).toFloat() * markerRadius, size * .012f, paint)
            }
        }
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = size * .083f; paint.color = Color.rgb(169, 183, 145)
        val total = data?.let { "${Formatter.formatShortFileSize(context, it.total)} total" } ?: if (failed) "Unavailable" else "Loading"
        canvas.drawText(total, cx, cy - size * .2f, paint)
        paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        paint.textSize = size * .285f; paint.color = Color.WHITE
        canvas.drawText(data?.let { "${percent(it)}%" } ?: "—", cx, cy + size * .08f, paint)
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = size * .085f; paint.color = lime
        canvas.drawText("FREE", cx, cy + size * .245f, paint)
    }
}
