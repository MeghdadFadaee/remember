package meghdad.remember

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeLabelTest {
    @Test fun elapsedTimeFormatsMinuteAndHourBoundaries() {
        assertEquals("00:00", timeLabel(0))
        assertEquals("00:59", timeLabel(59_999))
        assertEquals("01:00", timeLabel(60_000))
        assertEquals("59:59", timeLabel(3_599_999))
        assertEquals("1:00:00", timeLabel(3_600_000))
        assertEquals("2:03:04", timeLabel(7_384_000))
    }
}
