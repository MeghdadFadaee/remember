package meghdad.remember

import android.animation.ValueAnimator
import android.content.Context
import android.os.PowerManager
import androidx.wear.protolayout.ModifiersBuilders.*
import androidx.wear.protolayout.expression.AnimationParameterBuilders.*

/** Short, host-owned interaction transitions; no loops, timers, or animation wake locks. */
@androidx.annotation.OptIn(markerClass = [androidx.wear.protolayout.expression.ProtoLayoutExperimental::class])
internal object RecordingTileMotion {
    fun allowed(context: Context): Boolean = ValueAnimator.areAnimatorsEnabled() &&
        !context.getSystemService(PowerManager::class.java).isPowerSaveMode

    fun transition(rise: Boolean = false): AnimatedVisibility {
        val enterSpec = spec(if (rise) 280 else 180, if (rise) 30 else 0)
        val enter = EnterTransition.Builder().setFadeIn(FadeInTransition.Builder()
            .setInitialAlpha(0f).setAnimationSpec(enterSpec).build())
        if (rise) enter.setSlideIn(SlideInTransition.Builder().setDirection(SLIDE_DIRECTION_BOTTOM_TO_TOP)
            .setInitialSlideBound(SlideParentBound.Builder().setSnapTo(SLIDE_PARENT_SNAP_TO_INSIDE).build())
            .setAnimationSpec(enterSpec).build())
        return AnimatedVisibility.Builder().setEnterTransition(enter.build())
            .setExitTransition(ExitTransition.Builder().setFadeOut(FadeOutTransition.Builder()
                .setTargetAlpha(0f).setAnimationSpec(spec(90)).build()).build()).build()
    }

    private fun spec(duration: Long, delay: Long = 0) = AnimationSpec.Builder()
        .setAnimationParameters(AnimationParameters.Builder().setDurationMillis(duration).setDelayMillis(delay)
            .setEasing(Easing.FAST_OUT_SLOW_IN_EASING).build()).build()
}
