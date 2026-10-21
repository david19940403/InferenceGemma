package hn.fredi.inferencelocal.agent

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class SshCredentialsTest {
    private fun credentials(): Pair<String, String> {
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 1024)
        try {
            val privateOut = ByteArrayOutputStream()
            val publicOut = ByteArrayOutputStream()
            pair.writePrivateKey(privateOut)
            pair.writePublicKey(publicOut, "test")
            return privateOut.toString("UTF-8") to "192.168.9.6 ${publicOut.toString("UTF-8")}"
        } finally { pair.dispose() }
    }

    @Test fun acceptsRealKeysAndImportsIdentity() {
        val (privateKey, knownHosts) = credentials()
        val ssh = SshCredentials.client(privateKey, knownHosts)
        try { assertEquals(1, ssh.identityRepository.identities.size) }
        finally { ssh.removeAllIdentity() }
    }

    @Test fun rejectsPlaceholderKnownHostsWithUsefulError() {
        val (privateKey, _) = credentials()
        val failure = assertThrows(IllegalArgumentException::class.java) {
            SshCredentials.client(privateKey, "192.168.9.6 ssh-ed25519 REEMPLAZAR")
        }
        assertTrue(failure.message!!.contains("known_hosts"))
    }

    @Test fun rejectsPlaceholderPrivateKeyWithoutLeakingItsContents() {
        val (_, knownHosts) = credentials()
        val failure = assertThrows(IllegalArgumentException::class.java) {
            SshCredentials.client("-----BEGIN OPENSSH PRIVATE KEY-----\nREEMPLAZAR\n-----END OPENSSH PRIVATE KEY-----", knownHosts)
        }
        assertTrue(failure.message!!.contains("private_key"))
        assertFalse(failure.message!!.contains("REEMPLAZAR"))
    }
}
