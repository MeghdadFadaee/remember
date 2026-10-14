package meghdad.remember

import android.app.job.JobScheduler
import android.Manifest
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings get() = BackupSettings(context)
    private fun restorePreferences(previous: Map<String, *>) {
        val editor = settings.preferences.edit().clear()
        previous.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
        } }
        editor.commit()
    }
    @Test fun backupPagesAreReachableAndAutomaticUploadsDefaultOff() {
        val activity = instrumentation.startActivitySync(Intent(context, BackupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                    (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                fun button(label: String) = descendants(activity.window.decorView).filterIsInstance<Button>().first { it.text == label }
                listOf("Server", "Transfers", "Server recordings", "Automatic backup", "Advanced").forEach {
                    button(it).performClick(); button("Back").performClick()
                }
            }
            assertFalse(settings.automatic)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun encryptedWatchKeyPersistsWithoutPlaintextPrivateKeyInPreferences() {
        val public = settings.generateKey()
        assertTrue(public.startsWith("ssh-ed25519 "))
        assertEquals(public, BackupSettings(context).generateKey())
        val privateKey = settings.privateKey()
        try {
            assertTrue(privateKey.toString(Charsets.UTF_8).contains("OPENSSH PRIVATE KEY"))
            assertFalse(settings.preferences.getString("key", "")!!.contains("PRIVATE KEY"))
        } finally { privateKey.fill(0) }
    }
    @Test fun manifestsPreserveTitlesAndBookmarksAndRejectTraversal() {
        val clip = Clip(UUID.randomUUID().toString(), "A thought", 1000, 20000, listOf(1000, 12000))
        assertEquals(clip, parseRemoteClip(clipManifest(clip, 4096, "a".repeat(64))).clip)
        assertThrows(IllegalArgumentException::class.java) {
            parseRemoteClip(clipManifest(clip.copy(id = "../../private"), 4096, "a".repeat(64)))
        }
    }
    @Test fun discoveryDoesNotSaveTrustOrRequireAWatchKey() {
        val args = InstrumentationRegistry.getArguments()
        val expected = args.getString("sftpFingerprint")
        assumeTrue("Disposable SFTP fixture not configured", expected != null)
        val previous = settings.preferences.all
        val pending = BackupServer("127.0.0.1", args.getString("sftpPort", "2222").toInt(), "backup-test", "/remember", "")
        try {
            settings.save(pending)
            settings.preferences.edit().remove("key").remove("public_key").commit()
            val found = ServerTrustProbe(pending, 20).use { it.fetch() }
            assertEquals(expected, found)
            assertEquals("", settings.server().fingerprint)
            assertNull(settings.publicKey())
            assertThrows(IllegalArgumentException::class.java) { BackupTransport(context).use { it.connect() } }
        } finally { restorePreferences(previous) }
    }
    @Test fun connectAndTrustRequiresApprovalAndBlocksChangedIdentity() {
        val args = InstrumentationRegistry.getArguments()
        val expected = args.getString("sftpFingerprint")
        assumeTrue("Disposable SFTP fixture not configured", expected != null)
        val previous = settings.preferences.all
        val pending = BackupServer("127.0.0.1", args.getString("sftpPort", "2222").toInt(), "backup-test", "/remember", "")
        var activity: android.app.Activity? = null
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun press(label: String) = instrumentation.runOnMainSync {
            descendants(activity!!.window.decorView).filterIsInstance<Button>().first { it.text == label }.performClick()
        }
        fun await(condition: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 10000
            while (!condition() && android.os.SystemClock.elapsedRealtime() < end) android.os.SystemClock.sleep(50)
            assertTrue("Trust flow did not reach expected state", condition())
        }
        fun dialogVisible() = instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText("Trust this server?")?.any { it.text?.toString() == "Trust this server?" } == true
        try {
            settings.save(pending)
            settings.preferences.edit().remove("key").remove("public_key").commit()
            activity = instrumentation.startActivitySync(Intent(context, BackupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            press("Server")
            instrumentation.runOnMainSync {
                assertEquals("Fingerprint must not require typing", 4, descendants(activity!!.window.decorView).filterIsInstance<EditText>().size)
            }
            press("Connect & trust"); await { dialogVisible() }
            assertEquals("", settings.server().fingerprint)
            if (args.getString("saveTrustScreenshot") == "true") {
                instrumentation.uiAutomation.waitForIdle(200, 3000)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "trust-dialog.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            onView(withText(android.R.string.cancel)).perform(click())
            assertEquals("", settings.server().fingerprint)
            press("Connect & trust"); await { dialogVisible() }
            press("View fingerprint")
            onView(withText(expected)).check(matches(isDisplayed()))
            assertEquals("Viewing the fingerprint must not approve it", "", settings.server().fingerprint)
            press("Back"); await { dialogVisible() }
            if (args.getString("saveTrustScreenshot") == "true") {
                instrumentation.uiAutomation.waitForIdle(200, 3000)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "trust-dialog.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            onView(withText("Trust server")).perform(click())
            await { settings.server().fingerprint == expected }
            val wrong = "SHA256:" + "A".repeat(43)
            settings.save(settings.server().copy(fingerprint = wrong))
            press("Connect & trust")
            val busyField = BackupActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }
            await { var busy = true; instrumentation.runOnMainSync { busy = busyField.getBoolean(activity) }; !busy }
            assertEquals("Changed identity must retain the existing pin", wrong, settings.server().fingerprint)
            assertFalse(dialogVisible())
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            restorePreferences(previous)
        }
    }
    @Test fun rsaIdentityIsRetainedUntilExplicitEd25519Replacement() {
        val previous = settings.preferences.all
        val rsa = com.jcraft.jsch.KeyPair.genKeyPair(SshCrypto.client(), com.jcraft.jsch.KeyPair.RSA, 2048)
        val privateBytes = java.io.ByteArrayOutputStream().also { rsa.writePrivateKey(it) }.toByteArray()
        try {
            val public = java.io.ByteArrayOutputStream().also { rsa.writePublicKey(it, "migration-fixture") }.toString("UTF-8")
            val encrypt = BackupSettings::class.java.getDeclaredMethod("encrypt", ByteArray::class.java).apply { isAccessible = true }
            settings.preferences.edit().putString("key", encrypt.invoke(settings, privateBytes) as String).putString("public_key", public).commit()
            assertEquals(public, settings.generateKey())
            val retained = settings.privateKey()
            try { assertArrayEquals(privateBytes, retained) } finally { retained.fill(0) }
            val replaced = settings.generateKey(replace = true)
            assertTrue(replaced.startsWith("ssh-ed25519 "))
            assertEquals(replaced, BackupSettings(context).generateKey())
            val key = settings.privateKey()
            try { assertTrue(key.toString(Charsets.UTF_8).contains("OPENSSH PRIVATE KEY")) }
            finally { key.fill(0) }
        } finally { privateBytes.fill(0); rsa.dispose(); restorePreferences(previous) }
    }
    @Test fun manualJobBacksUpSavedAudioAndAutomaticJobsKeepConstraints() {
        val args = InstrumentationRegistry.getArguments()
        val fingerprint = args.getString("sftpFingerprint")
        assumeTrue("Disposable SFTP fixture not configured", fingerprint != null)
        assumeTrue("Do not test during recording", RecordingService.activeId == null)
        val previous = settings.preferences.all
        val store = Recordings(context)
        val clip = Clip(UUID.randomUUID().toString(), "Scheduled fixture", 1000, 10000, listOf(1000))
        val scheduler = context.getSystemService(JobScheduler::class.java)
        settings.save(BackupServer("127.0.0.1", args.getString("sftpPort", "2222").toInt(), "backup-test", "/remember", fingerprint!!))
        settings.generateKey()
        store.audio(clip.id).writeBytes(ByteArray(65536) { (it % 251).toByte() }); store.save(clip)
        try {
            BackupWork.start(context)
            val end = android.os.SystemClock.elapsedRealtime() + 60_000
            while (settings.receipt(clip.id) == null && android.os.SystemClock.elapsedRealtime() < end) android.os.SystemClock.sleep(100)
            assertEquals(backupVersion(clip, store.audio(clip.id)), settings.receipt(clip.id))
            settings.preferences.edit().putBoolean("automatic", true).putBoolean("charging", true).putBoolean("unmetered", true).putInt("hours", 24).commit()
            BackupWork.schedule(context)
            val job = scheduler.getPendingJob(72)!!
            assertTrue(job.isRequireCharging)
            assertTrue(job.isPersisted)
            assertTrue(job.requiredNetwork!!.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            assertEquals(24 * 60 * 60_000L, job.intervalMillis)
            BackupWork.cancel(context)
            assertNull(scheduler.getPendingJob(72))
            assertFalse(settings.automatic)
            assertTrue("Backup must retain local audio", store.audio(clip.id).exists())
        } finally {
            BackupWork.cancel(context); store.delete(clip)
            restorePreferences(previous)
        }
    }
    @Test fun remoteAudioPlaysFromVerifiedCacheAndPausesInBackground() {
        val args = InstrumentationRegistry.getArguments()
        val fingerprint = args.getString("sftpFingerprint")
        assumeTrue("Disposable SFTP fixture not configured", fingerprint != null)
        assumeTrue("Do not test during recording", RecordingService.activeId == null)
        val previous = settings.preferences.all
        val audio = File(context.cacheDir, "remote-playback-fixture.m4a")
        var activity: android.app.Activity? = null
        var foreground: android.app.Activity? = null
        settings.save(BackupServer("127.0.0.1", args.getString("sftpPort", "2222").toInt(), "backup-test", "/remember", fingerprint!!))
        settings.generateKey()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        try {
            activity = instrumentation.startActivitySync(Intent(context, BackupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val recorder = MediaRecorder(context)
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setOutputFile(audio.absolutePath)
                recorder.prepare(); recorder.start(); android.os.SystemClock.sleep(3000); recorder.stop()
            } finally { recorder.release() }
            val id = UUID.randomUUID().toString()
            val clip = Clip(id, "Playback fixture ${id.take(8)}", 1000, 3000, listOf(1000))
            BackupTransport(context).use { client -> client.connect(); client.upload(audio, clip) { _, _ -> } }
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            fun click(prefix: String) = instrumentation.runOnMainSync {
                descendants(activity!!.window.decorView).filterIsInstance<Button>().first { it.text.startsWith(prefix) }.performClick()
            }
            fun await(timeout: Long = 10000, condition: () -> Boolean) {
                val end = android.os.SystemClock.elapsedRealtime() + timeout
                var ready = false
                while (!ready && android.os.SystemClock.elapsedRealtime() < end) {
                    instrumentation.runOnMainSync { ready = condition() }; if (!ready) android.os.SystemClock.sleep(50)
                }
                assertTrue("Remote playback did not reach expected state", ready)
            }
            click("Server recordings"); click("Refresh")
            await { descendants(activity!!.window.decorView).filterIsInstance<Button>().any { it.text.startsWith(clip.title) } }
            click(clip.title); click("Download & play")
            val playerField = BackupActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
            val readyField = BackupActivity::class.java.getDeclaredField("playbackReady").apply { isAccessible = true }
            await { readyField.getBoolean(activity) && (playerField.get(activity) as? MediaPlayer)?.isPlaying == true }
            foreground = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync { assertFalse((playerField.get(activity) as MediaPlayer).isPlaying) }
            assertEquals(audio.inputStream().use { digestStream(it) }, File(context.cacheDir, "server-playback.m4a").inputStream().use { digestStream(it) })
        } finally {
            instrumentation.runOnMainSync { foreground?.finish(); activity?.finish() }
            audio.delete(); File(context.cacheDir, "server-playback.m4a").delete()
            restorePreferences(previous)
        }
    }
    /** Opt-in: point ONLY at a disposable SFTP fixture, never at a personal backup folder. */
    @Test fun sftpResumesVerifiesAndRejectsChangedHostKey() {
        val args = InstrumentationRegistry.getArguments()
        val fingerprint = args.getString("sftpFingerprint")
        assumeTrue("Disposable SFTP fixture not configured", fingerprint != null)
        val previous = settings.preferences.all
        val fixture = BackupServer("127.0.0.1", args.getString("sftpPort", "2222").toInt(), "backup-test", "/remember", fingerprint!!)
        val file = File(context.cacheDir, "backup-fixture.bin")
        val downloaded = File(context.cacheDir, "backup-downloaded.bin")
        val clip = Clip(UUID.randomUUID().toString(), "Disposable test", 1000, 20000, listOf(1000, 5000))
        file.outputStream().use { output -> repeat(64) { output.write(ByteArray(32768) { (it % 251).toByte() }) } }
        settings.save(fixture); settings.generateKey()
        try {
            var interrupted = false
            BackupTransport(context).use { client ->
                client.connect()
                try { client.upload(file, clip) { sent, _ -> if (sent > 32768) error("Simulated interruption") } }
                catch (_: Exception) { interrupted = true }
            }
            assertTrue(interrupted)
            var firstProgress = 0L
            BackupTransport(context).use { client ->
                client.connect()
                client.upload(file, clip) { sent, total ->
                    if (firstProgress == 0L) firstProgress = sent
                    assertTrue(sent <= total)
                }
                assertTrue("Expected resumed transfer", firstProgress > 32768)
                val remote = client.list().single { it.clip.id == clip.id }
                assertEquals(clip, remote.clip)
                client.download(remote, downloaded) { _, _ -> }
                assertEquals(file.inputStream().use { digestStream(it) }, downloaded.inputStream().use { digestStream(it) })
                assertThrows(IllegalArgumentException::class.java) { client.download(remote.copy(digest = "0".repeat(64)), downloaded) { _, _ -> } }
                assertEquals("Failed verification must preserve the previous cached file", file.length(), downloaded.length())
                client.upload(file, clip.copy(title = "Renamed")) { _, _ -> }
                assertEquals("Renamed", client.list().single { it.clip.id == clip.id }.clip.title)
            }
            settings.save(fixture.copy(fingerprint = "SHA256:" + "A".repeat(43)))
            assertThrows(Exception::class.java) { BackupTransport(context).use { it.connect() } }
        } finally {
            file.delete(); downloaded.delete()
            // Previous settings may be incomplete, so restore their fields without validating them.
            restorePreferences(previous)
        }
    }
}
