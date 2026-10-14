package meghdad.remember

import android.Manifest
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run only on a disposable Wear OS emulator; exercises the actual system tile host. */
@RunWith(AndroidJUnit4::class)
class RecordingTileTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue("Tile or recorder did not reach expected state", condition())
    }
    private fun nodes(root: AccessibilityNodeInfo? = automation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (root == null) emptyList() else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun control(label: String) = nodes().firstOrNull { it.contentDescription?.toString() == label && it.isClickable }
    private fun click(label: String) {
        await { control(label) != null }
        assertTrue(control(label)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun clock() = nodes().mapNotNull { node ->
        Regex("[0-9]+:[0-9]{2}").find(node.contentDescription?.toString().orEmpty())?.value
    }.firstOrNull()
    private fun screenshot(name: String) {
        automation.waitForIdle(400, 5000)
        val bitmap = automation.takeScreenshot() ?: error("No screenshot")
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun minuteArtworkAdvancesInTheActualCarousel() {
        assertNull("Use a disposable emulator without an active recording", RecordingService.activeId)
        val preferences = RecordingPreferences(context)
        val previous = preferences.read()
        val store = Recordings(context)
        val existing = store.list().map { it.id }.toSet()
        try {
            preferences.write(RecordingLimit())
            automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
            shell("am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile --ecn component meghdad.remember/meghdad.remember.RecordingTileService")
            shell("am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation show-tile --ei index 0")
            click("Record")
            await { RecordingService.activeId != null && control("Pause") != null }
            assertTrue(nodes().any { it.stateDescription?.toString() == "0 minutes" })
            val end = SystemClock.elapsedRealtime() + 70_000
            while (RecordingService.elapsed < 61_000 && SystemClock.elapsedRealtime() < end) SystemClock.sleep(200)
            assertTrue(RecordingService.elapsed >= 61_000)
            await { nodes().any { it.stateDescription?.toString() == "1 minute" } }
            screenshot("tile-one-minute.png")
            click("Save")
            await { RecordingService.activeId == null }
        } finally {
            if (RecordingService.activeId != null) {
                instrumentation.runOnMainSync { context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.SAVE)) }
                await { RecordingService.activeId == null }
            }
            preferences.write(previous)
            store.list().filter { it.id !in existing }.forEach { store.delete(it) }
        }
    }
    @Test fun carouselStartsAndControlsRecordingOnlyAfterAnExplicitTap() {
        assertNull("Use a disposable emulator without an active recording", RecordingService.activeId)
        val preferences = RecordingPreferences(context)
        val previous = preferences.read()
        val store = Recordings(context)
        val existing = store.list().map { it.id }.toSet()
        try {
            preferences.write(RecordingLimit())
            automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
            assertTrue(shell("am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile --ecn component meghdad.remember/meghdad.remember.RecordingTileService").contains("result=1"))
            shell("am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation show-tile --ei index 0")
            await { control("Record") != null }
            assertNull(RecordingService.activeId)
            screenshot("tile-idle.png")
            click("Record")
            await { RecordingService.activeId != null && RecordingService.elapsed > 1200 }
            val id = RecordingService.activeId!!
            shell("am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation show-tile --ei index 0")
            await { control("Pause") != null && clock() != null }
            val initial = clock()
            SystemClock.sleep(2200)
            assertNotEquals("The host should advance the timer without another app request", initial, clock())
            screenshot("tile-recording.png")
            click("Pause")
            await { RecordingService.paused && control("Resume") != null }
            val pausedTime = RecordingService.elapsed
            val pausedClock = clock()
            SystemClock.sleep(1300)
            assertEquals(pausedTime, RecordingService.elapsed)
            assertEquals(pausedClock, clock())
            click("Mark")
            await { RecordingService.marks.size == 1 }
            assertEquals(listOf(pausedTime), RecordingService.marks)
            screenshot("tile-paused.png")
            // An already-paused command must not toggle back into capture.
            instrumentation.runOnMainSync { RecordingService.tileCommand(id, TileRecordingAction.PAUSE) }
            assertTrue(RecordingService.paused)
            click("Resume")
            await { !RecordingService.paused && RecordingService.elapsed > pausedTime + 300 }
            shell("input keyevent 223") // Screen off must not end recording.
            val beforeSleep = RecordingService.elapsed
            SystemClock.sleep(1200)
            assertTrue(RecordingService.elapsed > beforeSleep + 800)
            shell("input keyevent 224")
            shell("am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation show-tile --ei index 0")
            click("Save")
            await { RecordingService.activeId == null && control("Record") != null }
            val saved = store.list().first { it.id == id }
            assertEquals(listOf(pausedTime), saved.marks)
            MediaMetadataRetriever().also { retriever ->
                try {
                    retriever.setDataSource(store.audio(id).absolutePath)
                    assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 1000)
                } finally { retriever.release() }
            }
            // A queued command for the previous clip must not save the next one.
            click("Record")
            await { RecordingService.activeId != null && RecordingService.elapsed > 1200 }
            val nextId = RecordingService.activeId
            instrumentation.runOnMainSync { RecordingService.tileCommand(id, TileRecordingAction.SAVE) }
            assertEquals(nextId, RecordingService.activeId)
        } finally {
            if (RecordingService.activeId != null) {
                instrumentation.runOnMainSync { context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.SAVE)) }
                await { RecordingService.activeId == null }
            }
            preferences.write(previous)
            store.list().filter { it.id !in existing }.forEach { store.delete(it) }
        }
    }
}
