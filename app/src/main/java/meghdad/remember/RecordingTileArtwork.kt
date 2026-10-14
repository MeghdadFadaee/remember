package meghdad.remember

import android.graphics.*
import androidx.wear.protolayout.ResourceBuilders
import java.io.ByteArrayOutputStream

/** Small, process-cached resources drawn from the same original paths as MinuteCounterView. */
internal object RecordingTileArtwork {
    const val VERSION = "recording-spatial-1"
    val resources: ResourceBuilders.Resources by lazy {
        ResourceBuilders.Resources.Builder().setVersion(VERSION).apply {
            for (digit in 0..9) addIdToImageMapping("digit-$digit", image(160, 240) { canvas ->
                canvas.scale(1.6f, 1.6f)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 30f
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                    shader = LinearGradient(0f, 15f, 75f, 143f,
                        intArrayOf(Color.rgb(145, 157, 126), Color.rgb(197, 211, 155), Color.rgb(204, 235, 40), Color.rgb(162, 203, 0)),
                        floatArrayOf(0f, .3f, .5f, 1f), Shader.TileMode.CLAMP)
                }
                canvas.drawPath(MinuteNumeralArtwork.path(digit), paint)
            })
            addIdToImageMapping("dial", image(300, 300) { canvas ->
                canvas.drawPaint(Paint().apply {
                    shader = RadialGradient(195f, 90f, 240f,
                        intArrayOf(Color.rgb(48, 57, 35), Color.rgb(22, 29, 18), Color.rgb(13, 17, 11)),
                        floatArrayOf(0f, .65f, 1f), Shader.TileMode.CLAMP)
                })
            })
        }.build()
    }

    private fun image(width: Int, height: Int, draw: (Canvas) -> Unit): ResourceBuilders.ImageResource {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            draw(Canvas(bitmap))
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            ResourceBuilders.ImageResource.Builder().setInlineResource(ResourceBuilders.InlineImageResource.Builder()
                .setData(output.toByteArray()).setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                .setWidthPx(width).setHeightPx(height).build()).build()
        } finally { bitmap.recycle() }
    }
}
