package meghdad.remember

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingLimitsIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun awaitState(timeout: Long = 5000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue("Recorder did not reach expected state", condition())
    }
    private fun command(action: String) = instrumentation.runOnMainSync {
        val intent = Intent(context, RecordingService::class.java).setAction(action)
        if (action == RecordingService.START) context.startForegroundService(intent) else context.startService(intent)
    }
    private fun checkPlayable(clip: Clip) {
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(Recordings(context).audio(clip.id).absolutePath)
            assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 1000)
        } finally { metadata.release() }
    }
    private fun session(action: LimitAction, work: (Activity, String) -> Unit) {
        val preferences = RecordingPreferences(context)
        val previous = preferences.read()
        val store = Recordings(context)
        val existing = store.list().map { it.id }.toSet()
        var activity: Activity? = null
        try {
            preferences.write(RecordingLimit(true, 1, action))
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            command(RecordingService.START)
            awaitState { RecordingService.activeId != null && RecordingService.elapsed > 1100 }
            work(activity, RecordingService.activeId!!)
        } finally {
            if (RecordingService.activeId != null) {
                command(RecordingService.SAVE)
                awaitState { RecordingService.activeId == null }
            }
            preferences.write(previous)
            store.list().filter { it.id !in existing }.forEach { store.delete(it) }
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
        }
    }
    @Test fun minuteLimitExcludesPauseAndSavesAfterLeavingTheApp() = session(LimitAction.STOP) { activity, id ->
        command(RecordingService.PAUSE)
        awaitState { RecordingService.paused }
        val elapsed = RecordingService.elapsed
        SystemClock.sleep(1700)
        assertEquals(elapsed, RecordingService.elapsed)
        command(RecordingService.PAUSE)
        awaitState { !RecordingService.paused }
        instrumentation.runOnMainSync { activity.finish() }
        awaitState(70_000) { RecordingService.activeId == null }
        val clip = Recordings(context).list().first { it.id == id }
        assertTrue("Expected one minute of captured audio", clip.duration in 60_000..62_000)
        checkPlayable(clip)
    }
    @Test fun limitStartsNewPlayableClipAtZeroAndKeepsSessionSettings() = session(LimitAction.NEXT_CLIP) { activity, firstId ->
        command(RecordingService.MARK)
        awaitState { RecordingService.marks.size == 1 }
        // Changes are for the next session, so this session must still roll over.
        RecordingPreferences(context).write(RecordingLimit(false, 15, LimitAction.STOP))
        instrumentation.runOnMainSync { activity.finish() }
        awaitState(70_000) { RecordingService.activeId != null && RecordingService.activeId != firstId }
        val secondId = RecordingService.activeId!!
        assertEquals(2, RecordingService.part)
        assertTrue(RecordingService.elapsed < 2000)
        assertTrue(RecordingService.marks.isEmpty())
        val first = Recordings(context).list().first { it.id == firstId }
        assertTrue(first.duration in 60_000..62_000)
        assertEquals(1, first.marks.size)
        assertTrue(first.title.endsWith("Part 1"))
        checkPlayable(first)
        awaitState { RecordingService.elapsed > 1300 }
        command(RecordingService.SAVE)
        awaitState { RecordingService.activeId == null }
        val second = Recordings(context).list().first { it.id == secondId }
        assertTrue(second.duration in 1300..5000)
        assertTrue(second.title.endsWith("Part 2"))
        checkPlayable(second)
    }
}
