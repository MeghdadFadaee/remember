package meghdad.remember

import android.content.Context
import androidx.wear.protolayout.ProtoLayoutScope
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.TileBuilders

/** Bounded minute artwork timeline, with one shared set of commands and a live time fallback. */
internal object RecordingTilePresentation {
    fun tile(context: Context, state: RecordingTileState, actions: RecordingTileActions, now: Long = System.currentTimeMillis(),
             scope: ProtoLayoutScope? = null): TileBuilders.Tile {
        val anchor = now - state.elapsedMillis
        val layout = RecordingTileLayout(context, actions, scope = scope)
        val timeline = TimelineBuilders.Timeline.Builder()
        fun entry(minutes: Long, start: Long? = null, end: Long? = null, original: Boolean = true) {
            val item = TimelineBuilders.TimelineEntry.Builder().setLayout(
                Layout.Builder()
                    .setRoot(layout.build(state, minutes, anchor, original)).build())
            if (start != null && end != null) item.setValidity(TimelineBuilders.TimeInterval.Builder()
                .setStartMillis(start).setEndMillis(end).build())
            timeline.addTimelineEntry(item.build())
        }
        val minute = state.elapsedMillis / 60_000
        if (state.active && !state.paused) {
            // Static glyphs advance through a host timeline. Share action tokens across frames so
            // rendering future minutes neither evicts current controls nor introduces app polling.
            entry(minute, original = false)
            repeat(60) { offset ->
                val value = minute + offset
                entry(value, anchor + value * 60_000, anchor + (value + 1) * 60_000)
            }
        } else entry(minute)
        return TileBuilders.Tile.Builder().setResourcesVersion(RecordingTileArtwork.VERSION)
            .setFreshnessIntervalMillis(if (state.active) 60_000 else 0)
            .setTileTimeline(timeline.build()).build()
    }

}
