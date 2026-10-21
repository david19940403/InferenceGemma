package hn.fredi.inferencelocal.agent

import com.jcraft.jsch.JSch
import java.io.ByteArrayInputStream
import java.util.Base64

/** Validate credentials before saving them, keeping parser errors and key data out of the UI. */
object SshCredentials {
    fun client(privateKey: String, knownHosts: String): JSch {
        val lines = knownHosts.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        require(lines.isNotEmpty()) { "Falta known_hosts: importa la clave publica verificada del servidor SSH" }
        lines.forEachIndexed { index, line ->
            val fields = line.split(Regex("\\s+"))
            val offset = if(fields.first().startsWith("@")) 1 else 0
            require(fields.size >= offset + 3 && runCatching {
                Base64.getDecoder().decode(fields[offset + 2]).size >= 16
            }.getOrDefault(false)) { "known_hosts, linea ${index + 1}: clave publica SSH invalida; reemplaza los valores de ejemplo" }
        }
        val ssh = JSch()
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        try {
            ssh.setKnownHosts(ByteArrayInputStream(knownHosts.toByteArray()))
        } catch (e: Exception) {
            throw IllegalArgumentException("known_hosts invalido: importa claves publicas SSH verificadas")
        }
        val bytes = privateKey.toByteArray()
        try {
            ssh.addIdentity("agent", bytes, null, null)
            require(ssh.identityRepository.identities.none { it.isEncrypted }) { "La clave SSH requiere una frase de paso; importa una clave dedicada sin frase de paso" }
        } catch (e: Exception) {
            ssh.removeAllIdentity()
            throw IllegalArgumentException("private_key invalida: importa una clave privada SSH completa y sin frase de paso")
        } finally { bytes.fill(0) }
        return ssh
    }
}
