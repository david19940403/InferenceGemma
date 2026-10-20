package hn.fredi.inferencelocal.agent

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

data class CommandOutput(val exitCode: Int, val stdout: String, val stderr: String)

class SSHConnectionPool(private val config: AgentConfig) : AutoCloseable {
    private val lock = Mutex()
    private val sessions = mutableMapOf<HostConfig, Session>()
    private val jsch = JSch().apply {
        // Android does not expose the Java 15 EdDSA providers used by JSch on desktop.
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        setKnownHosts(ByteArrayInputStream(config.known_hosts.toByteArray()))
        val bytes = config.private_key.toByteArray()
        try { addIdentity("agent", bytes, null, null) } finally { bytes.fill(0) }
    }
    suspend fun execute(host: String, command: String): CommandOutput = withContext(Dispatchers.IO) {
        val target = config.hosts.singleOrNull { it.host == host } ?: error("Host no autorizado")
        val session = lock.withLock {
            sessions[target]?.takeIf { it.isConnected } ?: jsch.getSession(target.user, target.host, target.port).apply {
                setConfig("StrictHostKeyChecking", "yes")
                setConfig("PreferredAuthentications", "publickey")
                timeout = 10000
                serverAliveInterval = 15000
                try { connect(5000) } catch (e: Exception) { disconnect(); throw e }
                sessions[target] = this
            }
        }
        val channel = session.openChannel("exec") as ChannelExec
        try {
            channel.setCommand(command)
            channel.setInputStream(null)
            val stdout = channel.inputStream
            val stderr = channel.errStream
            channel.connect(5000)
            val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
            fun drain(input: java.io.InputStream, dest: ByteArrayOutputStream) {
                val buffer = ByteArray(4096)
                while (input.available() > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, input.available()))
                    if (count <= 0) break
                    require(out.size() + err.size() + count <= 32768) { "La salida SSH excedió 32 KiB" }
                    dest.write(buffer, 0, count)
                }
            }
            withTimeout(120000) {
                while (true) {
                    ensureActive(); drain(stdout, out); drain(stderr, err)
                    if (channel.isClosed) { drain(stdout, out); drain(stderr, err); break }
                    delay(20)
                }
            }
            check(channel.exitStatus >= 0) { "SSH terminó sin código de salida" }
            CommandOutput(channel.exitStatus, out.toString("UTF-8"), err.toString("UTF-8"))
        } finally { channel.disconnect() }
    }
    override fun close() { sessions.values.forEach { it.disconnect() }; sessions.clear(); jsch.removeAllIdentity() }
}
