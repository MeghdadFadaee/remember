package meghdad.remember

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.*
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {
    companion object {
        @Volatile var activeId: String? = null; private set
        @Volatile var paused = false; private set
        @Volatile var elapsed = 0L; private set
        @Volatile var amplitude = 0; private set
        @Volatile var marks: List<Long> = emptyList(); private set
        @Volatile var part = 1; private set
        var message: String? = null
        const val START = "start"
        const val PAUSE = "pause"
        const val SAVE = "save"
        const val MARK = "mark"
    }
    private var recorder: MediaRecorder? = null
    private var lock: PowerManager.WakeLock? = null
    private var segmentStart = 0L
    private var accumulated = 0L
    private var created = 0L
    private var lockRenewed = 0L
    private var sessionTitle = ""
    private var sessionLimit = RecordingLimit()
    private var transitioning = false
    private val handler = Handler(Looper.getMainLooper())
    private fun capturedTime() = accumulated + if (paused) 0L else SystemClock.elapsedRealtime() - segmentStart
    private val tick = object : Runnable {
        override fun run() {
            if (recorder == null || transitioning) return
            if (!paused && SystemClock.elapsedRealtime() - lockRenewed > 9 * 60 * 1000L) {
                lock?.acquire(10 * 60 * 1000L)
                lockRenewed = SystemClock.elapsedRealtime()
            }
            elapsed = capturedTime()
            amplitude = if (paused) 0 else runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            when (sessionLimit.actionAt(elapsed)) {
                LimitAction.STOP -> finishRecording("Minute limit reached · saved")
                LimitAction.NEXT_CLIP -> nextClip()
                null -> handler.postDelayed(this, 100)
            }
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                START -> if (recorder == null) begin()
                PAUSE -> togglePause()
                MARK -> if (recorder != null && !transitioning) marks = marks + capturedTime()
                SAVE -> if (recorder != null) finishRecording() else stopSelf()
            }
        } catch (_: Exception) {
            message = "Recording interrupted. Please try again."
            release(); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun begin() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("capture", "Audio recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val save = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(SAVE), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "capture").setSmallIcon(R.drawable.ic_remember)
            .setContentTitle("Remember is recording").setContentText("Tap to open • Save when you're done")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Save", save).build()).build()
        startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        sessionLimit = RecordingPreferences(this).read()
        sessionTitle = "Moment · " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        part = 1; message = null
        lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "remember:capture").apply {
            setReferenceCounted(false); acquire(10 * 60 * 1000L)
        }
        lockRenewed = SystemClock.elapsedRealtime()
        startClip()
    }
    private fun startClip() {
        created = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        accumulated = 0; elapsed = 0; marks = emptyList(); paused = false; amplitude = 0
        val current = MediaRecorder(this)
        recorder = current
        current.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100); setAudioEncodingBitRate(128000); setAudioChannels(1)
            setOutputFile(Recordings(this@RecordingService).pending(id).absolutePath)
            setOnErrorListener { source, _, _ -> if (source === recorder && !transitioning) finishRecording("Recorder stopped · saved") }
            setMaxFileSize(500L * 1024 * 1024)
            setOnInfoListener { source, what, _ ->
                if (source === recorder && !transitioning && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    finishRecording("File size limit reached · saved")
                }
            }
            prepare(); start()
        }
        segmentStart = SystemClock.elapsedRealtime()
        // During rollover the old ID stays visible until the new recorder is ready.
        activeId = id
        handler.post(tick)
    }
    private fun togglePause() {
        val current = recorder ?: return
        if (transitioning) return
        if (paused) { current.resume(); segmentStart = SystemClock.elapsedRealtime(); paused = false }
        else {
            current.pause(); accumulated += SystemClock.elapsedRealtime() - segmentStart
            elapsed = accumulated; paused = true
        }
    }
    private fun saveClip(): Boolean {
        val current = recorder ?: return false
        val id = activeId ?: return false
        handler.removeCallbacks(tick)
        elapsed = capturedTime()
        return try {
            current.stop()
            val store = Recordings(this)
            check(store.pending(id).renameTo(store.audio(id)))
            val title = sessionTitle + if (sessionLimit.enabled && sessionLimit.action == LimitAction.NEXT_CLIP) " · Part $part" else ""
            store.save(Clip(id, title, created, elapsed, marks))
            true
        } catch (_: Exception) {
            message = "Could not save this clip. Recording stopped."
            false
        } finally {
            runCatching { current.release() }; recorder = null
        }
    }
    private fun nextClip() {
        if (transitioning) return
        transitioning = true
        try {
            if (!saveClip()) { release(); stopSelf(); return }
            val savedPart = part
            part++
            // Keep the same microphone foreground service and wake lock, including with screen off.
            startClip()
            message = "Part $savedPart saved · next clip started"
        } catch (_: Exception) {
            message = "Clip saved, but the next recording could not start."
            release(); stopSelf()
        } finally { transitioning = false }
    }
    private fun finishRecording(successMessage: String = "Moment saved") {
        if (recorder == null || transitioning) return
        transitioning = true
        try { if (saveClip()) message = successMessage }
        finally { release(); transitioning = false; stopSelf() }
    }
    private fun release() {
        handler.removeCallbacks(tick)
        runCatching { recorder?.release() }; recorder = null
        lock?.let { if (it.isHeld) it.release() }; lock = null
        activeId = null; paused = false; amplitude = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() { if (recorder != null) finishRecording(); super.onDestroy() }
}
