package meghdad.remember

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast

/** A visible, tile-host-only entry point for microphone permission and foreground capture. */
class TileRecordingActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var permissionPending = false
    private var dispatched = false
    private var deadline = 0L
    private val awaitRecorder = object : Runnable {
        override fun run() {
            if (!resumed) return
            when {
                RecordingService.activeId != null -> finish()
                SystemClock.elapsedRealtime() >= deadline -> fail()
                else -> handler.postDelayed(this, 50)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionPending = savedInstanceState?.getBoolean("permissionPending") ?: false
        dispatched = savedInstanceState?.getBoolean("dispatched") ?: false
        deadline = savedInstanceState?.getLong("deadline") ?: 0
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        window.decorView.post { startWhenVisible() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) startWhenVisible()
    }

    private fun startWhenVisible() {
        if (!resumed || isFinishing || !hasWindowFocus()) return
        if (RecordingService.activeId != null) { finish(); return }
        if (permissionPending) return
        if (dispatched) { handler.removeCallbacks(awaitRecorder); handler.post(awaitRecorder); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        try {
            // Start only while this activity is visible; a background TileService cannot use mic.
            dispatched = true
            deadline = SystemClock.elapsedRealtime() + 5000
            startForegroundService(Intent(this, RecordingService::class.java).setAction(RecordingService.START))
            handler.post(awaitRecorder)
        } catch (_: Exception) { fail() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        permissionPending = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startWhenVisible()
        else {
            Toast.makeText(this, R.string.tile_microphone_needed, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permissionPending", permissionPending)
        outState.putBoolean("dispatched", dispatched)
        outState.putLong("deadline", deadline)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(awaitRecorder)
        super.onPause()
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun fail() {
        Toast.makeText(this, R.string.tile_start_failed, Toast.LENGTH_LONG).show()
        finish()
    }
}
