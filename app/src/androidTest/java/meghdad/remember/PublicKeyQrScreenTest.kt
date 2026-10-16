package meghdad.remember

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses only a disposable watch identity; no network connection or recording is needed. */
@RunWith(AndroidJUnit4::class)
class PublicKeyQrScreenTest {
    @Test fun serverShowsScannablePublicKeyAndBackPreservesTheWatchIdentity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = BackupSettings(context)
        val key = settings.generateKey()
        val encryptedIdentity = settings.preferences.getString("key", null)
        val activity = instrumentation.startActivitySync(Intent(context, BackupActivity::class.java)
            .putExtra(BackupActivity.EXTRA_PAGE, "server").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun awaitView(match: (View) -> Boolean): View {
            var found: View? = null
            val end = SystemClock.elapsedRealtime() + 6000
            while (found == null && SystemClock.elapsedRealtime() < end) {
                instrumentation.runOnMainSync { found = descendants(activity.window.decorView).firstOrNull(match) }
                if (found == null) SystemClock.sleep(50)
            }
            return found ?: error("Public-key page did not open")
        }
        try {
            instrumentation.runOnMainSync {
                descendants(activity.window.decorView).filterIsInstance<EditText>().first().setText("backup.example")
            }
            onView(withText("Show watch key")).perform(scrollTo(), click())
            awaitView { it is Button && it.text == "Show QR code" }
            onView(withText("Show QR code")).perform(scrollTo(), click())
            val qr = awaitView { it is PublicKeyQrView }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(3300) // Let the touch-revealed menu hide before the scan/screenshot.
            val snapshot = instrumentation.uiAutomation.takeScreenshot()!!
            try {
                val pixels = IntArray(snapshot.width * snapshot.height)
                snapshot.getPixels(pixels, 0, snapshot.width, 0, 0, snapshot.width, snapshot.height)
                assertEquals(key.trim(), QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
                    RGBLuminanceSource(snapshot.width, snapshot.height, pixels)))).text)
                File(context.getExternalFilesDir(null), "public-key-qr.png").outputStream().use { snapshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                instrumentation.runOnMainSync {
                    val point = IntArray(2); qr.getLocationOnScreen(point)
                    val radius = snapshot.width / 2.0
                    for (x in listOf(point[0], point[0] + qr.width)) for (y in listOf(point[1], point[1] + qr.height)) {
                        assertTrue("QR quiet border must fit the round screen", kotlin.math.hypot(x - radius, y - snapshot.height / 2.0) < radius)
                    }
                    assertTrue(qr.keepScreenOn)
                    assertFalse(qr.contentDescription.isNullOrBlank())
                }
            } finally { snapshot.recycle() }
            pressBack()
            awaitView { it is Button && it.text == "Copy on watch" }
            pressBack()
            awaitView { it is Button && it.text == "Show watch key" }
            instrumentation.runOnMainSync {
                assertEquals("backup.example", descendants(activity.window.decorView).filterIsInstance<EditText>().first().text.toString())
            }
            assertEquals(key, settings.publicKey())
            assertEquals(encryptedIdentity, settings.preferences.getString("key", null))
            assertNull(RecordingService.activeId)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
