package hn.fredi.inferencelocal.remote

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import hn.fredi.inferencelocal.agent.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

private val wire = Json { encodeDefaults = true }
@Serializable private data class IntentRequest(val query: String, val request_id: String)
@Serializable private data class EngineRequest(val action: String, val request_id: String)
@Serializable private data class SettingsResponse(val settings: EngineSettings, val models: List<String>)
@Serializable private data class CatalogProbeResponse(val url: String, val message: String, val sources: List<CatalogStatus>)
@Serializable private data class StatusResponse(val engine: EngineMonitorState, val terminal: TerminalState, val owner: String?, val job: RemoteJob?, val ram_available_mb: Long, val process_pss_mb: Int, val toolstore: ToolStoreSettings, val catalogs: List<CatalogStatus> = CatalogMonitor.state.value)
private suspend inline fun <reified T> ApplicationCall.json(value: T, status: HttpStatusCode = HttpStatusCode.OK) = respondText(wire.encodeToString(value), ContentType.Application.Json, status)
private suspend inline fun <reified T> ApplicationCall.body(): T {
    val bytes = receiveChannel().readRemaining(65537).readBytes()
    require(bytes.size <= 65536) { "Solicitud demasiado grande" }
    return wire.decodeFromString(bytes.toString(Charsets.UTF_8))
}

fun Application.remoteModule(context: Context, settings: RemoteSettings) {
    val sessions = ConcurrentHashMap<String,Long>()
    val clientAvailable = runCatching { context.assets.open("client.apk").close(); true }.getOrDefault(false)
    suspend fun ApplicationCall.authorized(): Boolean {
        val origin = request.headers[HttpHeaders.Origin]
        if(origin != null && runCatching { val uri = URI(origin); uri.scheme == "http" && uri.rawAuthority.equals(request.headers[HttpHeaders.Host], true) }.getOrDefault(false).not()) {
            json(mapOf("error" to "Origen no permitido"),HttpStatusCode.Forbidden); return false
        }
        val bearer = request.headers[HttpHeaders.Authorization]
        val valid = if(bearer != null) MessageDigest.isEqual(bearer.toByteArray(), "Bearer ${settings.token}".toByteArray())
            else request.cookies["remote_session"]?.let { (sessions[it] ?: 0) > System.currentTimeMillis() } == true
        if(!valid) json(mapOf("error" to "Introduce la clave de acceso del dispositivo"),HttpStatusCode.Unauthorized)
        return valid
    }
    suspend fun ApplicationCall.guarded(block: suspend () -> Unit) {
        response.headers.append("Cache-Control","no-store")
        if(!authorized()) return
        try { block() }
        catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: SerializationException) { json(mapOf("error" to "JSON inválido"),HttpStatusCode.BadRequest) }
        catch(e: IllegalArgumentException) { json(mapOf("error" to (e.message ?: "Datos inválidos")),HttpStatusCode.BadRequest) }
        catch(e: IllegalStateException) { json(mapOf("error" to (e.message ?: "Engine ocupado")),HttpStatusCode.Conflict) }
        catch(e: Exception) { json(mapOf("error" to "No se pudo completar la solicitud"),HttpStatusCode.InternalServerError) }
    }
    suspend fun submit(action: String, query: String, requestId: String): RemoteJob = withContext(Dispatchers.Main) {
        if(action == "load") check(EngineOwnership.owner.value in listOf(null,"terminal")) { "Libera el servidor API antes de cargar la terminal" }
        RemoteJobs.submit(action,query,requestId,EngineMonitor.state.value.busy || LiteRTEngineService.state.value.busy) { id ->
            val command = when(action) { "intent" -> LiteRTEngineService.RUN; "load" -> LiteRTEngineService.LOAD; else -> LiteRTEngineService.UNLOAD }
            ContextCompat.startForegroundService(context,Intent(context,LiteRTEngineService::class.java).setAction(command).putExtra("query",query).putExtra("remote_job_id",id))
        }
    }
    routing {
        get("/") {
            call.response.headers.append("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
            call.respondText(context.assets.open("remote/index.html").bufferedReader().use { it.readText() },ContentType.Text.Html)
        }
        get("/assets/{file}") {
            val name = call.parameters["file"]
            if(name !in listOf("app.js","styles.css")) { call.respond(HttpStatusCode.NotFound); return@get }
            call.respondText(context.assets.open("remote/$name").bufferedReader().use { it.readText() },if(name == "app.js") ContentType.Application.JavaScript else ContentType.Text.CSS)
        }
        get("/remote/health") { call.json(buildJsonObject { put("service","InferenceLocal Remote");put("version",1);put("client_available",clientAvailable) }) }
        get("/client.apk") {
            if(!clientAvailable) { call.respond(HttpStatusCode.NotFound); return@get }
            call.response.headers.append(HttpHeaders.ContentDisposition,"attachment; filename=InferenceClient.apk")
            call.respondOutputStream(ContentType.parse("application/vnd.android.package-archive")) { context.assets.open("client.apk").use { it.copyTo(this) } }
        }
        post("/remote/session") { call.guarded {
            sessions.entries.removeIf { it.value < System.currentTimeMillis() }
            check(sessions.size < 128) { "Demasiadas sesiones activas" }
            val session = newRemoteToken();sessions[session] = System.currentTimeMillis()+8*60*60*1000
            call.response.cookies.append(Cookie("remote_session",session,httpOnly=true,path="/",maxAge=28800,extensions=mapOf("SameSite" to "Strict")))
            call.json(mapOf("ok" to true))
        } }
        post("/remote/logout") { call.guarded {
            call.request.cookies["remote_session"]?.let { sessions.remove(it) }
            call.response.cookies.append(Cookie("remote_session","",httpOnly=true,path="/",maxAge=0,extensions=mapOf("SameSite" to "Strict")))
            call.json(mapOf("ok" to true))
        } }
        get("/remote/status") { call.guarded {
            val memory = android.app.ActivityManager.MemoryInfo().also { context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
            call.json(StatusResponse(EngineMonitor.state.value,LiteRTEngineService.state.value,EngineOwnership.owner.value,RemoteJobs.state.value,memory.availMem/1048576,(android.os.Debug.getPss()/1024).toInt(),ToolStoreSettingsStore(context).load()))
        } }
        get("/remote/settings") { call.guarded {
            call.json(SettingsResponse(EngineSettingsStore(context).load(),context.getExternalFilesDir(null)?.listFiles()?.filter { it.extension == "litertlm" }?.map { it.path }.orEmpty()))
        } }
        put("/remote/settings") { call.guarded {
            val value = call.body<EngineSettings>()
            withContext(Dispatchers.Main) { EngineSettingsStore(context).save(value) }
            call.json(value)
        } }
        get("/remote/toolstore") { call.guarded { call.json(ToolStoreSettingsStore(context).load()) } }
        put("/remote/toolstore") { call.guarded {
            val value=call.body<ToolStoreSettings>()
            withContext(Dispatchers.Main) { ToolStoreSettingsStore(context).save(value) }
            call.json(ToolStoreSettingsStore(context).load())
        } }
        post("/remote/toolstore/test") { call.guarded {
            val value=call.body<ToolStoreSettings>()
            val config=ConfigVault(context).load()
            val client=FederatedToolStoreClient(ToolStoreSettingsStore(context).connections(config,value)) { source, query ->
                ToolStoreClient(config.copy(tool_store_url=source.url,token=source.token)).search(query)
            }
            try { client.search("__connection_test__") } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: IllegalStateException) { /* Include per-node results even when every node is unavailable. */ }
            call.json(CatalogProbeResponse(value.url, "${client.statuses.count { it.status == "ok" }} catálogos disponibles. Probar no guarda cambios.", client.statuses))
        } }
        post("/remote/intents") { call.guarded { val request = call.body<IntentRequest>(); call.json(submit("intent",request.query,request.request_id),HttpStatusCode.Accepted) } }
        post("/remote/engine") { call.guarded {
            val request = call.body<EngineRequest>();require(request.action in listOf("load","unload")) { "Acción inválida" }
            call.json(submit(request.action,"",request.request_id),HttpStatusCode.Accepted)
        } }
        post("/remote/cancel") { call.guarded {
            withContext(Dispatchers.Main) { if(LiteRTEngineService.state.value.busy || RemoteJobs.pending) context.startService(Intent(context,LiteRTEngineService::class.java).setAction(LiteRTEngineService.CANCEL)) }
            call.json(mapOf("ok" to true))
        } }
    }
}
