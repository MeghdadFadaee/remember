package meghdad.remember

import android.graphics.*
import androidx.wear.protolayout.ResourceBuilders
import java.io.ByteArrayOutputStream

/** Small, process-cached resources drawn from the same original paths as MinuteCounterView. */
internal object RecordingTileArtwork {
    const val VERSION = "recording-spatial-2"
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
            for (name in listOf("pause", "resume", "mark")) addIdToImageMapping("control-$name", image(64, 64) { canvas ->
                canvas.scale(2f, 2f)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(228, 237, 206); strokeWidth = 2.4f
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
                }
                when (name) {
                    "pause" -> { canvas.drawLine(12f, 9f, 12f, 23f, paint); canvas.drawLine(20f, 9f, 20f, 23f, paint) }
                    "resume" -> {
                        paint.style = Paint.Style.FILL
                        canvas.drawPath(Path().apply { moveTo(11f, 7f); lineTo(24f, 16f); lineTo(11f, 25f); close() }, paint)
                    }
                    "mark" -> canvas.drawPath(Path().apply {
                        moveTo(10f, 7f); lineTo(22f, 7f); lineTo(22f, 25f); lineTo(16f, 21f); lineTo(10f, 25f); close()
                    }, paint)
                }
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
