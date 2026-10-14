package meghdad.remember

import java.util.Locale
import java.util.UUID

internal data class RecordingTileState(
    val clipId: String? = null,
    val paused: Boolean = false,
    val elapsedMillis: Long = 0,
    val bookmarks: Int = 0,
    val part: Int = 1,
) {
    val active: Boolean get() = clipId != null
    val time: String get() = String.format(Locale.ROOT, "%02d:%02d", elapsedMillis / 60_000, elapsedMillis / 1000 % 60)
}

internal enum class TileRecordingAction { PAUSE, RESUME, MARK, SAVE }

/** Opaque, one-shot commands. A cached tile can never operate on a different clip. */
internal class RecordingTileActions(private val capacity: Int = 128) {
    private val issued = LinkedHashMap<String, Pair<String, TileRecordingAction>>()

    @Synchronized fun issue(clipId: String, action: TileRecordingAction): String {
        val token = UUID.randomUUID().toString()
        issued[token] = clipId to action
        while (issued.size > capacity) issued.remove(issued.keys.first())
        return token
    }

    @Synchronized fun consume(token: String, currentClipId: String?): TileRecordingAction? {
        val (clipId, action) = issued.remove(token) ?: return null
        return action.takeIf { clipId == currentClipId }
    }
}
