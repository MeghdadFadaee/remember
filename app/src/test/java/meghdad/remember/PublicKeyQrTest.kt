package meghdad.remember

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.jcraft.jsch.KeyPair
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class PublicKeyQrTest {
    @Test fun phoneScannableCodesPreserveEd25519AndLegacyRsaPublicKeysAtWatchSizes() {
        for (type in listOf(KeyPair.ED25519, KeyPair.RSA)) {
            val pair = KeyPair.genKeyPair(SshCrypto.client(), type, 2048)
            val key = try { ByteArrayOutputStream().also { pair.writePublicKey(it, "remember-watch") }.toString("UTF-8").trim() }
                finally { pair.dispose() }
            val code = PublicKeyQr.encode("$key\n")
            for (side in listOf(238, 280)) {
                val scale = side / code.width
                assertTrue("Watch code must retain at least two pixels per module", scale >= 2)
                val offset = (side - code.width * scale) / 2
                val pixels = IntArray(side * side) { -1 }
                for (y in 0 until code.height) for (x in 0 until code.width) if (code[x, y]) {
                    for (dy in 0 until scale) for (dx in 0 until scale) pixels[(offset + y * scale + dy) * side + offset + x * scale + dx] = 0xff000000.toInt()
                }
                val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels))))
                assertEquals(key, decoded.text)
            }
            for (i in 0 until code.width) for (margin in 0..3) {
                assertFalse(code[i, margin]); assertFalse(code[margin, i])
                assertFalse(code[i, code.height - 1 - margin]); assertFalse(code[code.width - 1 - margin, i])
            }
        }
    }
    @Test fun privateKeyAndMultilinePayloadsAreRejected() {
        for (payload in listOf("-----BEGIN OPENSSH PRIVATE KEY-----", "ssh-ed25519 aaa\nsecret", "ssh-rsa aaa\rsecret", "ssh-rsa " + "a".repeat(2048))) {
            assertThrows(IllegalArgumentException::class.java) { PublicKeyQr.encode(payload) }
        }
    }
}
