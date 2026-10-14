package meghdad.remember

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a fresh disposable watch emulator with microphone permission still ungranted. */
@RunWith(AndroidJUnit4::class)
class RecordingTilePermissionTest {
    @Test fun tileRequestsMicrophonePermissionAndDenialNeverStartsRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        assertNull(RecordingService.activeId)
        assumeTrue("Run the permission flow before other recording tests on a fresh disposable watch",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_DENIED)
        val activity = context.packageManager.getActivityInfo(ComponentName(context, TileRecordingActivity::class.java), 0)
        assertEquals("com.google.android.wearable.permission.BIND_TILE_PROVIDER", activity.permission)
        fun shell(command: String) = automation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }
        fun nodes(root: AccessibilityNodeInfo? = automation.rootInActiveWindow): List<AccessibilityNodeInfo> =
            if (root == null) emptyList() else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
        fun await(condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 10_000
            while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
            assertTrue("Permission flow did not reach the expected state", condition())
        }
        fun record() = nodes().firstOrNull { it.contentDescription?.toString() == "Record" && it.isClickable }
        fun permissionButton(id: String): AccessibilityNodeInfo? {
            nodes().firstOrNull { it.viewIdResourceName?.endsWith(":id/$id") == true }?.let { return it }
            // Wear OS 7 renders permission choices with Compose instead of resource IDs.
            val labels = if (id == "permission_deny_button") listOf("Don’t allow", "Don't allow")
                else listOf("While using app", "While using the app")
            var node = nodes().firstOrNull { it.packageName == "com.google.android.permissioncontroller" && it.text?.toString() in labels }
            while (node != null && !node.isClickable) node = node.parent
            return node
        }
        val store = Recordings(context)
        val existing = store.list().map { it.id }.toSet()
        try {
            shell("am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile --ecn component meghdad.remember/meghdad.remember.RecordingTileService")
            shell("am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation show-tile --ei index 0")
            await { record() != null }
            assertTrue(record()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await { permissionButton("permission_deny_button") != null }
            assertNull(RecordingService.activeId)
            assertTrue(permissionButton("permission_deny_button")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await { record() != null }
            assertNull(RecordingService.activeId)
            assertTrue(record()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await { permissionButton("permission_allow_foreground_only_button") != null }
            assertNull(RecordingService.activeId)
            assertTrue(permissionButton("permission_allow_foreground_only_button")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await { RecordingService.activeId != null && RecordingService.elapsed > 1200 }
            // Returning to the carousel must happen automatically, without opening MainActivity.
            await { nodes().any { it.contentDescription?.toString() == "Pause" && it.isClickable } }
        } finally {
            if (RecordingService.activeId != null) {
                instrumentation.runOnMainSync { context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.SAVE)) }
                await { RecordingService.activeId == null }
            }
            store.list().filter { it.id !in existing }.forEach { store.delete(it) }
        }
    }
}
