package meghdad.remember

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.app.ActivityOptions
import androidx.wear.protolayout.ProtoLayoutScope
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.*
import androidx.wear.protolayout.LayoutElementBuilders.*
import androidx.wear.protolayout.ModifiersBuilders.*
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInt32.IntFormatter
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicString
import java.time.Instant
import kotlin.math.min

/** Spatial home-screen composition, expressed as a system-rendered tile. */
@androidx.annotation.OptIn(markerClass = [androidx.wear.protolayout.expression.ProtoLayoutExperimental::class])
internal class RecordingTileLayout(private val context: Context, private val actions: RecordingTileActions,
                                   private val animateTransitions: Boolean = RecordingTileMotion.allowed(context),
                                   private val scope: ProtoLayoutScope? = null) {
    private val commands = mutableMapOf<TileRecordingAction, Clickable>()
    private val lime = 0xffc7ea18.toInt()

    fun build(state: RecordingTileState, displayedMinutes: Long = state.elapsedMillis / 60_000,
              anchorMillis: Long = System.currentTimeMillis() - state.elapsedMillis, originalArtwork: Boolean = true): LayoutElement {
        val size = context.resources.configuration.screenWidthDp.coerceIn(180, 260).toFloat()
        val compact = size <= 200
        val width = size * .74f
        val root = Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(Modifiers.Builder().setBackground(Background.Builder().setColor(argb(0xff0d110b.toInt())).build()).build())
            .addContent(Image.Builder().setResourceId("dial").setWidth(expand()).setHeight(expand()).build())
        val duration = if (state.active && !state.paused) DynamicInstant.withSecondsPrecision(Instant.ofEpochMilli(anchorMillis))
            .durationUntil(DynamicInstant.platformTimeWithSecondsPrecision()) else null
        val seconds = DegreesProp.Builder(if (state.active) (state.elapsedMillis % 60_000) / 1000f * 3f else 0f)
        duration?.let { seconds.setDynamicValue(it.secondsPart.asFloat().times(3f)) }
        // A thick, flat-ended arc reaches the center, forming the home's half-dial seconds sweep.
        root.addContent(Box.Builder().setWidth(dp(size * .98f)).setHeight(dp(size * .98f))
            .addContent(Arc.Builder().setAnchorAngle(degrees(0f)).setAnchorType(ARC_ANCHOR_START)
                .addContent(ArcLine.Builder().setLength(seconds.build()).setThickness(dp(size * .49f))
                    .setLayoutConstraintsForDynamicLength(AngularLayoutConstraint.Builder(180f).setAngularAlignment(ANGULAR_ALIGNMENT_START).build())
                    .setStrokeCap(STROKE_CAP_BUTT).setColor(argb(0x209cae69)).build()).build()).build())
        // The side instruments represent capture/paused state and the current bookmark count.
        root.addContent(edge(size, 58f, 64f, 0xff3d492a.toInt()))
        for (i in 0..11) {
            root.addContent(edge(size, 301f - i * 5.3f, 2.4f, if (state.active && !state.paused) lime else 0xff3d492a.toInt()))
            // Keep layer positions stable as bookmark count changes, preserving renderer diffing.
            root.addContent(edge(size, 58f + i * 5.3f, 2.4f, if (i < state.bookmarks) 0xffe4edce.toInt() else 0x00000000))
        }
        val clockDescription = TypeBuilders.StringProp.Builder(context.getString(R.string.tile_timer_description, state.time))
        val digits = IntFormatter.Builder().setMinIntegerDigits(2).setGroupingUsed(false).build()
        // Build one compact expression for the spoken timer; it also makes tap-to-refresh useful on older hosts.
        if (duration != null) clockDescription.setDynamicValue(duration.toIntMinutes().format(digits)
            .concat(DynamicString.constant(":"))
            .concat(duration.secondsPart.format(digits)))
        val counterSemantics = Semantics.Builder().setContentDescription(clockDescription.build())
        if (originalArtwork) counterSemantics.setStateDescription(TypeBuilders.StringProp.Builder(
            context.resources.getQuantityString(R.plurals.tile_minutes, displayedMinutes.toInt(), displayedMinutes)).build())
        val numeral = Box.Builder().setWidth(dp(size * .74f)).setHeight(dp(size * .76f))
            .setModifiers(Modifiers.Builder().setClickable(Clickable.Builder().setId("refresh").setVisualFeedbackEnabled(false)
                .setOnClick(ActionBuilders.LoadAction.Builder().build()).build())
                .setSemantics(counterSemantics.build())
                .setTransformation(Transformation.Builder().setTranslationX(dp(size * .02f)).setTranslationY(dp(-size * .025f)).build()).build())
        if (originalArtwork) {
            val number = displayedMinutes.toString()
            val scale = min(size * .67f / (number.length * 100f - 4f), size * .73f / 150f)
            val row = Row.Builder().setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            number.forEach { row.addContent(Image.Builder().setResourceId("digit-$it")
                .setWidth(dp(100f * scale)).setHeight(dp(150f * scale)).build()) }
            numeral.addContent(row.build())
        } else {
            // A dynamic fallback keeps the value honest if a host retains a tile beyond its artwork timeline.
            val minutes = TypeBuilders.StringProp.Builder(displayedMinutes.toString())
            duration?.let { minutes.setDynamicValue(it.toIntMinutes().format()) }
            numeral.addContent(Text.Builder().setText(minutes.build())
                .setLayoutConstraintsForDynamicText(TypeBuilders.StringLayoutConstraint.Builder("8888").build())
                .setFontStyle(FontStyle.Builder().setSize(sp(48f)).setWeight(FONT_WEIGHT_BOLD).setColor(argb(lime)).build()).build())
        }
        root.addContent(numeral.build())
        val statusLabel = context.getString(if (!state.active) R.string.tile_brand else if (state.paused) R.string.tile_paused else R.string.tile_recording)
        val status = Text.Builder().setText(statusLabel).setFontStyle(FontStyle.Builder().setSize(sp(10f))
            .setLetterSpacing(em(if (state.active) 0f else .2f)).setColor(argb(if (state.active) lime else 0xffffffff.toInt())).build())
        val statusModifiers = animatedModifiers()
        if (state.active) statusModifiers.setSemantics(Semantics.Builder().setContentDescription(
            statusLabel + ". " + context.getString(R.string.tile_details, state.part,
                context.resources.getQuantityString(R.plurals.tile_bookmarks, state.bookmarks, state.bookmarks))).build())
        status.setModifiers(statusModifiers.build())
        val primaryLabel = context.getString(if (state.active) R.string.tile_save else R.string.tile_start)
        val primaryAction = if (state.active) command(state, TileRecordingAction.SAVE) else startAction()
        val controls = Column.Builder().setWidth(dp(width)).setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .addContent(status.build()).addContent(gap(if (compact) 38f else 62f))
            // Same position, size, chrome, and child order for both Record and Save.
            .addContent(button(primaryLabel, primaryAction, width)).addContent(gap(8f))
        val satellites = Box.Builder().setWidth(dp(width)).setHeight(dp(64f))
        if (state.active) {
            val pause = if (state.paused) TileRecordingAction.RESUME else TileRecordingAction.PAUSE
            satellites.addContent(Row.Builder().setVerticalAlignment(VERTICAL_ALIGN_CENTER)
                .setModifiers(animatedModifiers(rise = true).build())
                .addContent(iconButton(context.getString(if (state.paused) R.string.tile_resume else R.string.tile_pause),
                    if (state.paused) "control-resume" else "control-pause", command(state, pause)))
                .addContent(Spacer.Builder().setWidth(dp(28f)).build())
                .addContent(iconButton(context.getString(R.string.tile_mark), "control-mark", command(state, TileRecordingAction.MARK))).build())
        }
        controls.addContent(satellites.build())
        root.addContent(Box.Builder().setWidth(expand()).setHeight(expand()).setVerticalAlignment(VERTICAL_ALIGN_TOP)
            .setModifiers(Modifiers.Builder().setPadding(Padding.Builder().setTop(dp(if (compact) 12f else 16f)).build()).build())
            .addContent(controls.build()).build())
        return root.build()
    }

    private fun startAction(): Clickable {
        if (scope?.hasCapability(ProtoLayoutScope.RendererCapability.PENDING_INTENT_ACTION) == true) {
            // A direct activity PendingIntent skips the host's shared-button launch animation.
            val intent = Intent(context, TileRecordingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            val pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle())
            return Clickable.Builder(scope, "start").setVisualFeedbackEnabled(false).setOnClick(pending).build()
        }
        return Clickable.Builder().setId("start").setVisualFeedbackEnabled(false)
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(ActionBuilders.AndroidActivity.Builder()
                .setPackageName(context.packageName).setClassName(TileRecordingActivity::class.java.name).build()).build()).build()
    }

    private fun command(state: RecordingTileState, action: TileRecordingAction) = commands.getOrPut(action) {
        Clickable.Builder().setId(actions.issue(state.clipId!!, action)).setVisualFeedbackEnabled(false).setOnClick(ActionBuilders.LoadAction.Builder().build()).build()
    }
    private fun edge(size: Float, angle: Float, length: Float, color: Int) = Box.Builder()
        .setWidth(dp(size * .92f)).setHeight(dp(size * .92f)).addContent(Arc.Builder()
            .setAnchorAngle(degrees(angle)).setAnchorType(ARC_ANCHOR_START)
            .addContent(ArcLine.Builder().setLength(degrees(length)).setThickness(dp(size * .014f)).setColor(argb(color)).build()).build()).build()
    private fun gap(height: Float) = Spacer.Builder().setHeight(dp(height)).build()
    private fun animatedModifiers(rise: Boolean = false) = Modifiers.Builder().apply {
        if (animateTransitions) setContentUpdateAnimation(RecordingTileMotion.transition(rise))
    }
    private fun iconButton(label: String, resource: String, click: Clickable) = Box.Builder()
        .setWidth(dp(48f)).setHeight(dp(48f))
        .setModifiers(Modifiers.Builder().setClickable(click)
            .setSemantics(Semantics.Builder().setRole(SEMANTICS_ROLE_BUTTON).setContentDescription(label).build()).build())
        .addContent(Box.Builder().setWidth(dp(36f)).setHeight(dp(36f))
            .setModifiers(Modifiers.Builder()
                .setBorder(Border.Builder().setWidth(dp(1f)).setColor(argb(0x547d8b58)).build())
                .setBackground(Background.Builder().setColor(argb(0xe5181f10.toInt()))
                    .setCorner(Corner.Builder().setRadius(dp(18f)).build()).build()).build())
            .addContent(Image.Builder().setResourceId(resource).setWidth(dp(22f)).setHeight(dp(22f)).build()).build()).build()

    private fun button(label: String, click: Clickable, width: Float) = Box.Builder()
        .setWidth(dp(width)).setHeight(dp(48f))
        .setModifiers(Modifiers.Builder().setClickable(click)
            .setSemantics(Semantics.Builder().setRole(SEMANTICS_ROLE_BUTTON).setContentDescription(label).build())
            .setBorder(Border.Builder().setWidth(dp(1f)).setColor(argb(0x8cc7ea18.toInt())).build())
            .setBackground(Background.Builder().setColor(argb(0xbe181f10.toInt()))
                .setCorner(Corner.Builder().setRadius(dp(28f)).build()).build()).build())
        .addContent(Text.Builder().setText(when (label) {
            context.getString(R.string.tile_start) -> context.getString(R.string.tile_record_button)
            context.getString(R.string.tile_save) -> context.getString(R.string.tile_save_button)
            else -> label
        }).setFontStyle(FontStyle.Builder().setSize(sp(12f)).setColor(argb(0xffe4edce.toInt())).build())
            .setMaxLines(1).setModifiers(animatedModifiers().build()).build()).build()
}
