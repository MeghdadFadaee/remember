package meghdad.remember

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Plain OpenSSH public-key text, encoded locally with a four-module quiet border. */
internal object PublicKeyQr {
    fun encode(publicKey: String): BitMatrix {
        val line = publicKey.trim()
        require(line.length <= 2048 && !line.contains('\n') && !line.contains('\r') &&
            (line.startsWith("ssh-ed25519 ") || line.startsWith("ssh-rsa "))) { "Expected a watch public key" }
        return QRCodeWriter().encode(line, BarcodeFormat.QR_CODE, 0, 0, mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4,
        ))
    }
}
