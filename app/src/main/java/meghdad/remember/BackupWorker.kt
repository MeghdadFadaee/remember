package meghdad.remember

import android.content.Context
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

object BackupGate { val lock = ReentrantLock() }

fun backupVersion(clip: Clip, file: File): String = sha256(
    "${clip.id}\n${clip.title}\n${clip.created}\n${clip.duration}\n${clip.marks}\n${file.length()}\n${file.lastModified()}".toByteArray())

object BackupWork {
    private const val MANUAL = 71
    private const val AUTOMATIC = 72
    fun start(context: Context) {
        val settings = BackupSettings(context)
        settings.server().validate()
        require(settings.publicKey() != null) { "Generate and authorize the watch key first" }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(MANUAL) != null) return
        settings.preferences.edit().putInt("attempt_71", 0).putString("summary", "Waiting for connection").apply()
        check(scheduler.schedule(JobInfo.Builder(MANUAL, ComponentName(context, BackupWorker::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()) == JobScheduler.RESULT_SUCCESS) { "Could not queue backup" }
    }
    fun cancel(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancel(MANUAL); scheduler.cancel(AUTOMATIC)
        BackupSettings(context).preferences.edit().putBoolean("automatic", false).putString("summary", "Backups paused").apply()
    }
    fun schedule(context: Context) {
        val settings = BackupSettings(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!settings.automatic) { scheduler.cancel(AUTOMATIC); return }
        settings.server().validate()
        require(settings.publicKey() != null) { "Generate and authorize the watch key first" }
        check(scheduler.schedule(JobInfo.Builder(AUTOMATIC, ComponentName(context, BackupWorker::class.java))
            .setPeriodic(TimeUnit.HOURS.toMillis(settings.hours.toLong()))
            .setRequiresCharging(settings.charging).setPersisted(true)
            .setRequiredNetworkType(if (settings.unmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()) == JobScheduler.RESULT_SUCCESS) { "Could not schedule backups" }
    }
}

class BackupWorker : JobService() {
    private data class Running(val runner: BackupRunner, val thread: Thread)
    private val running = mutableMapOf<Int, Running>()
    override fun onStartJob(params: JobParameters): Boolean {
        val runner = BackupRunner(applicationContext)
        val thread = Thread {
            val retry = runner.performBackup(params.jobId)
            android.os.Handler(mainLooper).post {
                if (running[params.jobId]?.runner === runner) {
                    running.remove(params.jobId)
                    if (!runner.stopped) jobFinished(params, retry)
                }
            }
        }
        running[params.jobId] = Running(runner, thread)
        thread.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.let { it.runner.stop(); it.thread.interrupt() }
        return params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP
    }
    override fun onDestroy() {
        running.values.forEach { it.runner.stop(); it.thread.interrupt() }; running.clear()
        super.onDestroy()
    }
}

private class BackupRunner(private val applicationContext: Context) {
    @Volatile var stopped = false
        private set
    fun stop() { stopped = true; transport?.close() }
    @Volatile private var transport: BackupTransport? = null
    private fun ensureRunning() {
        check(!stopped && !Thread.currentThread().isInterrupted) { "Backup paused" }
    }
    fun performBackup(jobId: Int): Boolean {
        if (!BackupGate.lock.tryLock()) return true
        val settings = BackupSettings(applicationContext)
        var current: String? = null
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            settings.server().validate()
            val store = Recordings(applicationContext)
            val clips = store.list().filter { settings.receipt(it.id) != backupVersion(it, store.audio(it.id)) }
            if (clips.isEmpty()) {
                settings.preferences.edit().putInt("attempt_$jobId", 0).putString("summary", "Everything is backed up").apply()
                return false
            }
            settings.preferences.edit().putString("summary", "Connecting…").apply()
            BackupTransport(applicationContext) {
                ensureRunning()
                check(android.os.SystemClock.elapsedRealtime() - start < 8 * 60_000) { "Backup will continue in another attempt" }
            }.use { client ->
                transport = client
                client.connect()
                for (clip in clips) {
                    ensureRunning()
                    current = clip.id
                    val file = store.audio(clip.id)
                    if (!file.exists()) continue
                    val version = backupVersion(clip, file)
                    settings.status(clip.id, "Uploading")
                    var lastProgress = 0L
                    client.upload(file, clip, stage = { stage ->
                        settings.status(clip.id, stage)
                        settings.preferences.edit().putString("summary", stage).apply()
                    }) { sent, total ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastProgress > 500) {
                            val percentage = (sent * 100 / total.coerceAtLeast(1)).coerceIn(0, 100)
                            settings.status(clip.id, "Uploading · $percentage%")
                            settings.preferences.edit().putString("summary", "Uploading · $percentage%").apply()
                            lastProgress = now
                        }
                    }
                    // A simultaneous rename should be uploaded again, not silently acknowledged.
                    settings.receipt(clip.id, version)
                    settings.status(clip.id, "Backed up")
                }
            }
            settings.preferences.edit().putInt("attempt_$jobId", 0).putLong("last_success", System.currentTimeMillis()).putString("summary", "Backup complete").apply()
            return false
        } catch (_: Exception) {
            val message = if (stopped) "Waiting" else "Failed · check connection and server settings"
            current?.let { settings.status(it, message) }
            settings.preferences.edit().putString("summary", if (stopped) "Backup paused" else message).apply()
            val attempts = settings.preferences.getInt("attempt_$jobId", 0) + 1
            val retry = !stopped && attempts < settings.attempts
            settings.preferences.edit().putInt("attempt_$jobId", if (retry) attempts else 0).apply()
            return retry
        } finally { transport?.close(); transport = null; BackupGate.lock.unlock() }
    }
}
