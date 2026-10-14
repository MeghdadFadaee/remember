package meghdad.remember

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.tiles.renderer.TileRenderer
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RecordingTileLayoutTest {
    @Test fun controlsAndLargeMinuteValuesFitSmallAndLargeRoundViewports() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (size in listOf(192, 227)) {
            val base = instrumentation.targetContext
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { screenWidthDp = size })
            var primaryBounds: android.graphics.Rect? = null
            for (state in listOf(RecordingTileState(), RecordingTileState("clip", false, 59_000, 0, 1), RecordingTileState("clip", true, 7_205_000, 12, 3), RecordingTileState("clip", true, 86_400_000, 0, 1))) {
                lateinit var parent: FrameLayout
                lateinit var future: ListenableFuture<View>
                instrumentation.runOnMainSync {
                    parent = FrameLayout(context)
                    // Renderer resources live in the test APK, while the layout uses app strings.
                    val renderer = TileRenderer(instrumentation.context, context.mainExecutor) { }
                    val layout = LayoutElementBuilders.Layout.Builder()
                        .setRoot(RecordingTileLayout(context, RecordingTileActions(), animateTransitions = false).build(state)).build()
                    future = renderer.inflateAsync(layout, RecordingTileArtwork.resources, parent)
                }
                val view = future.get(10, TimeUnit.SECONDS)
                instrumentation.runOnMainSync {
                    val pixels = (size * context.resources.displayMetrics.density).toInt()
                    parent.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY))
                    parent.layout(0, 0, pixels, pixels)
                    fun descendants(node: View): List<View> = listOf(node) + if (node is ViewGroup) (0 until node.childCount).flatMap { descendants(node.getChildAt(it)) } else emptyList()
                    val all = descendants(view)
                    val controls = all.filter { it.isClickable }
                    assertEquals(if (state.active) 4 else 2, controls.size)
                    controls.forEach { control ->
                        val bounds = android.graphics.Rect(0, 0, control.width, control.height)
                        parent.offsetDescendantRectToMyCoords(control, bounds)
                        assertTrue("Control outside $size dp viewport: $bounds", bounds.left >= 0 && bounds.top >= 0 && bounds.right <= pixels && bounds.bottom <= pixels)
                        val minimum = (48 * context.resources.displayMetrics.density).toInt()
                        assertTrue("Tap target too small", bounds.width() >= minimum && bounds.height() >= minimum)
                        assertFalse("Missing spoken description", control.contentDescription.isNullOrEmpty())
                        if (control.contentDescription.toString() in listOf("Record", "Save")) {
                            primaryBounds?.let { assertEquals("Record and Save must occupy the same fixed position", it, bounds) }
                            primaryBounds = android.graphics.Rect(bounds)
                        }
                        if (control.contentDescription.toString() in listOf("Resume", "Mark", "Save", "Record")) {
                            // Buttons are capsules: both rounded ends must fit inside the watch circle.
                            val radius = bounds.height() / 2.0
                            for (x in listOf(bounds.left + radius, bounds.right - radius)) {
                                val distance = kotlin.math.hypot(x - pixels / 2.0, bounds.exactCenterY() - pixels / 2.0)
                                assertTrue("Button clipped by round screen at $size dp: $bounds", distance + radius <= pixels / 2.0 + 1)
                            }
                        }
                    }
                    val texts = all.filterIsInstance<TextView>()
                    // The live host clock can cross a second/minute boundary while inflating.
                    val descriptions = controls.map { it.contentDescription?.toString().orEmpty() }
                    assertTrue("Missing spoken timer: $descriptions", descriptions.any {
                        if (state.active && !state.paused) Regex("[0-9]+:[0-9]{2}").containsMatchIn(it)
                        else it.contains(state.time)
                    })
                    val images = all.filterIsInstance<ImageView>()
                    assertEquals(1 + (state.elapsedMillis / 60_000).toString().length + if (state.active) 2 else 0, images.size)
                    assertTrue("Original numeral artwork must render", images.all { it.drawable != null })
                    assertFalse("No caption or duplicate digital timer below the numeral", texts.any { it.text.toString().contains(":") })
                    assertFalse("Secondary controls must be icon-only", texts.any { it.text.toString() in listOf("Pause", "Resume", "Mark") })
                    texts.forEach { text ->
                        assertTrue("Text is clipped: ${text.text}", text.layout != null && (0 until text.layout.lineCount).all { text.layout.getEllipsisCount(it) == 0 })
                    }
                }
            }
        }
    }
}
