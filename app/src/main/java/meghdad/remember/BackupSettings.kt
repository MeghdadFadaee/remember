package meghdad.remember

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BackupServer(val host: String, val port: Int, val user: String, val folder: String, val fingerprint: String) {
    fun validateConfiguration() {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' }) { "Enter a server address without ssh://" }
        require(port in 1..65535) { "Port must be 1–65535" }
        require(user.isNotBlank()) { "Enter a username" }
        require(folder.startsWith('/') && folder != "/" && folder.none { it == '\u0000' || it == '*' || it == '?' || it == '[' || it == '\\' } && folder.split('/').none { it == ".." }) { "Use an absolute backup folder without wildcards or .." }
        require(fingerprint.isEmpty() || fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{43}"))) { "Invalid server fingerprint" }
    }
    fun validate() {
        validateConfiguration()
        require(fingerprint.isNotEmpty()) { "Use Connect & trust on the Server page first" }
    }
    fun withRememberedTrust(previous: BackupServer): BackupServer = copy(
        fingerprint = if (host == previous.host && port == previous.port) previous.fingerprint else "")
    val identity: String get() = sha256("$host:$port\n$user\n$folder\n$fingerprint".toByteArray())
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Secrets are encrypted with a non-exportable Android Keystore wrapping key. */
class BackupSettings(context: Context) {
    val preferences = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    fun server() = BackupServer(preferences.getString("host", "")!!, preferences.getInt("port", 22),
        preferences.getString("user", "")!!, preferences.getString("folder", "/remember")!!,
        preferences.getString("fingerprint", "")!!)
    fun save(server: BackupServer) {
        server.validateConfiguration()
        val changed = server.identity != server().identity
        val editor = preferences.edit().putString("host", server.host).putInt("port", server.port).putString("user", server.user)
            .putString("folder", server.folder.trimEnd('/')).putString("fingerprint", server.fingerprint)
        if (changed) editor.putString("summary", "Ready when you are").putLong("last_success", 0)
        editor.apply()
    }
    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("remember-backup", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("remember-backup", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(bytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(bytes), Base64.NO_WRAP)
    }
    fun privateKey(): ByteArray {
        val data = Base64.decode(preferences.getString("key", null) ?: error("Generate a watch key first"), Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            doFinal(data.copyOfRange(12, data.size))
        }
    }
    fun publicKey(): String? = preferences.getString("public_key", null)
    fun generateKey(replace: Boolean = false): String {
        // Showing an existing public key must not pause an active transfer.
        if (!replace) publicKey()?.let { return it }
        check(BackupGate.lock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) { "Pause transfers before changing the watch key" }
        try {
            if (!replace) publicKey()?.let { return it }
            val pair = KeyPair.genKeyPair(SshCrypto.client(), KeyPair.ED25519)
            try {
                val privateBytes = ByteArrayOutputStream().also { pair.writeOpenSSHv1PrivateKey(it, null as ByteArray?) }.toByteArray()
                val public = ByteArrayOutputStream().also { pair.writePublicKey(it, "remember-watch") }.toString("UTF-8")
                try { check(preferences.edit().putString("key", encrypt(privateBytes)).putString("public_key", public).commit()) }
                finally { privateBytes.fill(0) }
                return public
            } finally { pair.dispose() }
        } finally { BackupGate.lock.unlock() }
    }
    val automatic get() = preferences.getBoolean("automatic", false)
    val charging get() = preferences.getBoolean("charging", true)
    val unmetered get() = preferences.getBoolean("unmetered", true)
    val hours get() = preferences.getInt("hours", 24)
    val attempts get() = preferences.getInt("attempts", 3)
    val timeout get() = preferences.getInt("timeout", 20)
    fun receipt(id: String): String? = preferences.getString("receipt_${server().identity}_$id", null)
    fun receipt(id: String, version: String) {
        check(preferences.edit().putString("receipt_${server().identity}_$id", version).commit()) { "Could not save backup status" }
    }
    fun status(id: String): String = preferences.getString("status_${server().identity}_$id", "Waiting")!!
    fun status(id: String, text: String) { preferences.edit().putString("status_${server().identity}_$id", text).apply() }
}
