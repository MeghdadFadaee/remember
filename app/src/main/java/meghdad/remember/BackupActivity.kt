package meghdad.remember

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.*
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Backup navigation is independent of capture; opening it never stops RecordingService. */
class BackupActivity : Activity() {
    private val lime = Color.rgb(199, 234, 24)
    private val muted = Color.rgb(164, 175, 143)
    private lateinit var settings: BackupSettings
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private var page = "overview"
    private var floatingMenu: FloatingMenuController? = null
    companion object { const val EXTRA_PAGE = "backup_page" }
    private var backupDial: StatusDialView? = null
    private var waitingCount: TextView? = null
    private var busy = false
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var client: BackupTransport? = null
    @Volatile private var trustProbe: ServerTrustProbe? = null
    private var player: MediaPlayer? = null
    private var playbackReady = false
    private var resumed = false
    private var selected: RemoteClip? = null
    private var remoteClips: List<RemoteClip> = emptyList()
    private var serverDraft: BackupServer? = null
    private var pendingTrust: BackupServer? = null
    private var displayedPublicKey: String? = null
    private var publicKeyQr: com.google.zxing.common.BitMatrix? = null
    private var summary: TextView? = null
    private var progress: TextView? = null
    private var playbackButton: Button? = null
    private var seek: SeekBar? = null
    private val rows = mutableMapOf<String, TextView>()
    private var localClips: List<Clip> = emptyList()
    private val tick = object : Runnable {
        override fun run() {
            if (page == "overview") {
                summary?.text = settings.preferences.getString("summary", "Ready when you are")
                updateBackupDial()
            }
            if (page == "transfers") rows.forEach { (id, view) ->
                val clip = localClips.find { it.id == id }
                if (clip != null) view.text = transferState(clip)
            }
            player?.takeIf { playbackReady }?.let { p -> runCatching {
                seek?.progress = p.currentPosition
                playbackButton?.text = if (p.isPlaying) "Pause" else "Play"
                progress?.text = timeLabel(p.currentPosition.toLong())
            } }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = BackupSettings(this)
        page = intent.getStringExtra(EXTRA_PAGE)?.takeIf { it in listOf("overview", "server", "transfers", "remote", "automatic", "advanced") } ?: "overview"
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { back() }
        render()
    }
    override fun onResume() { super.onResume(); resumed = true; floatingMenu?.resume(); handler.post(tick) }
    override fun onPause() { resumed = false; floatingMenu?.pause(); handler.removeCallbacks(tick); if (playbackReady) player?.pause(); super.onPause() }
    override fun onDestroy() { floatingMenu?.dispose(); trustProbe?.close(); client?.close(); executor.shutdownNow(); player?.release(); super.onDestroy() }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        floatingMenu?.onTouch(event.actionMasked)
        return handled
    }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val handled = super.dispatchKeyEvent(event)
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode in listOf(
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_TAB)) floatingMenu?.interact()
        return handled
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchGenericMotionEvent(event)
        if (event.action == MotionEvent.ACTION_SCROLL) floatingMenu?.interact()
        return handled
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun text(value: String, size: Float = 12f, color: Int = Color.WHITE): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        setPadding(dp(3), dp(5), dp(3), dp(5)); body.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(label: String, action: () -> Unit): Button = WatchDesign.action(this, label, action = action).also { body.addView(it) }
    private fun primary(label: String, action: () -> Unit): Button = WatchDesign.action(this, label, true, action).also { body.addView(it) }
    private fun menu() {
        if (busy) { toast(getString(R.string.backup_wait_for_the_current_action_or_cancel_it)); return }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SCREEN, "menu"))
        finish()
    }
    private fun navigate(target: String) {
        if (busy) { toast(getString(R.string.backup_wait_for_the_current_action_or_cancel_it)); return }
        player?.release(); player = null
        playbackReady = false
        page = target; render()
    }
    private fun render() {
        val menuWasVisible = floatingMenu?.button?.visibility == View.VISIBLE
        floatingMenu?.dispose(); floatingMenu = null
        rows.clear(); summary = null; progress = null; playbackButton = null; seek = null; backupDial = null; waitingCount = null
        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; setBackgroundColor(Color.rgb(13, 17, 11)) }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            // Keep controls inside a round watch's usable area even on emulator images
            // that omit the round configuration qualifier.
            setPadding(dp(28), dp(14), dp(28), dp(24))
        }
        val layers = FrameLayout(this).apply { setBackgroundColor(WatchDesign.ink) }
        scroll.addView(body); layers.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        if (page !in listOf("trust", "trust_fingerprint")) {
            val menuButton = WatchDesign.menuDock(this) { menu() }
            layers.addView(menuButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            floatingMenu = FloatingMenuController(menuButton).also {
                if (resumed) { it.resume(); if (menuWasVisible) it.interact() }
            }
        }
        setContentView(layers)
        window.decorView.windowInsetsController?.hide(android.view.WindowInsets.Type.systemBars())
        scroll.descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        scroll.isFocusableInTouchMode = true
        scroll.setOnGenericMotionListener { _, event ->
            if (event.action == MotionEvent.ACTION_SCROLL) {
                scroll.smoothScrollBy(0, (-event.getAxisValue(MotionEvent.AXIS_SCROLL) * dp(35)).toInt()); true
            } else false
        }
        when (page) {
            "server" -> serverPage()
            "public_key" -> publicKeyPage()
            "public_key_qr" -> publicKeyQrPage()
            "trust" -> trustPage()
            "trust_fingerprint" -> {
                title(getString(R.string.backup_server_identity))
                text(pendingTrust?.fingerprint.orEmpty(), 12f, muted)
                button(getString(R.string.backup_back)) { navigate("trust") }
            }
            "transfers" -> transfersPage()
            "remote" -> remotePage()
            "playback" -> playbackPage()
            "automatic" -> automaticPage()
            "advanced" -> advancedPage()
            "licenses" -> { title(getString(R.string.backup_open_source_licenses)); text(listOf("JSch.txt", "Bouncy-Castle.txt", "Apache-2.0.txt", "ZXing-NOTICE.txt").joinToString("\n\n") { name -> assets.open("licenses/$name").bufferedReader().use { it.readText() } }, 10f, muted); button(getString(R.string.backup_back)) { navigate("advanced") } }
            else -> overview()
        }
        scroll.requestFocus()
    }
    private fun title(value: String) { WatchDesign.heading(body, value, "Remember") }
    private fun updateBackupDial() {
        val backed = localClips.count { settings.receipt(it.id) == backupVersion(it, Recordings(this).audio(it.id)) }
        backupDial?.update(backed.toString(), "backed up / ${localClips.size}", if (localClips.isEmpty()) 0f else backed.toFloat() / localClips.size)
        waitingCount?.text = getString(R.string.backup_waiting_count, localClips.size - backed)
    }
    private fun overview() {
        body.addView(WatchDesign.label(this, getString(R.string.backup_backup).uppercase(), 10f, lime).apply { letterSpacing = .16f })
        localClips = Recordings(this).list()
        backupDial = StatusDialView(this).also { body.addView(it, LinearLayout.LayoutParams(-1, dp(92))) }
        primary(getString(R.string.backup_back_up_now)) { startBackup() }
        waitingCount = text("", 11f, lime)
        updateBackupDial()
        summary = text(settings.preferences.getString("summary", "Ready when you are")!!, 11f, muted)
        val time = settings.preferences.getLong("last_success", 0)
        WatchDesign.card(body, getString(R.string.backup_last), if (time == 0L) getString(R.string.backup_none_completed) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time)), WatchIcon.BACKUP)
        val server = settings.server()
        WatchDesign.card(body, getString(R.string.backup_destination), server.host.ifBlank { getString(R.string.backup_no_destination) }, WatchIcon.SERVER)
        text(getString(if (settings.automatic) R.string.backup_automatic_on else R.string.backup_automatic_off), 10f, muted)
    }
    private fun field(label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        text(label, 11f, muted)
        return EditText(this).apply {
            setText(value); setTextColor(Color.WHITE); textSize = 12f; inputType = type
            contentDescription = label; setSelectAllOnFocus(true)
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun serverPage() {
        title(getString(R.string.backup_server))
        text("01  /  CONNECTION", 9f, lime)
        val server = serverDraft?.withRememberedTrust(settings.server()) ?: settings.server()
        val host = field(getString(R.string.backup_address), server.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = field(getString(R.string.backup_port), server.port.toString(), InputType.TYPE_CLASS_NUMBER)
        val user = field(getString(R.string.backup_username), server.user)
        val folder = field(getString(R.string.backup_backup_folder), server.folder, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        fun draft(): BackupServer = BackupServer(host.text.toString().trim(), port.text.toString().toIntOrNull() ?: 0,
            user.text.toString().trim(), folder.text.toString().trim(), "").withRememberedTrust(settings.server())
        val trustStatus = text(getString(if (server.fingerprint.isEmpty()) R.string.backup_server_untrusted else R.string.backup_server_trusted), 11f, lime)
        fun updateTrustStatus() { trustStatus.text = getString(if (draft().fingerprint.isEmpty()) R.string.backup_server_untrusted else R.string.backup_server_trusted) }
        val endpointWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateTrustStatus() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        }
        host.addTextChangedListener(endpointWatcher); port.addTextChangedListener(endpointWatcher)
        fun save(candidate: BackupServer): Boolean = persistServer(candidate).also { if (it) updateTrustStatus() }
        fun testConnection() { runRemote("Connecting…") { it.test(); "Connected. Backup folder is accessible." } }
        primary(getString(R.string.backup_connect_trust)) {
            val candidate = draft()
            runCatching { candidate.validateConfiguration() }.onSuccess {
                serverDraft = candidate
                runTask(getString(R.string.backup_reading_server_identity), {
                    check(BackupGate.lock.tryLock()) { "A backup is running. Pause transfers first." }
                    try {
                        ServerTrustProbe(candidate, settings.timeout).use { probe ->
                            trustProbe = probe; probe.fetch()
                        }
                    } finally { trustProbe = null; BackupGate.lock.unlock() }
                }) { found ->
                    if (draft().copy(fingerprint = "") != candidate.copy(fingerprint = "")) {
                        toast(getString(R.string.backup_server_details_changed))
                    } else if (candidate.fingerprint.isNotEmpty() && candidate.fingerprint != found) {
                        toast(getString(R.string.backup_server_key_changed))
                    } else if (candidate.fingerprint == found) {
                        if (save(candidate)) testConnection()
                    } else {
                        pendingTrust = candidate.copy(fingerprint = found)
                        page = "trust"; render()
                    }
                }
            }.onFailure { toast(it.message ?: "Check server settings") }
        }
        button(getString(R.string.backup_save_server)) {
            if (save(draft())) {
                if (settings.server().fingerprint.isNotEmpty()) BackupWork.schedule(this)
                menu()
            }
        }
        button(getString(R.string.backup_test_connection)) {
            val candidate = draft()
            runCatching { candidate.validate() }.onSuccess { if (save(candidate)) testConnection() }
                .onFailure { toast(it.message ?: "Use Connect & trust first") }
        }
        text("02  /  WATCH IDENTITY", 9f, lime)
        button(if (settings.publicKey() == null) "Create watch key" else "Show watch key") {
            runTask("Preparing key…", { settings.generateKey() }) { key ->
                serverDraft = draft()
                showPublicKey(key)
            }
        }
        if (settings.publicKey()?.startsWith("ssh-rsa ") == true) {
            button(getString(R.string.backup_replace_ed25519)) {
                AlertDialog.Builder(this).setTitle(R.string.backup_replace_ed25519)
                    .setMessage(R.string.backup_replace_key_message)
                    .setPositiveButton(R.string.backup_replace_key_confirm) { _, _ ->
                        runTask("Preparing key…", {
                            BackupWork.cancel(this)
                            settings.generateKey(replace = true)
                        }) { key -> render(); showPublicKey(key) }
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        progress = text("", 10f, muted)
        button(getString(R.string.backup_back)) { back() }
    }
    private fun persistServer(candidate: BackupServer): Boolean = try {
        candidate.validateConfiguration()
        check(!busy) { "Wait for the current action" }
        check(BackupGate.lock.tryLock()) { "Pause transfers before changing the server" }
        try {
            if (candidate.fingerprint.isEmpty()) BackupWork.cancel(this)
            settings.save(candidate)
        } finally { BackupGate.lock.unlock() }
        serverDraft = null; remoteClips = emptyList(); selected = null
        true
    } catch (error: Exception) { toast(error.message ?: "Check server settings"); false }
    private fun cancelTrust() { pendingTrust = null; page = "server"; render() }
    private fun trustPage() {
        val candidate = pendingTrust ?: run { page = "server"; serverPage(); return }
        text(getString(R.string.backup_trust_server_title), 17f, lime)
        text(getString(R.string.backup_trust_server_address, candidate.host, candidate.port), 14f)
        text(getString(R.string.backup_remember_server), 11f, muted)
        button(getString(R.string.backup_trust_server_confirm)) {
            if (persistServer(candidate)) {
                pendingTrust = null; page = "server"; render()
                if (settings.publicKey() != null) runRemote("Connecting…") { it.test(); "Connected. Backup folder is accessible." }
                else toast(getString(R.string.backup_server_trusted_create_key))
            }
        }.setTextColor(lime)
        button(getString(android.R.string.cancel)) { cancelTrust() }
        button(getString(R.string.backup_view_fingerprint)) { navigate("trust_fingerprint") }
    }
    private fun showPublicKey(key: String) {
        displayedPublicKey = key.trim()
        publicKeyQr = null
        navigate("public_key")
    }
    private fun publicKeyPage() {
        val key = displayedPublicKey ?: settings.publicKey()?.trim()
        if (key == null) { page = "server"; serverPage(); return }
        displayedPublicKey = key
        title(getString(R.string.backup_watch_public_key))
        primary(getString(R.string.backup_show_qr)) {
            runTask(getString(R.string.backup_show_qr), { runCatching { PublicKeyQr.encode(key) } }) { result ->
                if (page == "public_key" && displayedPublicKey == key) result.onSuccess { code ->
                    publicKeyQr = code; navigate("public_key_qr")
                }.onFailure { toast(getString(R.string.backup_public_key_qr_failed)) }
            }
        }
        text(getString(R.string.backup_public_key_instructions), 11f, muted)
        button(getString(R.string.backup_copy_on_watch)) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.backup_watch_public_key), key))
            toast(getString(R.string.backup_public_key_copied))
        }
        button(getString(R.string.backup_export_public_key)) {
            runCatching {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "remember-watch.pub"), 20)
            }.onFailure { toast(getString(R.string.backup_could_not_export_key)) }
        }
        text(key, 10f, muted).setTextIsSelectable(true)
        button(getString(R.string.backup_back)) { back() }
    }
    private fun publicKeyQrPage() {
        val code = publicKeyQr ?: run { page = "public_key"; publicKeyPage(); return }
        text(getString(R.string.backup_scan_with_phone), 10f, lime)
        val side = dp((resources.configuration.screenWidthDp * .62f).toInt())
        body.addView(PublicKeyQrView(this, code), LinearLayout.LayoutParams(side, side))
        text(getString(R.string.backup_qr_instructions), 10f, muted)
        button(getString(R.string.backup_back)) { back() }
    }
    @Deprecated("Framework document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20 && resultCode == RESULT_OK) runCatching {
            contentResolver.openOutputStream(data?.data ?: error("No file selected"))!!.use { it.write(settings.publicKey()!!.toByteArray()) }
        }.onSuccess { toast(getString(R.string.backup_public_key_exported)) }.onFailure { toast(getString(R.string.backup_could_not_export_key)) }
    }
    private fun transferState(clip: Clip): String = if (settings.receipt(clip.id) == backupVersion(clip, Recordings(this).audio(clip.id))) "Backed up" else settings.status(clip.id).let {
        if (it == "Backed up") "Waiting · changed" else it
    }
    private fun transfersPage() {
        title(getString(R.string.backup_transfers))
        val clips = Recordings(this).list()
        localClips = clips
        text("${clips.size} moments · one safe copy at a time", 10f, muted)
        WatchDesign.pair(body,
            WatchDesign.action(this, getString(R.string.backup_back_up_retry), true) { startBackup() },
            WatchDesign.action(this, getString(R.string.pause_all)) {
                BackupWork.cancel(this)
                clips.forEach { if (transferState(it) != "Backed up") settings.status(it.id, "Waiting") }
                toast(getString(R.string.backup_backups_paused_automatic_backup_is_off))
            })
        if (clips.isEmpty()) {
            body.addView(ImageView(this).apply { setImageDrawable(WatchGlyph(WatchIcon.TRANSFERS, lime)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(60), dp(72)))
            text(getString(R.string.backup_save_a_recording_to_begin), 14f)
        }
        clips.forEach { clip -> rows[clip.id] = WatchDesign.card(body, clip.title, transferState(clip), WatchIcon.TRANSFERS) }
        text(getString(R.string.backup_copies_stay_on_the_server_when_you_delete_local_recordings), 10f, muted)
        button(getString(R.string.backup_back)) { back() }
    }
    private fun startBackup() {
        runCatching { BackupWork.start(this) }.onSuccess { toast(getString(R.string.backup_backup_queued_opens_when_connected)) }
            .onFailure { toast(it.message ?: "Configure the server first") }
    }
    private fun remotePage() {
        WatchDesign.heading(body, getString(R.string.on_your_server), getString(R.string.backup_server_recordings))
        button(getString(R.string.backup_refresh)) {
            runTask("Loading recordings…", { withRemote { it.list() } }) { remoteClips = it; render() }
        }
        if (remoteClips.isEmpty()) {
            body.addView(ImageView(this).apply { setImageDrawable(WatchGlyph(WatchIcon.REMOTE, Color.rgb(143, 204, 217))); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(72), dp(88)))
            text(getString(R.string.backup_tap_refresh_to_browse_your_backups), 12f, muted)
        }
        remoteClips.forEach { remote ->
            val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(remote.clip.created))
            WatchDesign.card(body, remote.clip.title, "$date · ${timeLabel(remote.clip.duration)}", WatchIcon.PLAY) { selected = remote; navigate("playback") }
        }
        progress = text("", 10f, muted)
        button(getString(R.string.backup_back)) { back() }
    }
    private fun playbackPage() {
        val remote = selected ?: return
        title(getString(R.string.backup_server_playback))
        text(remote.clip.title, 17f)
        text("${timeLabel(remote.clip.duration)} · ${remote.clip.marks.size} bookmarks", 11f, muted)
        body.addView(StatusDialView(this).apply { update((remote.clip.duration / 60_000).toString(), "minutes on server", 1f) }, LinearLayout.LayoutParams(-1, dp(104)))
        progress = text(getString(R.string.backup_ready_to_download), 11f, lime)
        playbackButton = primary(getString(R.string.backup_download_play)) {
            if (player != null) { if (playbackReady) player!!.let { if (it.isPlaying) it.pause() else it.start() }; return@primary }
            val file = File(cacheDir, "server-playback.m4a")
            runTask("Preparing audio…", {
                require(StatFs(cacheDir.absolutePath).availableBytes > remote.bytes + 16 * 1024 * 1024) { "Not enough free space for playback" }
                var lastProgress = 0L
                withRemote { client -> client.download(remote, file) { sent, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgress > 500) {
                        lastProgress = now
                        handler.post { if (!isDestroyed && page == "playback") progress?.text = getString(R.string.backup_download_progress, sent * 100 / total) }
                    }
                } }
                file
            }) { downloaded ->
                runCatching {
                    player = MediaPlayer().apply {
                        setDataSource(downloaded.absolutePath)
                        setOnPreparedListener { p -> playbackReady = true; seek?.max = p.duration; if (resumed) p.start() }
                        setOnErrorListener { _, _, _ -> player?.release(); player = null; playbackReady = false; toast(getString(R.string.backup_could_not_play_audio)); true }
                        prepareAsync()
                    }
                }.onFailure { toast(getString(R.string.backup_could_not_play_audio)) }
            }
        }
        seek = SeekBar(this).apply {
            contentDescription = "Playback position"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser && playbackReady) runCatching { player?.seekTo(value) } }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            }); body.addView(this, LinearLayout.LayoutParams(-1, dp(40)))
        }
        remote.clip.marks.forEach { mark -> button("Bookmark · ${timeLabel(mark)}") { runCatching { player?.seekTo(mark.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) } } }
        text(getString(R.string.backup_audio_downloads_to_a_temporary_watch_cache_before_playing), 10f, muted)
        button(getString(R.string.backup_cancel_download)) { client?.close() }
        button(getString(R.string.backup_back)) { navigate("remote") }
    }
    private fun toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
        Switch(this).apply {
            background = WatchDesign.shape(this@BackupActivity)
            setPadding(dp(12), 0, dp(12), 0)
            thumbTintList = WatchDesign.switchTint()
            text = label; textSize = 12f; setTextColor(Color.WHITE); isChecked = value
            setOnCheckedChangeListener { _, checked -> change(checked) }
            body.addView(this, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(4); bottomMargin = dp(4) })
        }
    }
    private fun automaticPage() {
        WatchDesign.heading(body, getString(R.string.autopilot), getString(R.string.backup_automatic_backup))
        body.addView(StatusDialView(this).apply { update(settings.hours.toString(), if (settings.automatic) "hours / check" else "hours · off", if (settings.automatic) 1f else 0f) }, LinearLayout.LayoutParams(-1, dp(112)))
        toggle("Enable", settings.automatic) {
            settings.preferences.edit().putBoolean("automatic", it).apply()
            runCatching { BackupWork.schedule(this) }.onSuccess { render() }.onFailure { error ->
                settings.preferences.edit().putBoolean("automatic", false).apply(); toast(error.message ?: "Configure server first"); render()
            }
        }
        toggle("While charging", settings.charging) { updateOption("charging", it) }
        toggle("Unmetered only", settings.unmetered) { updateOption("unmetered", it) }
        text(getString(R.string.check_interval), 9f, lime)
        optionStrip(listOf(1, 6, 24), settings.hours, { if (it == 24) "Daily" else "${it}h" }) {
            settings.preferences.edit().putInt("hours", it).apply(); BackupWork.schedule(this); render()
        }
        text(getString(R.string.backup_runs_when_conditions_allow_the_watch_may_delay_background), 10f, muted)
        button(getString(R.string.backup_back)) { back() }
    }
    private fun updateOption(key: String, value: Boolean) { settings.preferences.edit().putBoolean(key, value).apply(); BackupWork.schedule(this) }
    private fun advancedPage() {
        WatchDesign.heading(body, getString(R.string.fine_tune), getString(R.string.backup_advanced))
        text(getString(R.string.retry_attempts), 9f, lime)
        optionStrip(listOf(1, 3, 5), settings.attempts, { it.toString() }) {
            settings.preferences.edit().putInt("attempts", it).apply(); render()
        }
        text(getString(R.string.connection_timeout), 9f, lime)
        optionStrip(listOf(20, 40, 60), settings.timeout, { "${it}s" }) {
            settings.preferences.edit().putInt("timeout", it).apply(); render()
        }
        text(getString(R.string.on_this_watch), 9f, lime)
        button(getString(R.string.backup_clear_playback_cache)) {
            player?.release(); player = null; playbackReady = false
            File(cacheDir, "server-playback.m4a").delete(); File(cacheDir, "server-playback.m4a.download").delete()
            toast(getString(R.string.backup_playback_cache_cleared))
        }
        text(getString(R.string.backup_one_transfer_at_a_time_interrupted_uploads_resume_and_are), 10f, muted)
        if (settings.server().fingerprint.isNotEmpty()) button(getString(R.string.backup_server_identity)) {
            val trusted = settings.server()
            AlertDialog.Builder(this).setTitle(R.string.backup_server_identity)
                .setMessage("${trusted.host}:${trusted.port}\n\n${trusted.fingerprint}")
                .setPositiveButton(R.string.backup_forget_trust) { _, _ ->
                    AlertDialog.Builder(this).setTitle(R.string.backup_forget_trust)
                        .setMessage(R.string.backup_forget_trust_message)
                        .setPositiveButton(R.string.backup_forget_trust) { _, _ ->
                            runTask("Pausing backups…", {
                                BackupWork.cancel(this)
                                check(BackupGate.lock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) { "Wait for transfers to stop" }
                                try { settings.save(trusted.copy(fingerprint = "")) } finally { BackupGate.lock.unlock() }
                            }) { remoteClips = emptyList(); selected = null; render(); toast(getString(R.string.backup_trust_forgotten)) }
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        button(getString(R.string.backup_open_source_licenses)) { navigate("licenses") }
        button(getString(R.string.backup_back)) { back() }
    }
    private fun optionStrip(values: List<Int>, selected: Int, label: (Int) -> String, action: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        values.forEach { value ->
            row.addView(WatchDesign.action(this, label(value), value == selected) { action(value) }.apply {
                isSelected = value == selected
                if (Build.VERSION.SDK_INT >= 30) stateDescription = if (isSelected) "Selected" else "Not selected"
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), dp(4), dp(2), dp(8)) })
        }
        body.addView(row)
    }
    private fun <T> runTask(message: String, task: () -> T, done: (T) -> Unit) {
        if (busy) { toast(getString(R.string.backup_an_action_is_already_running)); return }
        busy = true; progress?.text = message
        executor.execute {
            val result = runCatching { task() }
            handler.post {
                busy = false
                if (isDestroyed) return@post
                result.onSuccess { progress?.text = ""; done(it) }.onFailure {
                    val message = friendlyError(it)
                    progress?.text = message; toast(message)
                }
            }
        }
    }
    private fun <T> withRemote(action: (BackupTransport) -> T): T {
        check(BackupGate.lock.tryLock()) { "A backup is running. Pause transfers first." }
        try {
            return BackupTransport(this) { check(!isDestroyed && !Thread.currentThread().isInterrupted) { "Cancelled" } }.use {
                client = it; it.connect(); action(it)
            }
        } finally { client = null; BackupGate.lock.unlock() }
    }
    private fun runRemote(message: String, action: (BackupTransport) -> String) { runTask(message, { withRemote(action) }) { toast(it); progress?.text = it } }
    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is IllegalArgumentException || error is IllegalStateException -> message
            message.contains("HostKey", true) || message.contains("host key", true) -> "Server fingerprint does not match. Check it with your administrator."
            message.contains("Auth fail", true) -> "Key rejected. Authorize the watch public key on the server."
            else -> "Connection failed. Check address, connection, permissions, and backup folder."
        }
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun back() {
        when (page) {
            "overview", "server", "transfers", "remote", "automatic", "advanced" -> {
                if (busy) toast(getString(R.string.backup_wait_for_the_current_action_or_cancel_it)) else finish()
            }
            "trust" -> cancelTrust()
            "trust_fingerprint" -> navigate("trust")
            "public_key_qr" -> navigate("public_key")
            "public_key" -> navigate("server")
            else -> navigate(when (page) { "playback" -> "remote"; "licenses" -> "advanced"; else -> "overview" })
        }
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Framework back navigation")
    override fun onBackPressed() { back() }
}
