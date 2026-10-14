package meghdad.remember

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.*
import org.junit.Test

class SshHostTrustTest {
    @Test fun discoveryReportsTheFingerprintButNeverApprovesTheKey() {
        val key = byteArrayOf(1, 2, 3)
        var observed: String? = null
        val repository = SshHostTrust.repository(null) { observed = it }
        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("server.test", key))
        assertEquals(SshHostTrust.fingerprint(key), observed)
        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("server.test", key))
    }
    @Test fun authenticationAcceptsOnlyThePreviouslyApprovedKey() {
        val original = byteArrayOf(1, 2, 3)
        val repository = SshHostTrust.repository(SshHostTrust.fingerprint(original))
        assertEquals(HostKeyRepository.OK, repository.check("server.test", original))
        assertEquals(HostKeyRepository.CHANGED, repository.check("server.test", byteArrayOf(4, 5, 6)))
    }
}
