package hn.fredi.inferencelocal.api

import android.R
import hn.fredi.inferencelocal.api.engine.LlmEngine
import hn.fredi.inferencelocal.api.models.*
import hn.fredi.inferencelocal.api.registry.ModelRegistry
import hn.fredi.inferencelocal.api.tools.ToolManager
import hn.fredi.inferencelocal.api.routes.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// Módulo principal
// ─────────────────────────────────────────────────────────────────────────────

fun Application.ollamaApiModule() {
    val toolManager = ToolManager()
    attributes.put(ToolManagerKey, toolManager)

    install(CallLogging)
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
    }

    install(ContentNegotiation) { json(apiJson) }

    install(StatusPages) {
        exception<hn.fredi.inferencelocal.api.engine.TokenLimitExceededException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest,
                OllamaError("Límite de tokens superado: ${cause.message}. Sugerencia: Comprime el chat o reduce el historial.")
            )
        }

        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, OllamaError(cause.message ?: "invalid argument"))
        }
        exception<NotImplementedError> { call, cause ->
            call.respond(HttpStatusCode.NotImplemented,
                OllamaError("not implemented: ${cause.message}")
            )
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError,
                OllamaError("internal error: ${cause.message}")
            )
        }
    }

    routing {
        inferenceRoutes()
        modelRoutes()
        systemRoutes()
    }
}
