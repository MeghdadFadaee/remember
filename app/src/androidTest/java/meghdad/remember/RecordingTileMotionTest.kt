package meghdad.remember

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.protolayout.LayoutElementBuilders.*
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ProtoLayoutScope
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.expression.VersionBuilders.VersionInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [androidx.wear.protolayout.expression.ProtoLayoutExperimental::class])
class RecordingTileMotionTest {
    @Test fun nativeStartActionUsesAnImmutableActivityIntentWithAnOlderHostFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (minor in listOf(500, 600)) {
            val scope = ProtoLayoutScope(VersionInfo.Builder().setMajor(1).setMinor(minor).build())
            val root = RecordingTileLayout(context, RecordingTileActions(), animateTransitions = false, scope = scope)
                .build(RecordingTileState())
            val start = descendants(root).mapNotNull { modifiers(it)?.clickable }.first { it.id == "start" }
            @Suppress("DEPRECATION")
            val pending = scope.collectPendingIntents().getParcelable<android.app.PendingIntent>("start")
            if (minor == 600) {
                assertNotNull(pending)
                assertTrue(pending!!.isActivity)
                assertTrue(pending.isImmutable)
            } else {
                assertNull(pending)
                assertTrue(start.onClick is ActionBuilders.LaunchAction)
            }
        }
    }

    private fun descendants(element: LayoutElement): List<LayoutElement> = listOf(element) + when (element) {
        is Box -> element.contents.flatMap(::descendants)
        is Column -> element.contents.flatMap(::descendants)
        is Row -> element.contents.flatMap(::descendants)
        else -> emptyList()
    }
    private fun modifiers(element: LayoutElement): Modifiers? = when (element) {
        is Box -> element.modifiers
        is Column -> element.modifiers
        is Row -> element.modifiers
        is Text -> element.modifiers
        is Image -> element.modifiers
        else -> null
    }
    @Test fun reducedMotionOmitsAllTransitionsAndKeepsControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (state in listOf(RecordingTileState(), RecordingTileState("clip", false), RecordingTileState("clip", true))) {
            val root = RecordingTileLayout(context, RecordingTileActions(), animateTransitions = false).build(state)
            val all = descendants(root)
            assertTrue(all.none { modifiers(it)?.contentUpdateAnimation != null })
            val labels = all.mapNotNull { modifiers(it)?.semantics?.contentDescription?.value }
            assertTrue(if (state.active) labels.containsAll(listOf("Save", "Mark", if (state.paused) "Resume" else "Pause")) else "Record" in labels)
        }
    }
    @Test fun transitionsAreFiniteAndDoNotAnimateTheWholeTileOrTimer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = RecordingTileLayout(context, RecordingTileActions(), animateTransitions = true)
            .build(RecordingTileState("clip", false, 9000))
        assertNull(modifiers(root)?.contentUpdateAnimation)
        val all = descendants(root)
        val timer = all.first { modifiers(it)?.clickable?.id == "refresh" }
        assertTrue("The numeral must stay visible throughout control transitions",
            descendants(timer).none { modifiers(it)?.contentUpdateAnimation != null })
        assertFalse("Avoid the renderer's pressed-state flash over the numeral", modifiers(timer)!!.clickable!!.isVisualFeedbackEnabled)
        val transitions = all.mapNotNull { modifiers(it)?.contentUpdateAnimation }
        assertTrue(transitions.any { it.enterTransition?.slideIn != null })
        val specs = transitions.flatMap { transition -> listOfNotNull(
            transition.enterTransition?.fadeIn?.animationSpec,
            transition.enterTransition?.slideIn?.animationSpec,
            transition.exitTransition?.fadeOut?.animationSpec,
        ) }
        assertTrue(specs.isNotEmpty())
        specs.forEach { spec ->
            assertNull("Interaction transitions must never loop", spec.repeatable)
            assertTrue("Keep transitions brief", spec.animationParameters!!.durationMillis in 75..320)
        }
    }
}
