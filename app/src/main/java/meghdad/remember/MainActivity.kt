package meghdad.remember

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.*
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class MainActivity : Activity() {
    private val ink = Color.rgb(13, 17, 11)
    private val mint = Color.rgb(199, 234, 24)
    private val muted = Color.rgb(164, 175, 143)
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var store: Recordings
    private var screen = "home"
    private var clip: Clip? = null
    private var player: MediaPlayer? = null
    private var minutes: MinuteCounterView? = null
    private var motionAllowed = false
    private lateinit var dial: SpatialDialDrawable
    private var downX = 0f
    private var downY = 0f
    private var status: TextView? = null
    private var pause: Button? = null
    private var play: Button? = null
    private var seek: SeekBar? = null
    private var active = false
    private lateinit var preferences: RecordingPreferences
    private var limitPicker: NumberPicker? = null
    private val storageWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var storageRequest = 0
    private var storageBusy = false
    private var lastStorageRead = 0L
    private var storageChart: StorageChartView? = null
    private var storageAvailable: TextView? = null
    private var storageApp: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val now = RecordingService.activeId != null
            if (now != active) { active = now; if (screen == "home") render() }
            if (now && screen == "home") {
                updateDial(RecordingService.elapsed)
                status?.text = if (RecordingService.paused) "PAUSED" else if (RecordingService.part > 1) "●  RECORDING · ${RecordingService.part}" else "●  RECORDING"
                pause?.text = if (RecordingService.paused) "Resume" else "Pause"
            }
            player?.let { p ->
                runCatching {
                    updateDial(p.currentPosition.toLong())
                    seek?.progress = p.currentPosition
                    play?.text = if (p.isPlaying) "Pause" else "Play"
                }
            }
            if (screen == "storage" && SystemClock.elapsedRealtime() - lastStorageRead > 5000 && !storageBusy) loadStorage()
            RecordingService.message?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show(); RecordingService.message = null }
            handler.postDelayed(this, 150)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { navigateBack() }
        preferences = RecordingPreferences(this)
        store = Recordings(this)
        store.recover()
        active = RecordingService.activeId != null
        render()
    }
    override fun onResume() {
        super.onResume()
        motionAllowed = android.animation.ValueAnimator.areAnimatorsEnabled() && !getSystemService(PowerManager::class.java).isPowerSaveMode
        minutes?.setFloating(motionAllowed && !RecordingService.paused && minutes?.visibility == View.VISIBLE)
        handler.post(ticker)
    }
    override fun onPause() { handler.removeCallbacks(ticker); minutes?.setFloating(false); player?.pause(); super.onPause() }
    override fun onDestroy() { releasePlayer(); storageWorker.shutdown(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun background(color: Int, radius: Float = 28f) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat() }
    private fun text(value: String, size: Float, color: Int = Color.WHITE): TextView {
        return TextView(this).apply {
            text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            body.addView(this)
        }
    }
    private fun button(label: String, color: Int = Color.rgb(43, 52, 32), action: () -> Unit): Button {
        return Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false; setTextColor(Color.WHITE)
            background = if (color == mint) background(Color.argb(190, 24, 31, 16)).apply {
                setStroke(dp(1), Color.argb(140, 199, 234, 24))
            } else background(Color.argb(205, Color.red(color), Color.green(color), Color.blue(color)))
            minHeight = 0; minimumHeight = 0
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CONFIRM); action() }
            body.addView(this)
        }
    }
    private fun render() {
        minutes?.setFloating(false)
        minutes = null; status = null; pause = null; play = null; seek = null; limitPicker = null
        storageRequest++; storageChart = null; storageAvailable = null; storageApp = null
        dial = SpatialDialDrawable()
        val layers = FrameLayout(this).apply { background = dial }
        minutes = MinuteCounterView(this).also { layers.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        scroll = ScrollView(this).apply { setBackgroundColor(Color.TRANSPARENT); isFillViewport = true; isVerticalScrollBarEnabled = false }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            val round = resources.configuration.isScreenRound
            setPadding(dp(if (round) 30 else 20), dp(16), dp(if (round) 30 else 20), dp(20))
        }
        scroll.addView(body)
        layers.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(layers)
        window.decorView.windowInsetsController?.hide(WindowInsets.Type.systemBars())
        window.decorView.windowInsetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        scroll.setOnTouchListener { _, event ->
            val edge = screen in listOf("home", "detail") && event.x > scroll.width * .82f && event.y in scroll.height * .23f..scroll.height * .77f
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    edge && (active || screen == "detail" || screen == "home")
                }
                MotionEvent.ACTION_MOVE -> edge && (active || screen == "detail" || screen == "home")
                MotionEvent.ACTION_UP -> {
                    if (edge && kotlin.math.abs(event.x - downX) < dp(20) && kotlin.math.abs(event.y - downY) < dp(20)) {
                        when {
                            active && screen == "home" -> { command(RecordingService.MARK); scroll.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
                            screen == "detail" -> seekFromEdge(event.x, event.y)
                            screen == "home" -> { screen = "library"; render() }
                        }
                        scroll.performClick()
                        true
                    } else false
                }
                else -> false
            }
        }
        scroll.isFocusableInTouchMode = true; scroll.requestFocus()
        scroll.setOnGenericMotionListener { _, event ->
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val movement = event.getAxisValue(MotionEvent.AXIS_SCROLL)
                if (screen == "limit_minutes" && limitPicker != null) {
                    limitPicker?.let { picker -> picker.value = (picker.value + if (movement > 0) -1 else 1).coerceIn(picker.minValue, picker.maxValue) }
                } else scroll.smoothScrollBy(0, (-movement * dp(35)).toInt())
                true
            } else false
        }
        when {
            screen == "settings" -> settings()
            screen == "storage" -> storage()
            screen == "limits" -> limits()
            screen == "limit_minutes" -> limitMinutes()
            screen == "limit_action" -> limitAction()
            active && screen == "home" -> recording()
            screen == "library" -> library()
            screen == "detail" -> detail()
            else -> home()
        }
    }
    private fun counter(elapsed: Long, saved: Boolean = false) {
        minutes?.update(elapsed, saved)
        // Foreground breathing room; the numeral itself lives in the full-screen layer below.
        body.addView(Space(this), LinearLayout.LayoutParams(-1, dp(62)))
    }
    private fun updateDial(elapsed: Long) {
        dial.elapsed = elapsed
        dial.mode = when {
            screen !in listOf("home", "detail") -> DialMode.LIBRARY
            active && RecordingService.paused -> DialMode.PAUSED
            active -> DialMode.RECORDING
            screen == "detail" -> DialMode.PLAYBACK
            screen == "library" -> DialMode.LIBRARY
            else -> DialMode.READY
        }
        dial.duration = clip?.duration ?: 0L
        dial.marks = if (active) RecordingService.marks else if (screen == "detail") clip?.marks.orEmpty() else emptyList()
        // Logarithmic peak scaling makes quiet speech visible without inventing a waveform.
        val peak = RecordingService.amplitude.coerceAtLeast(0)
        dial.level = if (peak == 0 || RecordingService.paused) 0f else (ln(1f + peak) / ln(32768f)).coerceIn(0f, 1f)
        minutes?.visibility = if (dial.mode == DialMode.LIBRARY) View.GONE else View.VISIBLE
        minutes?.update(elapsed, dial.mode == DialMode.READY)
        minutes?.setFloating(motionAllowed && dial.mode != DialMode.PAUSED && dial.mode != DialMode.LIBRARY)
        dial.invalidateSelf()
    }
    private fun seekFromEdge(x: Float, y: Float) {
        val item = clip ?: return
        val angle = Math.toDegrees(atan2((y - scroll.height / 2f).toDouble(), (x - scroll.width / 2f).toDouble()))
        val fraction = ((angle + 32) / 64).coerceIn(0.0, 1.0)
        if (player == null) togglePlayback(item)
        player?.seekTo((fraction * item.duration).toInt())
        scroll.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
    private fun home() {
        val clips = store.list()
        val total = clips.sumOf { it.duration }
        text("REMEMBER", 10f).apply { letterSpacing = .2f }
        counter(total, true)
        dial.freeStorage = (filesDir.usableSpace.toDouble() / filesDir.totalSpace.coerceAtLeast(1)).toFloat()
        updateDial(total)
        // Saved total has no running seconds; the sweep starts when capture starts.
        dial.elapsed = 0
        button("●  Record", mint) { startRecording() }.contentDescription = "Start recording"
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val moments = button("Moments · ${clips.size}") { screen = "library"; render() }.apply { textSize = 11f }
        val settings = button("Settings") { screen = "settings"; render() }.apply { textSize = 11f }
        body.removeView(moments); body.removeView(settings)
        row.addView(moments, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(3) })
        row.addView(settings, LinearLayout.LayoutParams(dp(64), dp(44)).apply { marginStart = dp(3) })
        body.addView(row, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(3) })
    }
    private fun recording() {
        status = text(if (RecordingService.paused) "PAUSED" else "●  RECORDING", 10f, mint)
        counter(RecordingService.elapsed)
        updateDial(RecordingService.elapsed)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pause = button(if (RecordingService.paused) "Resume" else "Pause") { command(RecordingService.PAUSE) }
        val mark = button("Mark") {
            command(RecordingService.MARK)
            Toast.makeText(this, "Moment marked", Toast.LENGTH_SHORT).show()
        }
        body.removeView(pause); body.removeView(mark)
        row.addView(pause, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(3) })
        row.addView(mark, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(3) })
        body.addView(row)
        button("■  Save", mint) { command(RecordingService.SAVE) }
        button("Settings") { screen = "settings"; render() }
    }
    private fun settings() {
        updateDial(0)
        text("SETTINGS", 10f, mint)
        text("Make it yours.", 17f)
        button("Storage") { screen = "storage"; render() }.apply { layoutParams.height = dp(56) }
        button("Recording limit") { screen = "limits"; render() }.apply { layoutParams.height = dp(56) }
        button("Backup") { startActivity(Intent(this, BackupActivity::class.java)) }.apply { layoutParams.height = dp(56) }
        button(if (active) "Back to recording" else "Back") { screen = "home"; render() }
    }
    private fun storage() {
        updateDial(0)
        body.setPadding(dp(12), dp(8), dp(12), dp(20))
        text("STORAGE", 10f, mint)
        storageChart = StorageChartView(this).also {
            // Center a large chart in the initial viewport; details and actions follow below it.
            val chartHeight = (resources.displayMetrics.heightPixels - dp(52)).coerceAtLeast(dp(140))
            body.addView(it, LinearLayout.LayoutParams(-1, chartHeight))
        }
        storageAvailable = text("● Available · …", 11f, mint)
        storageApp = text("● Remember · …", 11f)
        button("Refresh") { loadStorage() }
        button("Back") { screen = "settings"; render() }
        loadStorage()
    }
    private fun loadStorage() {
        if (storageBusy || storageWorker.isShutdown) return
        storageBusy = true; lastStorageRead = SystemClock.elapsedRealtime()
        val request = storageRequest
        storageWorker.execute {
            val result = runCatching { readStorageUsage(applicationContext) }
            handler.post {
                storageBusy = false
                if (isDestroyed || screen != "storage" || request != storageRequest) return@post
                result.onSuccess { usage ->
                    fun size(bytes: Long) = android.text.format.Formatter.formatShortFileSize(this, bytes)
                    storageChart?.update(usage)
                    storageAvailable?.text = "● Available · ${size(usage.available)}"
                    storageApp?.text = "● Remember · ${if (usage.estimated) "≈ " else ""}${size(usage.appUsed)}"
                }.onFailure {
                    storageChart?.showFailure()
                    storageAvailable?.text = "Could not read storage"
                    storageApp?.text = "Tap Refresh to retry"
                }
            }
        }
    }
    private fun limits() {
        updateDial(0)
        val limit = preferences.read()
        text("RECORDING LIMIT", 10f, mint)
        val toggle = Switch(this).apply {
            text = "Minute limit"; textSize = 12f; setTextColor(Color.WHITE)
            isChecked = limit.enabled
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(mint, Color.rgb(116, 129, 96))
            )
            setPadding(dp(5), 0, dp(5), 0)
            body.addView(this, LinearLayout.LayoutParams(-1, dp(44)))
            setOnCheckedChangeListener { _, checked -> preferences.write(preferences.read().copy(enabled = checked)); render() }
        }
        text(if (toggle.isChecked) "Enabled" else "Unlimited recording", 10f, muted)
        button("Length · ${limit.minutes} min") { screen = "limit_minutes"; render() }
        button("At the limit\n${if (limit.action == LimitAction.STOP) "Save & stop" else "Save & start next"}") {
            screen = "limit_action"; render()
        }.apply { layoutParams.height = dp(54); textSize = 11f }
        text("Applies to your next recording session.\nPaused time doesn't count.", 10f, muted)
        button("Back") { screen = "settings"; render() }
    }
    private fun limitMinutes() {
        updateDial(0)
        text("CLIP LENGTH", 10f, mint)
        text("Minutes", 13f)
        limitPicker = NumberPicker(this).apply {
            minValue = 1; maxValue = 1440; value = preferences.read().minutes; wrapSelectorWheel = false
            contentDescription = "Recording limit in minutes"
            setTextColor(Color.WHITE); setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics))
            body.addView(this, LinearLayout.LayoutParams(dp(116), dp(100)))
        }
        button("Save", mint) {
            limitPicker?.clearFocus()
            preferences.write(preferences.read().copy(minutes = limitPicker?.value ?: 15))
            screen = "limits"; render()
        }
        button("Cancel") { screen = "limits"; render() }
    }
    private fun limitAction() {
        updateDial(0)
        text("AT THE LIMIT", 10f, mint)
        val action = preferences.read().action
        button("${if (action == LimitAction.STOP) "✓ " else ""}Save & stop", if (action == LimitAction.STOP) mint else Color.rgb(43, 52, 32)) {
            preferences.write(preferences.read().copy(action = LimitAction.STOP)); render()
        }
        button("${if (action == LimitAction.NEXT_CLIP) "✓ " else ""}Save & start next", if (action == LimitAction.NEXT_CLIP) mint else Color.rgb(43, 52, 32)) {
            preferences.write(preferences.read().copy(action = LimitAction.NEXT_CLIP)); render()
        }
        text("New clips start at 0.\nA brief gap can occur between clips.", 11f, muted)
        button("Back") { screen = "limits"; render() }
    }
    private fun library() {
        updateDial(0)
        text("YOUR MOMENTS", 10f, mint)
        val clips = store.list()
        if (clips.isEmpty()) {
            text("A little space\nfor big ideas.", 20f)
            text("Record a thought, a song,\nor the sounds around you.", 12f, muted)
            button("Record a moment", mint) { screen = "home"; render(); startRecording() }
        } else {
            text("${clips.size} saved", 12f, muted)
            clips.forEach { item ->
                button("${item.title}\n${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(item.created))}  ·  ${timeLabel(item.duration)}") {
                    clip = item; screen = "detail"; render()
                }.apply {
                    textSize = 11f
                    layoutParams.height = dp(54)
                    maxLines = 2
                }
            }
        }
        button("Back") { screen = "home"; render() }
    }
    private fun detail() {
        val item = clip ?: run { screen = "library"; render(); return }
        text("SAVED MOMENT", 10f, mint)
        text(item.title, 16f)
        counter(player?.currentPosition?.toLong() ?: 0)
        updateDial(player?.currentPosition?.toLong() ?: 0)
        seek = SeekBar(this).apply {
            max = item.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(mint)
            thumbTintList = android.content.res.ColorStateList.valueOf(mint)
            contentDescription = "Playback position"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { if (user) player?.seekTo(value) }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
            body.addView(this, LinearLayout.LayoutParams(-1, dp(38)))
        }
        play = button("Play", mint) { togglePlayback(item) }
        text("${timeLabel(item.duration)}  ·  AAC audio", 10f, muted)
        item.marks.forEachIndexed { index, position ->
            button("Mark ${index + 1}  ·  ${timeLabel(position)}") {
                if (player == null) togglePlayback(item)
                player?.seekTo(position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        }
        button("Rename") {
            val input = EditText(this).apply { setText(item.title); selectAll(); maxLines = 1 }
            AlertDialog.Builder(this).setTitle("Name this moment").setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val name = input.text.toString().trim().take(80)
                    if (name.isNotEmpty()) {
                        val updated = item.copy(title = name)
                        runCatching { store.save(updated); clip = updated; render() }.onFailure { showError("Could not rename moment") }
                    }
                }.setNegativeButton("Cancel", null).show()
        }
        button("Delete", Color.rgb(67, 32, 38)) {
            AlertDialog.Builder(this).setTitle("Delete this moment?").setMessage("This recording will be permanently removed.")
                .setPositiveButton("Delete") { _, _ ->
                    releasePlayer()
                    runCatching { store.delete(item); screen = "library"; render() }.onFailure { showError("Could not delete moment") }
                }.setNegativeButton("Keep", null).show()
        }
        button("Back") { releasePlayer(); screen = "library"; render() }
    }
    private fun togglePlayback(item: Clip) {
        try {
            if (player == null) {
                player = MediaPlayer().apply {
                    setDataSource(store.audio(item.id).absolutePath)
                    prepare()
                    setOnCompletionListener { play?.text = "Play"; seekTo(0); updateDial(0) }
                    setOnErrorListener { _, _, _ -> releasePlayer(); showError("Could not play this moment"); true }
                }
            }
            player?.let { if (it.isPlaying) it.pause() else it.start() }
        } catch (_: Exception) { releasePlayer(); showError("Could not play this moment") }
    }
    private fun releasePlayer() { player?.release(); player = null }
    private fun showError(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun startRecording() {
        releasePlayer()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10); return
        }
        command(RecordingService.START)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }
    }
    private fun command(action: String) {
        try {
            val intent = Intent(this, RecordingService::class.java).setAction(action)
            if (action == RecordingService.START) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) { showError("Could not start recording. Check microphone permission.") }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
            else AlertDialog.Builder(this).setTitle("Microphone access needed")
                .setMessage("Allow microphone access to capture the sounds around you.")
                .setPositiveButton("Settings") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
                .setNegativeButton("Later", null).show()
        }
    }
    // API 31–32 fallback; API 33+ uses the registered OnBackInvokedCallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Framework back navigation")
    override fun onBackPressed() { navigateBack() }
    private fun navigateBack() {
        if (screen != "home") {
            releasePlayer()
            screen = when (screen) {
                "detail" -> "library"
                "storage", "limits" -> "settings"
                "limit_minutes", "limit_action" -> "limits"
                else -> "home"
            }
            render()
        } else finish()
    }
}
