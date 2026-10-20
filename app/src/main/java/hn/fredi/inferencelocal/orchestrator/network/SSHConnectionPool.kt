package hn.fredi.inferencelocal.orchestrator.network

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import hn.fredi.inferencelocal.orchestrator.models.CommandOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class SSHConnectionPool(
    private val privateKey: File,
    private val knownHosts: File
) : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, Session>()
    private val jsch = JSch().apply {
        require(privateKey.isFile) { "No existe la clave SSH: ${privateKey.path}" }
        require(knownHosts.isFile) { "No existe known_hosts: ${knownHosts.path}" }
        addIdentity(privateKey.absolutePath)
        setKnownHosts(knownHosts.absolutePath)
    }

    suspend fun executeCommand(
        host: String,
        user: String,
        port: Int,
        command: String
    ): CommandOutput = withContext(Dispatchers.IO) {
        require(host.matches(Regex("^[A-Za-z0-9._:-]+$"))) { "Host SSH inválido" }
        val key = "$user@$host:$port"
        var session = sessions[key]
        if (session?.isConnected != true) {
            session?.disconnect()
            session = jsch.getSession(user, host, port).apply {
                setConfig("StrictHostKeyChecking", "yes")
                setConfig("PreferredAuthentications", "publickey")
                connect(8_000)
            }
            sessions[key] = session
        }
        val channel = session.openChannel("exec") as ChannelExec
        try {
            channel.setCommand(command)
            channel.setInputStream(null)
            val stdout = channel.inputStream
            val stderr = channel.errStream
            channel.connect(5_000)
            val outText = stdout.bufferedReader().readText().take(MAX_OUTPUT_CHARS)
            val errText = stderr.bufferedReader().readText().take(MAX_OUTPUT_CHARS)
            while (!channel.isClosed) Thread.sleep(20)
            CommandOutput(channel.exitStatus, outText, errText)
        } finally {
            channel.disconnect()
        }
    }

    override fun close() {
        sessions.values.forEach { if (it.isConnected) it.disconnect() }
        sessions.clear()
    }

    private companion object { const val MAX_OUTPUT_CHARS = 32_768 }
}
