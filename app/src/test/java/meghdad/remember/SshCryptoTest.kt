package meghdad.remember

import com.jcraft.jsch.KeyPair
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class SshCryptoTest {
    @Test fun ed25519OpenSshIdentityReloadsAndSignsWithTheAndroidCompatibleBackend() {
        val client = SshCrypto.client()
        val pair = KeyPair.genKeyPair(client, KeyPair.ED25519)
        val bytes = ByteArrayOutputStream().also { pair.writeOpenSSHv1PrivateKey(it, null as ByteArray?) }.toByteArray()
        try {
            assertTrue(bytes.toString(Charsets.UTF_8).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
            val restored = KeyPair.load(client, bytes, null)
            try {
                assertEquals(KeyPair.ED25519, restored.keyType)
                assertArrayEquals(pair.publicKeyBlob, restored.publicKeyBlob)
                val message = "backup authentication".toByteArray()
                val verifier = pair.verifier
                verifier.update(message)
                assertTrue(verifier.verify(restored.getSignature(message)))
            } finally { restored.dispose() }
        } finally { bytes.fill(0); pair.dispose() }
    }
}
