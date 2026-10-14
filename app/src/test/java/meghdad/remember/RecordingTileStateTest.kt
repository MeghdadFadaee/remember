package meghdad.remember

import org.junit.Assert.*
import org.junit.Test

class RecordingTileStateTest {
    @Test fun durationKeepsTotalMinutesBeyondOneHour() {
        assertEquals("00:00", RecordingTileState().time)
        assertEquals("01:02", RecordingTileState(elapsedMillis = 62_999).time)
        assertEquals("120:05", RecordingTileState(elapsedMillis = 7_205_000).time)
    }

    @Test fun commandsAreSingleUseEvenWhenHostRepeatsTheClickId() {
        val actions = RecordingTileActions()
        val token = actions.issue("clip", TileRecordingAction.MARK)
        assertEquals(TileRecordingAction.MARK, actions.consume(token, "clip"))
        assertNull(actions.consume(token, "clip"))
    }

    @Test fun staleCommandsCannotControlAnotherPartOrSession() {
        val actions = RecordingTileActions()
        val token = actions.issue("old", TileRecordingAction.SAVE)
        assertNull(actions.consume(token, "new"))
        assertNull(actions.consume(token, "old"))
        assertNull(actions.consume("untrusted", "new"))
    }

    @Test fun expiredAndIdleCommandsFailClosed() {
        val actions = RecordingTileActions(2)
        val expired = actions.issue("clip", TileRecordingAction.PAUSE)
        val idle = actions.issue("clip", TileRecordingAction.SAVE)
        val valid = actions.issue("clip", TileRecordingAction.RESUME)
        assertNull(actions.consume(expired, "clip"))
        assertNull(actions.consume(idle, null))
        assertEquals(TileRecordingAction.RESUME, actions.consume(valid, "clip"))
    }
}
