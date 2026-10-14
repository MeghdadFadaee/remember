package meghdad.remember

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MenuVisibilityTest {
    @Test fun bottomMenuStartsHiddenRevealsWithoutOpeningAndWaitsUntilTouchEndsToHide() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun send(activity: Activity, action: Int, x: Float, y: Float, downTime: Long) = instrumentation.runOnMainSync {
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).also {
                it.source = InputDevice.SOURCE_TOUCHSCREEN
                activity.dispatchTouchEvent(it); it.recycle()
            }
        }
        for (type in listOf(MainActivity::class.java, BackupActivity::class.java)) {
            val activity = instrumentation.startActivitySync(Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                onView(isRoot()).check(matches(isDisplayed()))
                lateinit var menu: Button
                var x = 0f; var y = 0f
                instrumentation.runOnMainSync {
                    val root = activity.window.decorView
                    menu = descendants(root).filterIsInstance<Button>().single { it.contentDescription == "Open all sections" }
                    val location = IntArray(2); menu.getLocationInWindow(location)
                    assertEquals(root.width / 2, location[0] + menu.width / 2)
                    assertEquals(root.height, location[1] + menu.height)
                    assertEquals(root.height, descendants(root).filterIsInstance<ScrollView>().single().height)
                    assertEquals(View.INVISIBLE, menu.visibility)
                    x = location[0] + menu.width / 2f; y = location[1] + menu.height / 2f
                }
                val down = SystemClock.uptimeMillis()
                // The reveal tap must reach the old content target, never the newly shown menu.
                send(activity, MotionEvent.ACTION_DOWN, x, y, down)
                SystemClock.sleep(3300)
                instrumentation.runOnMainSync {
                    assertEquals("Do not hide under a held finger", View.VISIBLE, menu.visibility)
                    assertFalse(descendants(activity.window.decorView).filterIsInstance<Button>().any { it.contentDescription == "Recorder" })
                }
                send(activity, MotionEvent.ACTION_UP, x, y, down)
                instrumentation.runOnMainSync {
                    assertTrue("Reveal must not open the menu", menu.isAttachedToWindow)
                    assertFalse(descendants(activity.window.decorView).filterIsInstance<Button>().any { it.contentDescription == "Recorder" })
                }
                SystemClock.sleep(2000)
                instrumentation.runOnMainSync { assertEquals("Leave time to tap the revealed menu", View.VISIBLE, menu.visibility) }
                var hidden = false
                val deadline = SystemClock.elapsedRealtime() + 2500
                while (!hidden && SystemClock.elapsedRealtime() < deadline) {
                    instrumentation.runOnMainSync { hidden = menu.visibility == View.INVISIBLE }
                    if (!hidden) SystemClock.sleep(50)
                }
                assertTrue("Menu must hide after idle time", hidden)
                // Ordinary content touches reveal it too, without swallowing that gesture.
                val tap = SystemClock.uptimeMillis()
                send(activity, MotionEvent.ACTION_DOWN, activity.window.decorView.width / 2f, activity.window.decorView.height * .12f, tap)
                send(activity, MotionEvent.ACTION_UP, activity.window.decorView.width / 2f, activity.window.decorView.height * .12f, tap)
                instrumentation.runOnMainSync { assertEquals(View.VISIBLE, menu.visibility) }
                if (type == MainActivity::class.java && InstrumentationRegistry.getArguments().getString("saveDesignScreenshots") == "true") {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(100)
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(context.cacheDir, "home-menu-visible.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
                if (type == MainActivity::class.java) {
                    val open = SystemClock.uptimeMillis()
                    send(activity, MotionEvent.ACTION_DOWN, x, y, open)
                    send(activity, MotionEvent.ACTION_UP, x, y, open)
                    instrumentation.runOnMainSync {
                        assertTrue("A second tap opens the visible menu", descendants(activity.window.decorView).filterIsInstance<Button>().any { it.contentDescription == "Recorder" })
                    }
                }
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }
}
