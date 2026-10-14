package meghdad.remember

import android.content.Intent
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.assertEquals
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    /** Exercise the real activity stack, the fixed menu shortcut, and scrollable grid tiles. */
    @Test fun oneMenuOpensEverySectionAndBackReturnsWithoutNestedMenus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun revealMenu(open: Boolean = false) {
            // Run the gesture and assertion together: an idle-waiting test runner must not
            // wait out the very timeout this control is meant to have.
            instrumentation.runOnMainSync {
                val current = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .single { it.window.decorView.hasWindowFocus() }
                val root = current.window.decorView
                val now = android.os.SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                    MotionEvent.obtain(now, now, action, root.width * .5f, root.height * .2f, 0).also {
                        it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        current.dispatchTouchEvent(it); it.recycle()
                    }
                }
                val button = descendants(root).filterIsInstance<Button>().single { it.contentDescription == "Open all sections" }
                assertEquals(View.VISIBLE, button.visibility)
                if (open) button.performClick()
            }
        }
        fun screenshot(name: String) {
            if (InstrumentationRegistry.getArguments().getString("saveDesignScreenshots") != "true") return
            onView(isRoot()).check(matches(isDisplayed()))
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(350)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        try {
            screenshot("home")
            revealMenu(open = true)
            screenshot("menu")
            listOf("Moments", "Storage", "Recording limit", "Backup", "Server", "Transfers", "Server recordings", "Automatic backup", "Advanced").forEach { destination ->
                android.util.Log.i("RememberNavigation", "Opening $destination")
                onView(withContentDescription(destination)).perform(scrollTo())
                instrumentation.waitForIdleSync()
                android.os.SystemClock.sleep(250)
                onView(withContentDescription(destination)).perform(click())
                val expected = if (destination in listOf("Moments", "Storage", "Recording limit")) MainActivity::class.java else BackupActivity::class.java
                var ready = false
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                    instrumentation.runOnMainSync {
                        ready = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .any { expected.isInstance(it) && it.window.decorView.hasWindowFocus() }
                    }
                    if (!ready) android.os.SystemClock.sleep(50)
                }
                assertTrue("Section must be foreground before testing its touch menu", ready)
                revealMenu()
                screenshot(when (destination) {
                    "Recording limit" -> "recording-limit"
                    "Backup" -> "backup-overview"
                    "Server" -> "backup-server"
                    "Automatic backup" -> "backup-automatic"
                    else -> destination.lowercase().replace(' ', '-')
                })
                // Both ways out must return to the same shared grid across activity boundaries.
                if (destination in listOf("Storage", "Backup", "Automatic backup")) {
                    revealMenu(open = true)
                } else pressBack()
                instrumentation.waitForIdleSync()
                android.os.SystemClock.sleep(350)
                onView(withContentDescription("Recorder")).check(matches(isEnabled()))
            }
            onView(withContentDescription("Recorder")).perform(scrollTo(), click())
            onView(withContentDescription("Start recording")).check(matches(isDisplayed()))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
