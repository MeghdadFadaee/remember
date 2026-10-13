package meghdad.remember

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Clip(val id: String, val title: String, val created: Long, val duration: Long, val marks: List<Long>)

class Recordings(private val context: Context) {
    private val directory = File(context.filesDir, "recordings").apply { mkdirs() }
    fun audio(id: String) = File(directory, "$id.m4a")
    fun pending(id: String) = File(directory, "$id.pending")
    fun list(): List<Clip> = directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
        runCatching {
            val json = JSONObject(file.readText())
            val marks = json.optJSONArray("marks") ?: JSONArray()
            Clip(file.nameWithoutExtension, json.getString("title"), json.getLong("created"), json.getLong("duration"),
                (0 until marks.length()).map { marks.getLong(it) })
        }.getOrNull()
    }.filter { audio(it.id).exists() }.sortedByDescending { it.created }
    fun save(clip: Clip) {
        val json = JSONObject().put("title", clip.title).put("created", clip.created)
            .put("duration", clip.duration).put("marks", JSONArray(clip.marks))
        val temp = File(directory, "${clip.id}.tmp")
        temp.writeText(json.toString())
        check(temp.renameTo(File(directory, "${clip.id}.json"))) { "Could not save recording details" }
    }
    fun delete(clip: Clip) {
        check(!audio(clip.id).exists() || audio(clip.id).delete()) { "Could not delete audio" }
        File(directory, "${clip.id}.json").delete()
    }
    // A process interruption can leave a playable audio file without its final metadata.
    fun recover() {
        directory.listFiles().orEmpty().filter { it.extension == "pending" || (it.extension == "m4a" && !File(directory, "${it.nameWithoutExtension}.json").exists()) }.forEach { file ->
            if (file.nameWithoutExtension == RecordingService.activeId) return@forEach
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (duration > 0 && (file.extension == "m4a" || file.renameTo(audio(file.nameWithoutExtension)))) {
                    save(Clip(file.nameWithoutExtension, "Recovered moment", file.lastModified(), duration, emptyList()))
                }
            } catch (_: Exception) { /* Keep interrupted files for a future recovery attempt. */ }
            finally { retriever.release() }
        }
    }
}

fun timeLabel(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(java.util.Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}
