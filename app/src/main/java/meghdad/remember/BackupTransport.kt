package meghdad.remember

import android.content.Context
import com.jcraft.jsch.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class RemoteClip(val clip: Clip, val bytes: Long, val digest: String)

fun clipManifest(clip: Clip, bytes: Long, digest: String): String = JSONObject()
    .put("version", 1).put("id", clip.id).put("title", clip.title).put("created", clip.created)
    .put("duration", clip.duration).put("marks", JSONArray(clip.marks)).put("bytes", bytes).put("sha256", digest).toString()

fun parseRemoteClip(text: String): RemoteClip {
    val json = JSONObject(text)
    require(json.getInt("version") == 1)
    val id = json.getString("id")
    require(id.matches(Regex("[a-fA-F0-9-]{36}")))
    val bytes = json.getLong("bytes")
    require(bytes in 1..500L * 1024 * 1024)
    val digest = json.getString("sha256")
    require(digest.matches(Regex("[a-f0-9]{64}")))
    val duration = json.getLong("duration")
    require(duration >= 0)
    val marks = json.optJSONArray("marks") ?: JSONArray()
    require(marks.length() <= 10000)
    return RemoteClip(Clip(id, json.getString("title").take(200), json.getLong("created"), duration,
        (0 until marks.length()).map { marks.getLong(it).coerceIn(0, duration) }), bytes, digest)
}

fun digestStream(input: InputStream, check: () -> Unit = {}, maximumBytes: Long = Long.MAX_VALUE): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        check()
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maximumBytes) { "Audio exceeds its expected size" }
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** One authenticated session, bounded buffers, and no remote shell commands. */
class BackupTransport(context: Context, private val check: () -> Unit = {}) : AutoCloseable {
    private val settings = BackupSettings(context)
    private val server = settings.server().also { it.validate() }
    private var session: Session? = null
    private var channel: ChannelSftp? = null
    @Volatile private var closed = false
    private val sftp get() = channel ?: error("Not connected")
    fun connect() {
        check()
        check(!closed) { "Connection cancelled" }
        val ssh = SshCrypto.client()
        ssh.hostKeyRepository = SshHostTrust.repository(server.fingerprint)
        val key = settings.privateKey()
        try { ssh.addIdentity("remember-watch", key, null, null) } finally { key.fill(0) }
        val connection = ssh.getSession(server.user, server.host, server.port)
        session = connection
        connection.apply {
            setConfig("StrictHostKeyChecking", "yes")
            // Keep the previous RSA/ECDSA host-key preference for existing fingerprint pins.
            // Ed25519 remains available for servers offering only that host-key type.
            setConfig("server_host_key", SshHostTrust.ALGORITHMS)
            setConfig("PreferredAuthentications", "publickey")
            setConfig("compression.s2c", "none"); setConfig("compression.c2s", "none")
            timeout = settings.timeout * 1000
            connect(settings.timeout * 1000)
        }
        check()
        check(!closed) { "Connection cancelled" }
        val files = connection.openChannel("sftp") as ChannelSftp
        channel = files
        files.apply {
            setBulkRequests(4)
            connect(settings.timeout * 1000)
            cd(server.folder)
        }
        check()
        check(!closed) { "Connection cancelled" }
    }
    fun test(): String { check(); return sftp.pwd() }
    private fun size(path: String): Long? = try { sftp.lstat(path).size } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e
    }
    fun upload(file: File, clip: Clip, stage: (String) -> Unit = {}, progress: (Long, Long) -> Unit): String {
        check()
        stage("Checking audio")
        val digest = file.inputStream().use { digestStream(it, check) }
        val manifest = clipManifest(clip, file.length(), digest)
        val remote = "${clip.id}.m4a"
        val partial = "$remote.upload"
        stage("Checking server copy")
        // Verify existing final audio too: metadata may have changed after an earlier backup.
        val finalMatches = size(remote) == file.length() && sftp.get(remote).use { digestStream(it, check, file.length()) } == digest
        if (!finalMatches) {
            var offset = size(partial) ?: 0L
            if (offset > file.length()) { sftp.rm(partial); offset = 0 }
            var transferred = offset
            val monitor = object : SftpProgressMonitor {
                override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit
                override fun count(count: Long): Boolean {
                    check(); transferred += count; progress(transferred, file.length()); return true
                }
                override fun end() = Unit
            }
            file.inputStream().use { input ->
                // JSch's InputStream RESUME overload skips the remote size itself.
                sftp.put(input, partial, monitor, ChannelSftp.RESUME)
            }
            check()
            stage("Verifying backup")
            if (sftp.get(partial).use { digestStream(it, check, file.length()) } != digest) {
                sftp.rm(partial)
                error("Upload verification failed. Retry the recording.")
            }
            if (size(remote) != null) sftp.rm(remote)
            sftp.rename(partial, remote)
        }
        check()
        stage("Updating details")
        manifest.byteInputStream().use { sftp.put(it, "${clip.id}.json.upload", ChannelSftp.OVERWRITE) }
        if (size("${clip.id}.json") != null) sftp.rm("${clip.id}.json")
        // Metadata is the commit marker; remote browsers ignore unfinished uploads.
        sftp.rename("${clip.id}.json.upload", "${clip.id}.json")
        return sha256(manifest.toByteArray())
    }
    fun list(): List<RemoteClip> {
        val entries = mutableListOf<ChannelSftp.LsEntry>()
        try {
            sftp.ls("*.json", ChannelSftp.LsEntrySelector { entry ->
                entries.add(entry)
                if (entries.size >= 1000) ChannelSftp.LsEntrySelector.BREAK else ChannelSftp.LsEntrySelector.CONTINUE
            })
        } catch (e: SftpException) { if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e }
        return entries.mapNotNull { item ->
            check()
            if (!item.filename.matches(Regex("[a-fA-F0-9-]{36}\\.json")) || item.attrs.size > 128 * 1024) return@mapNotNull null
            runCatching {
                val remote = parseRemoteClip(sftp.get(item.filename).use { it.readBytesLimited(128 * 1024).toString(Charsets.UTF_8) })
                require(item.filename == "${remote.clip.id}.json")
                require(size("${remote.clip.id}.m4a") == remote.bytes)
                remote
            }.getOrNull()
        }.sortedByDescending { it.clip.created }
    }
    fun download(remote: RemoteClip, destination: File, progress: (Long, Long) -> Unit) {
        val temporary = File(destination.parentFile, "${destination.name}.download")
        try {
            sftp.get("${remote.clip.id}.m4a").use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= remote.bytes) { "Remote audio size changed" }
                    output.write(buffer, 0, count); progress(total, remote.bytes)
                }
                require(total == remote.bytes) { "Incomplete download" }
            } }
            require(temporary.inputStream().use { digestStream(it, check) } == remote.digest) { "Audio verification failed" }
            check(destination.exists().not() || destination.delete())
            check(temporary.renameTo(destination))
        } finally { temporary.delete() }
    }
    override fun close() { closed = true; channel?.disconnect(); session?.disconnect(); channel = null; session = null }
}

private fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "Metadata is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
