package meghdad.remember

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.protolayout.LayoutElementBuilders.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingTileTimelineTest {
    private fun serializedSize(value: Any): Int {
        // Proto implementation classes are runtime-only dependencies of the public builders.
        val proto = value.javaClass.getMethod("toProto").invoke(value)
        return proto.javaClass.getMethod("getSerializedSize").invoke(proto) as Int
    }
    private fun descendants(element: LayoutElement): List<LayoutElement> = listOf(element) + when (element) {
        is Box -> element.contents.flatMap(::descendants)
        is Column -> element.contents.flatMap(::descendants)
        is Row -> element.contents.flatMap(::descendants)
        else -> emptyList()
    }
    @Test fun minuteArtworkCrossesDigitBoundariesWithoutExpiringCurrentControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actions = RecordingTileActions(3)
        val now = 1_800_000_000_000L
        val state = RecordingTileState("clip", false, 599_000, 2, 1)
        val tile = RecordingTilePresentation.tile(context, state, actions, now)
        val entries = tile.tileTimeline!!.timelineEntries
        assertEquals(61, entries.size)
        assertNull(entries.first().validity) // Honest dynamic fallback beyond the cached artwork hour.
        assertEquals(now + 1000, entries[1].validity!!.endMillis)
        assertEquals(entries[1].validity!!.endMillis, entries[2].validity!!.startMillis)
        fun numeral(index: Int) = descendants(entries[index].layout!!.root!!).filterIsInstance<Image>()
            .mapNotNull { it.resourceId?.value?.takeIf { id -> id.startsWith("digit-") } }.joinToString("") { it.removePrefix("digit-") }
        assertEquals("9", numeral(1))
        assertEquals("10", numeral(2))
        assertEquals("68", numeral(60))
        fun commands(index: Int) = descendants(entries[index].layout!!.root!!).filterIsInstance<Box>()
            .mapNotNull { it.modifiers?.clickable?.id }.filter { it != "refresh" }.toSet()
        assertEquals(3, commands(1).size)
        assertEquals(commands(1), commands(60))
        commands(1).forEach { token ->
            assertNotNull("Generating future frames must not evict current controls", actions.consume(token, "clip"))
            assertNull(actions.consume(token, "clip"))
        }
        assertTrue("Keep the tile response comfortably below the Binder limit", serializedSize(tile) < 700_000)
        assertTrue(serializedSize(RecordingTileArtwork.resources) < 200_000)
    }
    @Test fun pausedAndIdleTilesKeepOneStaticMinuteArtworkFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (state in listOf(RecordingTileState(), RecordingTileState("clip", true, 7_205_000, 3, 2))) {
            val tile = RecordingTilePresentation.tile(context, state, RecordingTileActions())
            assertEquals(1, tile.tileTimeline!!.timelineEntries.size)
            assertNull(tile.tileTimeline!!.timelineEntries.single().validity)
        }
    }
}
