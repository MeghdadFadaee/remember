package meghdad.remember

import com.jcraft.jsch.*
import java.security.MessageDigest
import java.util.Base64

object SshHostTrust {
    const val ALGORITHMS = "ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-ed25519"
    fun fingerprint(key: ByteArray): String = "SHA256:" + Base64.getEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

    /** An unapproved discovery key is always rejected before authentication. */
    fun repository(expected: String?, observed: (String) -> Unit = {}): HostKeyRepository = object : HostKeyRepository {
        override fun check(host: String?, key: ByteArray): Int {
            val actual = fingerprint(key)
            observed(actual)
            return when {
                expected == null -> HostKeyRepository.NOT_INCLUDED
                actual == expected -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
        }
        override fun add(key: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "Remember approved server"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }
}

class ServerTrustProbe(private val server: BackupServer, private val timeout: Int) : AutoCloseable {
    @Volatile private var connection: Session? = null
    @Volatile private var closed = false
    fun fetch(): String {
        server.validateConfiguration()
        check(!closed) { "Connection cancelled" }
        var discovered: String? = null
        val ssh = SshCrypto.client()
        ssh.hostKeyRepository = SshHostTrust.repository(null) { discovered = it }
        val session = ssh.getSession(server.user, server.host, server.port)
        connection = session
        try {
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("server_host_key", SshHostTrust.ALGORITHMS)
            session.timeout = timeout * 1000
            try { session.connect(timeout * 1000) }
            catch (error: JSchException) { if (discovered == null) throw error }
            check(!closed) { "Connection cancelled" }
            return discovered ?: error("Could not read the server identity")
        } finally { session.disconnect(); connection = null }
    }
    override fun close() { closed = true; connection?.disconnect() }
}
