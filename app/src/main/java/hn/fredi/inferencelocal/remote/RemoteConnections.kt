package hn.fredi.inferencelocal.remote

import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class RemoteConnection(
    val id: String,
    val createdAt: Long,
    var lastSeen: Long,
    val remoteHost: String,
    val userAgent: String,
    var expiresAt: Long
)

/** In-memory browser/client sessions. Authentication tokens are intentionally never returned. */
class RemoteConnectionRegistry(private val ttlMs: Long = 8 * 60 * 60 * 1000L) {
    private data class Entry(val token: String, val connection: RemoteConnection)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun create(remoteHost: String, userAgent: String, now: Long = System.currentTimeMillis()): Pair<String, RemoteConnection> {
        purgeExpired(now)
        check(entries.size < 128) { "Demasiadas sesiones activas" }
        val token = newRemoteToken()
        val connection = RemoteConnection(
            id = UUID.randomUUID().toString(),
            createdAt = now,
            lastSeen = now,
            remoteHost = remoteHost.take(128),
            userAgent = userAgent.take(256),
            expiresAt = now + ttlMs
        )
        entries[connection.id] = Entry(token, connection)
        return token to connection.copy()
    }

    fun authenticate(token: String?, now: Long = System.currentTimeMillis()): RemoteConnection? {
        if (token.isNullOrBlank()) return null
        purgeExpired(now)
        val entry = entries.values.firstOrNull { it.token == token } ?: return null
        entry.connection.lastSeen = now
        return entry.connection.copy()
    }

    fun revoke(id: String): Boolean = entries.remove(id) != null

    fun revokeToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val match = entries.entries.firstOrNull { it.value.token == token } ?: return false
        return entries.remove(match.key) != null
    }

    fun purgeAll(): Int {
        val count = entries.size
        entries.clear()
        return count
    }

    fun purgeExpired(now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        entries.entries.removeIf { if (it.value.connection.expiresAt <= now) { removed++; true } else false }
        return removed
    }

    fun list(now: Long = System.currentTimeMillis()): List<RemoteConnection> {
        purgeExpired(now)
        return entries.values.map { it.connection.copy() }.sortedByDescending { it.lastSeen }
    }
}
