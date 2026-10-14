package meghdad.remember

import android.graphics.Path
import android.graphics.RectF

/** Shared original rounded numeral paths for the app and its recording tile. */
internal object MinuteNumeralArtwork {
    fun path(digit: Int) = Path().apply {
        when (digit) {
            0 -> addRoundRect(RectF(23f, 25f, 73f, 125f), 25f, 25f, Path.Direction.CW)
            1 -> { moveTo(31f, 43f); lineTo(58f, 25f); lineTo(58f, 125f) }
            2 -> { moveTo(23f, 41f); cubicTo(27f, 14f, 73f, 17f, 73f, 47f); cubicTo(73f, 65f, 47f, 82f, 24f, 112f); quadTo(19f, 125f, 32f, 125f); lineTo(75f, 125f) }
            3 -> { moveTo(24f, 29f); cubicTo(77f, 7f, 90f, 72f, 48f, 73f); cubicTo(94f, 75f, 76f, 142f, 23f, 121f) }
            4 -> { moveTo(52f, 25f); lineTo(22f, 84f); quadTo(17f, 92f, 29f, 92f); lineTo(77f, 92f); moveTo(66f, 25f); lineTo(66f, 125f) }
            5 -> { moveTo(73f, 25f); lineTo(26f, 25f); lineTo(23f, 70f); cubicTo(84f, 45f, 96f, 141f, 24f, 121f) }
            6 -> { moveTo(68f, 26f); cubicTo(21f, 14f, 15f, 72f, 24f, 106f); cubicTo(35f, 145f, 86f, 125f, 72f, 89f); cubicTo(62f, 65f, 28f, 73f, 22f, 92f) }
            7 -> { moveTo(22f, 25f); lineTo(76f, 25f); lineTo(31f, 125f) }
            8 -> {
                addRoundRect(RectF(25f, 24f, 71f, 74f), 23f, 23f, Path.Direction.CW)
                addRoundRect(RectF(22f, 74f, 74f, 126f), 26f, 26f, Path.Direction.CW)
            }
            9 -> { moveTo(27f, 124f); cubicTo(75f, 139f, 84f, 79f, 72f, 44f); cubicTo(57f, 6f, 10f, 28f, 25f, 64f); cubicTo(36f, 87f, 69f, 77f, 75f, 61f) }
        }
    }
}
