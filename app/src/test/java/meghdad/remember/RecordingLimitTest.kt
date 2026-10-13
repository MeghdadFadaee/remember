package meghdad.remember

import org.junit.Assert.*
import org.junit.Test

class RecordingLimitTest {
    @Test fun unlimitedNeverStopsAtConfiguredLength() {
        assertNull(RecordingLimit(false, 1, LimitAction.STOP).actionAt(60_000))
        assertNull(RecordingLimit(false, 1, LimitAction.NEXT_CLIP).actionAt(Long.MAX_VALUE))
    }
    @Test fun exactBoundarySelectsTheConfiguredAction() {
        val stop = RecordingLimit(true, 15, LimitAction.STOP)
        assertNull(stop.actionAt(899_999))
        assertEquals(LimitAction.STOP, stop.actionAt(900_000))
        val next = RecordingLimit(true, 1, LimitAction.NEXT_CLIP)
        assertNull(next.actionAt(59_999))
        assertEquals(LimitAction.NEXT_CLIP, next.actionAt(60_000))
        assertEquals(LimitAction.NEXT_CLIP, next.actionAt(60_100))
    }
    @Test fun longLimitsDoNotOverflowMilliseconds() {
        assertEquals(86_400_000L, RecordingLimit(true, 1440).durationMs)
    }
}
