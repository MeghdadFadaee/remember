package meghdad.remember

import android.content.Intent
import android.widget.Button
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @Test fun homeLaunchesWithRecordAndSharedMenuControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            instrumentation.runOnMainSync {
                fun descendants(view: View): List<View> = listOf(view) +
                    if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                val buttons = descendants(activity.window.decorView).filterIsInstance<Button>()
                assertTrue(buttons.any { it.contentDescription == "Start recording" && it.isEnabled })
                assertTrue(buttons.any { it.contentDescription == "Open all sections" && it.isEnabled })
                assertFalse(buttons.any { it.text.startsWith("Moments") })
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
