package meghdad.remember

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.*
import kotlin.math.roundToInt

/** Stable home controls: only labels and the small satellite controls transition. */
internal class RecorderControlsView(context: Context, onPrimary: (Boolean) -> Unit,
                                    onPause: () -> Unit, onMark: () -> Unit) : LinearLayout(context) {
    private fun dp(value: Int) = WatchDesign.dp(context, value)
    private val easing = PathInterpolator(.4f, 0f, .2f, 1f)
    private val heading = WatchDesign.label(context, "", 10f)
    private var recording = false
    private var paused = false
    private var boundPart = 0
    private var initialized = false
    private val primary = WatchDesign.action(context, "", action = { onPrimary(recording) }).apply {
        setTextColor(Color.rgb(228, 237, 206))
        background = WatchDesign.shape(context, Color.argb(190, 24, 31, 16), 28, Color.argb(140, 199, 234, 24))
        layoutParams = LayoutParams(-1, dp(48))
    }
    private val pauseButton = satellite(context.getString(R.string.tile_pause), WatchIcon.PAUSE, onPause)
    private val markButton = satellite(context.getString(R.string.tile_mark), WatchIcon.MARK, onMark)
    private val satellites = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(dp((resources.configuration.screenWidthDp * .77f).roundToInt()), dp(48))
        addView(pauseButton, LayoutParams(dp(48), dp(48)))
        addView(Space(context), LayoutParams(0, 1, 1f))
        addView(markButton, LayoutParams(dp(48), dp(48)))
        visibility = INVISIBLE
    }

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(heading, LayoutParams(-1, -2))
        addView(Space(context), LayoutParams(1, dp(if (resources.configuration.screenWidthDp <= 200) 38 else 62)))
        addView(primary)
        addView(Space(context), LayoutParams(1, dp(8)))
        addView(satellites)
    }

    fun bind(active: Boolean, isPaused: Boolean, part: Int, animate: Boolean) {
        val motion = animate && ValueAnimator.areAnimatorsEnabled()
        if (initialized && active == recording && isPaused == paused && part == boundPart) {
            if (!motion) finishTransitions()
            return
        }
        val changed = initialized && active != recording
        val pauseChanged = initialized && isPaused != paused
        recording = active; paused = isPaused; boundPart = part
        val title = if (!active) context.getString(R.string.tile_brand)
            else if (isPaused) context.getString(R.string.tile_paused)
            else if (part == 1) context.getString(R.string.tile_recording)
            else context.getString(R.string.recorder_recording_part, part)
        heading.setTextColor(if (active) WatchDesign.lime else Color.WHITE)
        heading.letterSpacing = if (active) 0f else .2f
        fadeText(heading, title, motion)
        fadeText(primary, context.getString(if (active) R.string.tile_save_button else R.string.tile_record_button), motion)
        primary.contentDescription = context.getString(if (active) R.string.tile_save else R.string.recorder_start)
        if (!initialized || changed) {
            satellites.animate().cancel()
            satellites.importantForAccessibility = if (active) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            pauseButton.isEnabled = active; markButton.isEnabled = active
            if (motion) {
                if (active) {
                    satellites.visibility = VISIBLE; satellites.alpha = 0f; satellites.translationY = dp(8).toFloat()
                    satellites.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(easing).start()
                } else satellites.animate().alpha(0f).translationY(dp(6).toFloat()).setDuration(160)
                    .setInterpolator(easing).withEndAction { if (!recording) satellites.visibility = INVISIBLE }.start()
            } else {
                satellites.visibility = if (active) VISIBLE else INVISIBLE
                satellites.alpha = if (active) 1f else 0f; satellites.translationY = 0f
            }
        }
        if (!initialized || pauseChanged || changed) {
            pauseButton.animate().cancel()
            pauseButton.setImageDrawable(WatchGlyph(if (isPaused) WatchIcon.PLAY else WatchIcon.PAUSE, Color.rgb(228, 237, 206)))
            pauseButton.contentDescription = context.getString(if (isPaused) R.string.tile_resume else R.string.tile_pause)
            if (motion && active && pauseChanged) {
                pauseButton.alpha = .35f
                pauseButton.animate().alpha(1f).setDuration(180).setInterpolator(easing).start()
            } else pauseButton.alpha = 1f
        }
        if (!motion) finishTransitions()
        initialized = true
    }

    private fun fadeText(view: TextView, value: String, motion: Boolean) {
        if (view.tag == value) return
        view.tag = value; view.animate().cancel()
        if (motion && initialized) view.animate().alpha(0f).setDuration(80).withEndAction {
            view.text = value
            view.animate().alpha(1f).setDuration(180).setInterpolator(easing).start()
        }.start() else { view.text = value; view.alpha = 1f }
    }

    fun finishTransitions() {
        listOf(heading, primary).forEach { view ->
            view.animate().cancel()
            (view.tag as? String)?.let { if (view.text.toString() != it) view.text = it }
            view.alpha = 1f
        }
        satellites.animate().cancel(); pauseButton.animate().cancel()
        satellites.visibility = if (recording) VISIBLE else INVISIBLE
        satellites.alpha = if (recording) 1f else 0f; satellites.translationY = 0f; pauseButton.alpha = 1f
    }

    private fun satellite(label: String, icon: WatchIcon, action: () -> Unit) = ImageButton(context).apply {
        contentDescription = label; setImageDrawable(WatchGlyph(icon, Color.rgb(228, 237, 206)))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(13), dp(13), dp(13), dp(13))
        val surface = InsetDrawable(WatchDesign.shape(context, Color.argb(229, 24, 31, 16), 18, 0x547d8b58), dp(6))
        val mask = InsetDrawable(WatchDesign.shape(context, Color.WHITE, 18), dp(6))
        background = RippleDrawable(ColorStateList.valueOf(0x3cc7ea18), surface, mask)
        stateListAnimator = null
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CONFIRM); action() }
    }

    override fun onDetachedFromWindow() { finishTransitions(); super.onDetachedFromWindow() }
}
