package meghdad.remember

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingsTest {
    @Test fun clipsPersistRenameSortAndDeleteWithoutTouchingOtherAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val isolated = object : ContextWrapper(context) { override fun getFilesDir() = folder }
            val store = Recordings(isolated)
            val old = Clip("first", "A thought", 1L, 60000L, listOf(1000L, 45000L))
            val recent = Clip("second", "Street sounds", 2L, 10000L, emptyList())
            store.audio(old.id).writeText("test audio")
            store.audio(recent.id).writeText("test audio")
            store.save(old); store.save(recent)
            assertEquals(listOf(recent, old), Recordings(isolated).list())
            store.save(old.copy(title = "Renamed idea"))
            assertEquals("Renamed idea", store.list().last().title)
            assertEquals(old.marks, store.list().last().marks)
            store.delete(old)
            assertFalse(store.audio(old.id).exists())
            assertEquals(listOf(recent), store.list())
            File(folder, "recordings/broken.json").writeText("invalid")
            assertEquals(listOf(recent), store.list())
        } finally { folder.deleteRecursively() }
    }
}
