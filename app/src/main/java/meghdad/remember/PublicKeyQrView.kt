package meghdad.remember

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.google.zxing.common.BitMatrix
import kotlin.math.min

/** Integer-sized, unfiltered modules keep the code sharp on a small watch display. */
internal class PublicKeyQrView(context: Context, private val code: BitMatrix) : View(context) {
    private val ink = Paint().apply { color = Color.BLACK; isAntiAlias = false }
    init {
        contentDescription = context.getString(R.string.backup_public_key_qr_description)
        setBackgroundColor(Color.WHITE)
        keepScreenOn = true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width / code.width, height / code.height)
        if (scale < 1) return
        val left = (width - code.width * scale) / 2
        val top = (height - code.height * scale) / 2
        for (y in 0 until code.height) for (x in 0 until code.width) if (code[x, y]) {
            canvas.drawRect((left + x * scale).toFloat(), (top + y * scale).toFloat(),
                (left + (x + 1) * scale).toFloat(), (top + (y + 1) * scale).toFloat(), ink)
        }
    }
}
