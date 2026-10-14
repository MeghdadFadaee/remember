package meghdad.remember

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/** A system-rendered remote control for the current clip; never starts microphone capture. */
class RecordingTileService : TileService() {
    companion object {
        private val actions = RecordingTileActions()
        internal fun refresh(context: Context) {
            // Tile availability must never interfere with saving or capturing audio.
            runCatching { getUpdater(context).requestUpdate(RecordingTileService::class.java) }
        }
    }

    public override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val before = RecordingService.tileState()
        actions.consume(requestParams.currentState.lastClickableId, before.clipId)?.let { action ->
            before.clipId?.let { RecordingService.tileCommand(it, action) }
        }
        return immediate(RecordingTilePresentation.tile(this, RecordingService.tileState(), actions, scope = requestParams.scope))
    }

    public override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        immediate(RecordingTileArtwork.resources)

    override fun onRecentInteractionEventsAsync(events: List<EventBuilders.TileInteractionEvent>): ListenableFuture<Void> {
        if (events.any { it.eventType == EventBuilders.TileInteractionEvent.ENTER }) refresh(this)
        return immediate<Void>(null)
    }

    private fun <T> immediate(value: T?): ListenableFuture<T> = CallbackToFutureAdapter.getFuture { completer ->
        completer.set(value)
        "Recording tile response"
    }
}
