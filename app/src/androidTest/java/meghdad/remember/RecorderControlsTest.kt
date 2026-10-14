package meghdad.remember

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecorderControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun bounds(view: View) = IntArray(2).let { point ->
        view.getLocationOnScreen(point); Rect(point[0], point[1], point[0] + view.width, point[1] + view.height)
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 6000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue("Recorder controls did not reach expected state", condition())
    }
    @Test fun controlsFitRoundScreensAndKeepThePrimaryButtonFixed() {
        instrumentation.runOnMainSync {
            for (size in listOf(192, 227)) {
                val configured = context.createConfigurationContext(Configuration(context.resources.configuration).apply { screenWidthDp = size })
                val density = configured.resources.displayMetrics.density
                val pixels = (size * density).toInt()
                val parent = FrameLayout(configured).apply { setPadding((22 * density).toInt(), (16 * density).toInt(), (22 * density).toInt(), 0) }
                val controls = RecorderControlsView(configured, {}, {}, {})
                parent.addView(controls, FrameLayout.LayoutParams(-1, -2))
                var primaryBounds: Rect? = null
                for ((active, paused) in listOf(false to false, true to false, true to true, false to false)) {
                    controls.bind(active, paused, 1, animate = false)
                    parent.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY))
                    parent.layout(0, 0, pixels, pixels)
                    val buttons = descendants(controls).filter { it.isClickable && it.isEnabled }
                    assertEquals(if (active) 3 else 1, buttons.size)
                    buttons.forEach { button ->
                        val rect = Rect(0, 0, button.width, button.height)
                        parent.offsetDescendantRectToMyCoords(button, rect)
                        assertTrue("Tap area must be at least 48 dp", rect.width() >= 48 * density && rect.height() >= 48 * density)
                        val radius = rect.height() / 2.0
                        for (x in listOf(rect.left + radius, rect.right - radius)) {
                            assertTrue("Control clipped on $size dp: $rect", kotlin.math.hypot(x - pixels / 2.0, rect.exactCenterY() - pixels / 2.0) + radius <= pixels / 2.0 + 1)
                        }
                        if (button !is ImageButton) {
                            primaryBounds?.let { assertEquals(it, rect) }; primaryBounds = rect
                        } else {
                            val menu = Rect((pixels - 48 * density).toInt() / 2, (pixels - 48 * density).toInt(), (pixels + 48 * density).toInt() / 2, pixels)
                            assertFalse("Floating menu must not cover the icon controls", Rect.intersects(rect, menu))
                        }
                    }
                }
            }
        }
    }

    @Test fun actualAppKeepsItsViewsAndRecordsThroughFloatingIconControls() {
        assertNull("Use a disposable emulator without an active recording", RecordingService.activeId)
        val store = Recordings(context)
        val existing = store.list().map { it.id }.toSet()
        val preferences = RecordingPreferences(context)
        val previous = preferences.read()
        preferences.write(RecordingLimit())
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun control(label: String): View? {
            var found: View? = null
            instrumentation.runOnMainSync { found = descendants(activity.window.decorView).firstOrNull { it.contentDescription == label && it.isShown } }
            return found
        }
        fun click(label: String) {
            await { control(label) != null }
            val button = control(label)!!
            instrumentation.runOnMainSync { assertTrue(button.performClick()) }
        }
        fun screenshot(name: String) {
            // The numeral deliberately keeps floating; wait only for the finite control transition.
            SystemClock.sleep(400)
            instrumentation.waitForIdleSync()
            val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            val primary = control("Start recording")!!
            val originalBounds = bounds(primary)
            val numeral = descendants(activity.window.decorView).filterIsInstance<MinuteCounterView>().single()
            screenshot("home-controls-idle.png")
            click("Start recording")
            await { RecordingService.elapsed > 1200 && control("Pause") != null }
            assertSame(primary, control("Save"))
            assertEquals(originalBounds, bounds(control("Save")!!))
            assertSame(numeral, descendants(activity.window.decorView).filterIsInstance<MinuteCounterView>().single())
            assertTrue(control("Pause") is ImageButton)
            assertTrue(control("Mark") is ImageButton)
            val id = RecordingService.activeId!!
            screenshot("home-controls-recording.png")
            click("Pause"); await { RecordingService.paused && control("Resume") != null }
            val elapsed = RecordingService.elapsed
            SystemClock.sleep(500); assertEquals(elapsed, RecordingService.elapsed)
            click("Mark"); await { RecordingService.marks.size == 1 }
            SystemClock.sleep(2200) // Keep the transient bookmark toast out of the screenshot.
            screenshot("home-controls-paused.png")
            click("Resume"); await { !RecordingService.paused && RecordingService.elapsed > elapsed + 300 }
            click("Save"); await { RecordingService.activeId == null && control("Start recording") != null }
            assertSame(primary, control("Start recording"))
            assertEquals(originalBounds, bounds(primary))
            val saved = store.list().first { it.id == id }
            assertEquals(listOf(elapsed), saved.marks)
            MediaMetadataRetriever().also { reader ->
                try { reader.setDataSource(store.audio(id).absolutePath); assertTrue(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 1000) }
                finally { reader.release() }
            }
        } finally {
            if (RecordingService.activeId != null) {
                instrumentation.runOnMainSync { context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.SAVE)) }
                await { RecordingService.activeId == null }
            }
            store.list().filter { it.id !in existing }.forEach { store.delete(it) }
            preferences.write(previous)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
