package meghdad.remember

import org.junit.Assert.*
import org.junit.Test

class BackupServerTest {
    private fun server() = BackupServer("example.test", 22, "backup", "/remember", "SHA256:" + "A".repeat(43))
    @Test fun rejectsUnsafeOrIncompleteServerDetails() {
        val invalid = listOf(server().copy(host = "ssh://example.test"), server().copy(port = 0),
            server().copy(user = ""), server().copy(folder = "/"), server().copy(folder = "/remember/../other"),
            server().copy(folder = "/remember/*"), server().copy(fingerprint = ""))
        invalid.forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) { candidate.validate() }
        }
        server().validate()
    }
    @Test fun backupReceiptsAreScopedToTheDestinationAndHostKey() {
        val original = server()
        assertNotEquals(original.identity, original.copy(folder = "/other").identity)
        assertNotEquals(original.identity, original.copy(user = "other").identity)
        assertNotEquals(original.identity, original.copy(fingerprint = "SHA256:" + "B".repeat(43)).identity)
        assertEquals(original.identity, original.copy().identity)
    }
    @Test fun savedConfigurationCanBeUntrustedButTransfersRequireApproval() {
        val pending = server().copy(fingerprint = "")
        pending.validateConfiguration()
        assertThrows(IllegalArgumentException::class.java) { pending.validate() }
    }
    @Test fun changingTheEndpointClearsTrustButChangingTheAccountDoesNot() {
        val trusted = server()
        assertEquals("", trusted.copy(host = "other.test").withRememberedTrust(trusted).fingerprint)
        assertEquals("", trusted.copy(port = 2222).withRememberedTrust(trusted).fingerprint)
        assertEquals(trusted.fingerprint, trusted.copy(user = "other", folder = "/other").withRememberedTrust(trusted).fingerprint)
    }
}
