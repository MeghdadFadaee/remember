package meghdad.remember

import android.Manifest
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class RecordingFlowTest {
    @Test fun recordingPausesMarksAndSavesPlayableAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var id: String? = null
        fun command(action: String) = instrumentation.runOnMainSync {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            if (action == RecordingService.START) context.startForegroundService(intent) else context.startService(intent)
        }
        fun awaitState(condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 5000
            while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
            assertTrue("Recorder did not reach expected state", condition())
        }
        try {
            command(RecordingService.START)
            awaitState { RecordingService.activeId != null && RecordingService.elapsed > 1100 }
            id = RecordingService.activeId
            command(RecordingService.PAUSE)
            awaitState { RecordingService.paused }
            val duration = RecordingService.elapsed
            SystemClock.sleep(350)
            assertEquals(duration, RecordingService.elapsed)
            command(RecordingService.MARK)
            awaitState { RecordingService.marks.size == 1 }
            command(RecordingService.PAUSE)
            awaitState { !RecordingService.paused && RecordingService.elapsed > duration + 300 }
            command(RecordingService.SAVE)
            awaitState { RecordingService.activeId == null }
            val store = Recordings(context)
            val saved = store.list().first { it.id == id }
            assertEquals(listOf(duration), saved.marks)
            assertTrue(saved.duration > 1400)
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(store.audio(saved.id).absolutePath)
                assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 1000)
            } finally { metadata.release() }
        } finally {
            if (RecordingService.activeId != null) { command(RecordingService.SAVE); awaitState { RecordingService.activeId == null } }
            Recordings(context).list().firstOrNull { it.id == id }?.let { Recordings(context).delete(it) }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
