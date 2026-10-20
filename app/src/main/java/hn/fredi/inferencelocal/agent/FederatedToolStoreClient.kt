package hn.fredi.inferencelocal.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.security.MessageDigest

class ToolStoreConnection(val id: String, val label: String, val url: String, val token: String) {
    override fun toString() = "$id ($url)"
}
@Serializable data class CatalogStatus(val id: String, val label: String, val url: String, val status: String, val toolCount: Int = 0)
object CatalogMonitor {
    private val mutable = MutableStateFlow<List<CatalogStatus>>(emptyList())
    val state = mutable.asStateFlow()
    fun update(value: List<CatalogStatus>) { mutable.value = value }
}

/** A failed node is skipped for subsequent search reformulations in this transaction only. */
class FederatedToolStoreClient(
    private val sources: List<ToolStoreConnection>,
    private val request: suspend (ToolStoreConnection, String) -> List<RemoteTool>
) {
    private val failed = mutableMapOf<String, CatalogStatus>()
    var statuses: List<CatalogStatus> = emptyList(); private set
    suspend fun search(query: String): List<RemoteTool> = coroutineScope {
        val results = sources.map { source -> async {
            failed[source.id]?.let { return@async it to emptyList<RemoteTool>() }
            try {
                val tools = withTimeout(7500) { request(source, query) }
                CatalogStatus(source.id, source.label, source.url, "ok", tools.size) to tools
            } catch(e: Exception) {
                if(e is CancellationException && e !is TimeoutCancellationException) throw e
                currentCoroutineContext().ensureActive()
                val status = when {
                    e is java.io.IOException || e is TimeoutCancellationException -> "offline"
                    e is IllegalArgumentException -> "invalid"
                    e.message?.contains("HTTP 401") == true || e.message?.contains("HTTP 403") == true -> "unauthorized"
                    else -> "error"
                }
                CatalogStatus(source.id, source.label, source.url, status) to emptyList()
            }
        } }.awaitAll()
        statuses = results.map { it.first }
        statuses.filter { it.status != "ok" }.forEach { failed[it.id] = it }
        CatalogMonitor.update(statuses)
        check(statuses.any { it.status == "ok" }) { "Ningún Tool Store disponible. Revisa los estados de los catálogos." }
        merge(query, results.flatMap { (status, tools) -> tools.map { status.id to it } })
    }

    companion object {
        internal fun merge(query: String, candidates: List<Pair<String, RemoteTool>>): List<RemoteTool> {
            // Equal executable definitions from central/local catalogs represent one tool.
            val unique = candidates.distinctBy { (_, t) -> Triple(t.name, t.transport, t.parameters) }
            val collisions = unique.groupingBy { it.second.name }.eachCount()
            fun words(value: String) = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "").split(Regex("[^a-z0-9]+" )).filter { it.length > 1 }.toSet()
            val terms = words(query)
            return unique.sortedByDescending { (_, t) -> words(t.name + " " + t.description + " " + t.transport.target_host).intersect(terms).size }
                .take(3).map { (id, tool) ->
                    if(collisions.getValue(tool.name) == 1) tool
                    else {
                        val hash = MessageDigest.getInstance("SHA-256").digest((tool.transport.toString() + tool.parameters).toByteArray())
                            .take(5).joinToString("") { "%02x".format(it.toInt() and 255) }
                        tool.copy(name = tool.name.take(28) + "__" + id.replace('-', '_').take(20) + "_" + hash,
                            description = "[$id / ${tool.transport.target_host}] ${tool.description}")
                    }
                }
        }
    }
}
