package meghdad.remember

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.NumberPicker
import android.widget.Switch
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTest {
    @Test fun settingsNavigationPersistsCustomLengthModeAndUnlimitedToggle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = RecordingPreferences(context)
        val previous = preferences.read()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                    (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                fun button(prefix: String) = descendants(activity.window.decorView).filterIsInstance<Button>().first { it.text.startsWith(prefix) }
                val now = android.os.SystemClock.uptimeMillis()
                listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                    android.view.MotionEvent.obtain(now, now, action, activity.window.decorView.width / 2f,
                        activity.window.decorView.height * .12f, 0).also { event -> activity.dispatchTouchEvent(event); event.recycle() }
                }
                descendants(activity.window.decorView).filterIsInstance<Button>().first { it.contentDescription == "Open all sections" }.performClick()
                button("Recording limit").performClick()
                descendants(activity.window.decorView).filterIsInstance<Switch>().single().isChecked = true
                button("Length").performClick()
                descendants(activity.window.decorView).filterIsInstance<NumberPicker>().single().value = 137
                button("Save").performClick()
                descendants(activity.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text == "At the limit" }.let { (it.parent.parent as View).performClick() }
                button("Save & start next").performClick()
                button("Back").performClick()
                assertEquals(RecordingLimit(true, 137, LimitAction.NEXT_CLIP), preferences.read())
                descendants(activity.window.decorView).filterIsInstance<Switch>().single().isChecked = false
                assertEquals(RecordingLimit(false, 137, LimitAction.NEXT_CLIP), RecordingPreferences(context).read())
            }
        } finally {
            preferences.write(previous)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    @Test fun deviceAndAppStorageAreReportedWithoutUsagePermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val usage = readStorageUsage(context)
        assertTrue(usage.total > 0)
        assertTrue(usage.available in 0..usage.total)
        assertTrue(usage.appUsed >= java.io.File(context.applicationInfo.sourceDir).length())
        assertTrue(usage.recordings >= 0)
        assertTrue(usage.appUsed >= usage.recordings)
    }
}
