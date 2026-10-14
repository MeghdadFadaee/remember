package meghdad.remember

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.*
import kotlin.math.*

/** Shared watch controls: native focus and spoken labels, with original vector artwork. */
object WatchDesign {
    val ink = Color.rgb(13, 17, 11)
    val lime = Color.rgb(199, 234, 24)
    val muted = Color.rgb(164, 175, 143)
    val surface = Color.rgb(30, 39, 25)
    fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value).roundToInt()
    fun switchTint() = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(lime, muted))
    fun shape(context: Context, color: Int = surface, radius: Int = 22, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
        stroke?.let { setStroke(dp(context, 1), it) }
    }
    fun touch(context: Context, color: Int = surface, radius: Int = 22) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(60, 199, 234, 24)), shape(context, color, radius), shape(context, Color.WHITE, radius))
    fun label(context: Context, value: String, size: Float = 12f, color: Int = Color.WHITE) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    fun heading(body: LinearLayout, title: String, eyebrow: String) {
        body.addView(label(body.context, eyebrow.uppercase(), 9f, lime).apply { letterSpacing = .16f })
        body.addView(label(body.context, title, 21f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(context, 4), 0, dp(context, 8))
        })
    }
    fun action(context: Context, label: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 12f; isAllCaps = false; setTextColor(if (primary) ink else Color.WHITE)
        background = touch(context, if (primary) lime else surface)
        minHeight = 0; minimumHeight = 0; stateListAnimator = null
        setPadding(dp(context, 8), 0, dp(context, 8), 0)
        layoutParams = LinearLayout.LayoutParams(-1, dp(context, 48)).apply { setMargins(0, dp(context, 4), 0, dp(context, 4)) }
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CONFIRM); action() }
    }
    fun pair(body: LinearLayout, first: Button, second: Button) {
        val row = LinearLayout(body.context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(first, LinearLayout.LayoutParams(0, dp(body.context, 48), 1f).apply { marginEnd = dp(body.context, 3) })
        row.addView(second, LinearLayout.LayoutParams(0, dp(body.context, 48), 1f).apply { marginStart = dp(body.context, 3) })
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(body.context, 6); bottomMargin = dp(body.context, 6) })
    }
    fun card(body: LinearLayout, title: String, subtitle: String, icon: WatchIcon, action: (() -> Unit)? = null): TextView {
        val context = body.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = touch(context); setPadding(dp(context, 12), dp(context, 12), dp(context, 10), dp(context, 12))
            minimumHeight = dp(context, 60)
        }
        val image = ImageView(context).apply { setImageDrawable(WatchGlyph(icon, lime)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        row.addView(image, LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)).apply { marginEnd = dp(context, 10) })
        val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(label(context, title, 13f).apply { gravity = Gravity.START; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        val detail = label(context, subtitle, 10f, muted).apply { gravity = Gravity.START; setPadding(0, dp(context, 4), 0, 0) }
        copy.addView(detail); row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) {
            row.contentDescription = "$title. $subtitle"
            image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            copy.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            row.isFocusable = true; row.setOnClickListener { row.performHapticFeedback(HapticFeedbackConstants.CONFIRM); action() }
            row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
                }
            }
        }
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 4); bottomMargin = dp(context, 4) })
        return detail
    }
    fun menuDock(context: Context, action: () -> Unit) = action(context, "", false, action).apply {
        background = touch(context, Color.TRANSPARENT, 24)
        setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
        val icon = WatchGlyph(WatchIcon.MENU, lime).apply { setBounds(0, 0, dp(context, 28), dp(context, 28)) }
        setCompoundDrawables(icon, null, null, null); compoundDrawablePadding = 0
        contentDescription = context.getString(R.string.open_menu)
    }
}

enum class WatchIcon { RECORD, MOMENTS, STORAGE, LIMIT, BACKUP, SERVER, TRANSFERS, REMOTE, AUTOMATIC, ADVANCED, MENU, PLAY }

/** Each destination has an identifiable silhouette, without fonts or bitmap assets. */
class WatchGlyph(private val icon: WatchIcon, private val tint: Int, private val orb: Boolean = false) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 48f, bounds.height() / 48f)
        if (orb) {
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(5f, 3f, 40f, 48f, Color.rgb(47, 61, 34), Color.rgb(21, 29, 17), Shader.TileMode.CLAMP)
            canvas.drawCircle(24f, 24f, 23f, paint); paint.shader = null
            paint.color = Color.argb(90, Color.red(tint), Color.green(tint), Color.blue(tint)); paint.style = Paint.Style.STROKE; paint.strokeWidth = .7f
            canvas.drawArc(RectF(1f, 1f, 47f, 47f), 210f, 130f, false, paint)
            canvas.translate(9f, 9f); canvas.scale(.625f, .625f)
        }
        paint.color = tint; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        fun line(x: Float, y: Float, xx: Float, yy: Float) = canvas.drawLine(x, y, xx, yy, paint)
        fun box(x: Float, y: Float, xx: Float, yy: Float, r: Float = 4f) = canvas.drawRoundRect(RectF(x, y, xx, yy), r, r, paint)
        fun arrow() { line(24f, 33f, 24f, 15f); line(18f, 21f, 24f, 15f); line(24f, 15f, 30f, 21f) }
        when (icon) {
            WatchIcon.RECORD -> { box(18f, 7f, 30f, 29f, 6f); canvas.drawArc(RectF(12f, 15f, 36f, 36f), 0f, 180f, false, paint); line(24f, 36f, 24f, 42f); line(18f, 42f, 30f, 42f) }
            WatchIcon.MOMENTS -> { line(10f, 20f, 10f, 28f); line(17f, 12f, 17f, 36f); line(24f, 6f, 24f, 42f); line(31f, 15f, 31f, 33f); line(38f, 20f, 38f, 28f) }
            WatchIcon.STORAGE -> { canvas.drawCircle(24f, 24f, 16f, paint); paint.strokeWidth = 6f; canvas.drawArc(RectF(8f, 8f, 40f, 40f), -90f, 235f, false, paint); paint.style = Paint.Style.FILL; canvas.drawCircle(24f, 24f, 3f, paint) }
            WatchIcon.LIMIT -> { canvas.drawCircle(24f, 26f, 16f, paint); line(24f, 26f, 24f, 16f); line(24f, 26f, 31f, 30f); line(20f, 5f, 28f, 5f); line(24f, 5f, 24f, 10f) }
            WatchIcon.BACKUP, WatchIcon.REMOTE -> {
                val path = Path().apply { moveTo(12f, 35f); cubicTo(-1f, 31f, 5f, 15f, 15f, 18f); cubicTo(17f, 3f, 37f, 5f, 37f, 20f); cubicTo(48f, 20f, 48f, 35f, 37f, 35f) }
                canvas.drawPath(path, paint)
                if (icon == WatchIcon.BACKUP) arrow() else { val play = Path().apply { moveTo(21f, 24f); lineTo(29f, 29f); lineTo(21f, 34f); close() }; canvas.drawPath(play, paint) }
            }
            WatchIcon.SERVER -> { box(8f, 7f, 40f, 22f); box(8f, 27f, 40f, 42f); line(14f, 14f, 15f, 14f); line(14f, 34f, 15f, 34f); line(27f, 14f, 34f, 14f); line(27f, 34f, 34f, 34f) }
            WatchIcon.TRANSFERS -> { line(15f, 37f, 15f, 10f); line(9f, 16f, 15f, 10f); line(15f, 10f, 21f, 16f); line(33f, 11f, 33f, 38f); line(27f, 32f, 33f, 38f); line(33f, 38f, 39f, 32f) }
            WatchIcon.AUTOMATIC -> { canvas.drawArc(RectF(7f, 7f, 41f, 41f), -60f, 290f, false, paint); line(35f, 5f, 40f, 12f); line(40f, 12f, 31f, 13f); line(24f, 14f, 24f, 24f); line(24f, 24f, 31f, 29f) }
            WatchIcon.ADVANCED -> { for (i in 0..2) { val y = 12f + i * 12; line(7f, y, 41f, y); box(if (i == 1) 26f else 12f, y - 4, if (i == 1) 32f else 18f, y + 4, 2f) } }
            WatchIcon.MENU -> { paint.style = Paint.Style.FILL; for (x in listOf(15f, 33f)) for (y in listOf(15f, 33f)) canvas.drawCircle(x, y, 5f, paint) }
            WatchIcon.PLAY -> { val path = Path().apply { moveTo(17f, 9f); lineTo(38f, 24f); lineTo(17f, 39f); close() }; canvas.drawPath(path, paint) }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A quiet, static dial: progress for backups, or tick marks for a configured duration. */
class StatusDialView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var value = "0"
    private var caption = ""
    private var fraction = 0f
    private val ring = RectF()
    fun update(number: String, label: String, progress: Float) {
        value = number; caption = label; fraction = progress.coerceIn(0f, 1f)
        contentDescription = "$number $label"; invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f; val r = size * .42f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = size * .035f; paint.strokeCap = Paint.Cap.ROUND; paint.color = WatchDesign.surface
        ring.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(ring, 135f, 270f, false, paint)
        paint.color = WatchDesign.lime
        if (fraction > 0) canvas.drawArc(ring, 135f, 270f * fraction, false, paint)
        paint.strokeWidth = size * .008f
        for (i in 0..24) {
            val angle = Math.toRadians(135.0 + i * 270.0 / 24)
            val outer = r - size * .05f; val inner = outer - if (i % 6 == 0) size * .05f else size * .024f
            paint.color = if (i / 24f <= fraction && fraction > 0) WatchDesign.lime else Color.rgb(67, 80, 54)
            canvas.drawLine(cx + cos(angle).toFloat() * inner, cy + sin(angle).toFloat() * inner, cx + cos(angle).toFloat() * outer, cy + sin(angle).toFloat() * outer, paint)
        }
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        paint.textSize = size * if (value.length > 3) .26f else .36f
        canvas.drawText(value, cx, cy + size * .09f, paint)
        paint.color = WatchDesign.muted; paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); paint.textSize = size * .075f
        val displayCaption = caption.uppercase()
        val captionWidth = paint.measureText(displayCaption)
        if (captionWidth > size * .68f) paint.textSize *= size * .68f / captionWidth
        canvas.drawText(displayCaption, cx, cy + size * .23f, paint)
    }
}
