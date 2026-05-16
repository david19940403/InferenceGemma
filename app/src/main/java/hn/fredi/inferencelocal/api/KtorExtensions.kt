package hn.fredi.inferencelocal.api

import hn.fredi.inferencelocal.api.engine.LlmEngine
import hn.fredi.inferencelocal.api.registry.ModelRegistry
import hn.fredi.inferencelocal.api.tools.ToolManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter

val LlmEngineKey     = AttributeKey<LlmEngine>("LlmEngine")
val ModelRegistryKey = AttributeKey<ModelRegistry>("ModelRegistry")
val ToolManagerKey   = AttributeKey<ToolManager>("ToolManager")

val apiJson = Json {
    prettyPrint       = false
    isLenient         = true
    ignoreUnknownKeys = true
    encodeDefaults    = true
}

fun PipelineContext<Unit, ApplicationCall>.requireEngine(): LlmEngine? {
    val engine = call.application.attributes.getOrNull(LlmEngineKey)
    if (engine == null) {
        // En teoría el servicio lo inyecta, si no está es un error de configuración
    }
    return engine
}

fun PipelineContext<Unit, ApplicationCall>.requireRegistry(): ModelRegistry {
    return call.application.attributes[ModelRegistryKey]
}

suspend inline fun <reified T : Any> PipelineContext<Unit, ApplicationCall>.safeReceive(): T? {
    return try {
        call.receive<T>()
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON: ${e.message}"))
        null
    }
}

fun PipelineContext<Unit, ApplicationCall>.streamHeaders() {
    call.response.header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.response.header(HttpHeaders.Connection, "keep-alive")
}

fun isoNow(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
