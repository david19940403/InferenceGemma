package hn.kwikstop.inferencelocal.api


import hn.kwikstop.inferencelocal.api.engine.LlmEngine
import hn.kwikstop.inferencelocal.api.models.*
import hn.kwikstop.inferencelocal.api.registry.ModelRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelineContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// Claves de atributos para inyección sin DI framework
// ─────────────────────────────────────────────────────────────────────────────

val LlmEngineKey    = AttributeKey<LlmEngine>("LlmEngine")
val ModelRegistryKey = AttributeKey<ModelRegistry>("ModelRegistry")

// ─────────────────────────────────────────────────────────────────────────────
// Módulo Ktor principal
// ─────────────────────────────────────────────────────────────────────────────

fun Application.ollamaApiModule() {

    // ── Serialización ─────────────────────────────────────────────────────────
    install(ContentNegotiation) {
        json(Json {
            prettyPrint       = false
            isLenient         = true
            ignoreUnknownKeys = true
            encodeDefaults    = true
        })
    }

    // ── Manejo global de errores ──────────────────────────────────────────────
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("Bad request: ${cause.message}"))
        }
        exception<NotImplementedError> { call, cause ->
            call.respond(HttpStatusCode.NotImplemented, ApiError("Not implemented: ${cause.message}"))
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ApiError("Internal error: ${cause.message}"))
        }
    }

    // ── Router ────────────────────────────────────────────────────────────────
    routing {

        // ╔═══════════════════════════════════════════════════════════════════╗
        // ║  ROOT / HEALTH                                                    ║
        // ╚═══════════════════════════════════════════════════════════════════╝

        get("/") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "Ollama is running"))
        }

        // ╔═══════════════════════════════════════════════════════════════════╗
        // ║  SECCIÓN 1 – GENERACIÓN Y CHAT                                   ║
        // ╚═══════════════════════════════════════════════════════════════════╝

        route("/api") {

            // ──────────────────────────────────────────────────────────────────
            // POST /api/generate
            // Genera una respuesta para un prompt de una sola interacción.
            // ──────────────────────────────────────────────────────────────────
            post("/generate") {
                val engine   = requireEngine() ?: return@post
                val registry = requireRegistry()

                val req = runCatching { call.receive<GenerateRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON: ${it.message}"))
                    return@post
                }

                if (req.prompt.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("'prompt' cannot be empty"))
                    return@post
                }

                if (!registry.exists(req.model)) {
                    call.respond(HttpStatusCode.NotFound, ApiError("model '${req.model}' not found"))
                    return@post
                }

                val result = engine.generate(
                    prompt  = req.prompt,
                    system  = req.system,
                    options = req.options
                )

                call.respond(
                    HttpStatusCode.OK,
                    GenerateResponse(
                        model           = req.model,
                        response        = result.text,
                        createdAt       = isoNow(),
                        done            = true,
                        totalDuration   = result.totalDurationNs,
                        evalCount       = result.evalCount
                    )
                )
            }

            // ──────────────────────────────────────────────────────────────────
            // POST /api/chat
            // Conversación multi-turno con historial de mensajes.
            // ──────────────────────────────────────────────────────────────────
            post("/chat") {
                val engine   = requireEngine() ?: return@post
                val registry = requireRegistry()

                val req = runCatching { call.receive<ChatRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON: ${it.message}"))
                    return@post
                }

                if (req.messages.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("'messages' cannot be empty"))
                    return@post
                }

                if (!registry.exists(req.model)) {
                    call.respond(HttpStatusCode.NotFound, ApiError("model '${req.model}' not found"))
                    return@post
                }

                val result = engine.chat(
                    messages = req.messages,
                    options  = req.options
                )

                call.respond(
                    HttpStatusCode.OK,
                    ChatResponse(
                        model     = req.model,
                        createdAt = isoNow(),
                        message   = ChatMessage(role = "assistant", content = result.text),
                        done      = true,
                        totalDuration = result.totalDurationNs,
                        evalCount     = result.evalCount
                    )
                )
            }

            // ──────────────────────────────────────────────────────────────────
            // POST /api/embeddings
            // Genera un vector de embedding para un texto.
            // ──────────────────────────────────────────────────────────────────
            post("/embeddings") {
                val engine   = requireEngine() ?: return@post
                val registry = requireRegistry()

                val req = runCatching { call.receive<EmbeddingsRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON: ${it.message}"))
                    return@post
                }

                if (req.prompt.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("'prompt' cannot be empty"))
                    return@post
                }

                if (!registry.exists(req.model)) {
                    call.respond(HttpStatusCode.NotFound, ApiError("model '${req.model}' not found"))
                    return@post
                }

                val embedding = engine.embed(req.prompt)
                call.respond(HttpStatusCode.OK, EmbeddingsResponse(embedding = embedding))
            }

            // ╔═══════════════════════════════════════════════════════════════╗
            // ║  SECCIÓN 2 – GESTIÓN DE MODELOS                              ║
            // ╚═══════════════════════════════════════════════════════════════╝

            // ──────────────────────────────────────────────────────────────────
            // GET /api/tags  → Lista modelos instalados
            // ──────────────────────────────────────────────────────────────────
            get("/tags") {
                val registry = requireRegistry()
                call.respond(HttpStatusCode.OK, registry.listModels())
            }

            // ──────────────────────────────────────────────────────────────────
            // POST /api/show  → Detalles de un modelo específico
            // ──────────────────────────────────────────────────────────────────
            post("/show") {
                val registry = requireRegistry()

                val req = runCatching { call.receive<ShowRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON: ${it.message}"))
                    return@post
                }

                val details = registry.showModel(req.name)
                if (details == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("model '${req.name}' not found"))
                    return@post
                }

                call.respond(HttpStatusCode.OK, details)
            }

            // ──────────────────────────────────────────────────────────────────
            // POST /api/create  → Crear modelo desde Modelfile
            // ──────────────────────────────────────────────────────────────────
            post("/create") {
                val registry = requireRegistry()

                val req = runCatching { call.receive<CreateRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON: ${it.message}"))
                    return@post
                }

                if (req.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("'name' cannot be empty"))
                    return@post
                }

                val result = registry.createModel(req.name, req.modelfile)
                val code = if (result.status == "success") HttpStatusCode.OK
                else HttpStatusCode.BadRequest

                call.respond(code, result)
            }

            // ──────────────────────────────────────────────────────────────────
            // POST /api/copy  → Copiar modelo a un nuevo nombre
            // ──────────────────────────────────────────────────────────────────
            post("/copy") {
                val registry = requireRegistry()

                val req = runCatching { call.receive<CopyRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON: ${it.message}"))
                    return@post
                }

                val ok = registry.copyModel(req.source, req.destination)
                if (!ok) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("source model '${req.source}' not found")
                    )
                    return@post
                }

                call.respond(HttpStatusCode.OK, mapOf("status" to "success"))
            }

            // ──────────────────────────────────────────────────────────────────
            // DELETE /api/delete  → Eliminar modelo
            // ──────────────────────────────────────────────────────────────────
            delete("/delete") {
                val registry = requireRegistry()

                val req = runCatching { call.receive<DeleteRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON: ${it.message}"))
                    return@delete
                }

                val ok = registry.deleteModel(req.name)
                if (!ok) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("model '${req.name}' not found or is currently active")
                    )
                    return@delete
                }

                call.respond(HttpStatusCode.OK, mapOf("status" to "success"))
            }

            // ──────────────────────────────────────────────────────────────────
            // POST /api/pull  → Descargar modelo desde el registro
            //
            // En un dispositivo Android no podemos descargar desde registry.ollama.ai
            // directamente (tamaños de GB). Devolvemos una respuesta informativa
            // y guiamos al usuario a usar ADB.
            // ──────────────────────────────────────────────────────────────────
            post("/pull") {
                val req = runCatching { call.receive<PullRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON: ${it.message}"))
                    return@post
                }

                val registry = requireRegistry()
                val known    = registry.knownRemoteModels()

                if (req.name !in known) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiError(
                            "Model '${req.name}' not in the known remote list. " +
                                    "Available: ${known.joinToString()}. " +
                                    "Use ADB to push model files directly."
                        )
                    )
                    return@post
                }

                // Modelo conocido pero no descargable en el dispositivo sin ADB
                call.respond(
                    HttpStatusCode.NotImplemented,
                    PullResponse(
                        status = "Pull from Android is not supported. " +
                                "Use ADB to push the .bin file to " +
                                "/sdcard/Android/data/<package>/files/"
                    )
                )
            }

            // ──────────────────────────────────────────────────────────────────
            // POST /api/push  → Subir modelo al registro
            // No implementado en entorno Android local.
            // ──────────────────────────────────────────────────────────────────
            post("/push") {
                call.respond(
                    HttpStatusCode.NotImplemented,
                    PushResponse(
                        status = "Push is not supported from the Android local server."
                    )
                )
            }

            // ╔═══════════════════════════════════════════════════════════════╗
            // ║  SECCIÓN 3 – ESTADO DEL SISTEMA                              ║
            // ╚═══════════════════════════════════════════════════════════════╝

            // ──────────────────────────────────────────────────────────────────
            // GET /api/ps  → Modelos activos en memoria
            // ──────────────────────────────────────────────────────────────────
            get("/ps") {
                val registry = requireRegistry()
                call.respond(HttpStatusCode.OK, registry.listRunningModels())
            }

            // ──────────────────────────────────────────────────────────────────
            // GET /api/version  → Versión del servidor
            // ──────────────────────────────────────────────────────────────────
            get("/version") {
                val registry = requireRegistry()
                call.respond(HttpStatusCode.OK, registry.version())
            }

            // ── Alias sin prefijo /api para compatibilidad ────────────────────
            get("/health") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
        }

        // ╔═══════════════════════════════════════════════════════════════════╗
        // ║  SECCIÓN 4 – COMPATIBILIDAD OPENAI  (/v1/...)                   ║
        // ╚═══════════════════════════════════════════════════════════════════╝

        route("/v1") {

            // ──────────────────────────────────────────────────────────────────
            // POST /v1/chat/completions  → Equivalente a /api/chat
            // ──────────────────────────────────────────────────────────────────
            post("/chat/completions") {
                val engine   = requireEngine() ?: return@post
                val registry = requireRegistry()

                val req = runCatching { call.receive<OaiChatRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON: ${it.message}"))
                    return@post
                }

                if (req.messages.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("'messages' cannot be empty"))
                    return@post
                }

                if (!registry.exists(req.model)) {
                    call.respond(HttpStatusCode.NotFound, ApiError("model '${req.model}' not found"))
                    return@post
                }

                // Convertir OaiMessage → ChatMessage (misma estructura)
                val chatMessages = req.messages.map {
                    ChatMessage(role = it.role, content = it.content)
                }

                val result = engine.chat(messages = chatMessages)

                call.respond(
                    HttpStatusCode.OK,
                    OaiChatResponse(
                        id      = "chatcmpl-${UUID.randomUUID()}",
                        created = Instant.now().epochSecond,
                        model   = req.model,
                        choices = listOf(
                            OaiChoice(
                                index        = 0,
                                message      = OaiMessage(role = "assistant", content = result.text),
                                finishReason = "stop"
                            )
                        ),
                        usage = OaiUsage(
                            promptTokens     = req.messages.sumOf { estimateTokens(it.content) },
                            completionTokens = result.evalCount,
                            totalTokens      = req.messages.sumOf { estimateTokens(it.content) } + result.evalCount
                        )
                    )
                )
            }

            // ──────────────────────────────────────────────────────────────────
            // POST /v1/embeddings  → Equivalente a /api/embeddings
            // ──────────────────────────────────────────────────────────────────
            post("/embeddings") {
                val engine   = requireEngine() ?: return@post
                val registry = requireRegistry()

                val req = runCatching { call.receive<OaiEmbeddingRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON: ${it.message}"))
                    return@post
                }

                if (req.input.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("'input' cannot be empty"))
                    return@post
                }

                if (!registry.exists(req.model)) {
                    call.respond(HttpStatusCode.NotFound, ApiError("model '${req.model}' not found"))
                    return@post
                }

                val embedding = engine.embed(req.input)

                call.respond(
                    HttpStatusCode.OK,
                    OaiEmbeddingResponse(
                        data  = listOf(OaiEmbeddingData(index = 0, embedding = embedding)),
                        model = req.model,
                        usage = OaiUsage(
                            promptTokens = estimateTokens(req.input),
                            totalTokens  = estimateTokens(req.input)
                        )
                    )
                )
            }

            // ──────────────────────────────────────────────────────────────────
            // GET /v1/models  → Equivalente a /api/tags
            // ──────────────────────────────────────────────────────────────────
            get("/models") {
                val registry = requireRegistry()
                val tags     = registry.listModels()

                val oaiModels = tags.models.map { m ->
                    OaiModelData(
                        id      = m.name,
                        created = Instant.now().epochSecond - 86400,
                        ownedBy = "local"
                    )
                }

                call.respond(HttpStatusCode.OK, OaiModelsResponse(data = oaiModels))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers de extensión para los handlers
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun PipelineContext<Unit, ApplicationCall>.requireEngine(): LlmEngine? {
    val engine = call.application.attributes.getOrNull(LlmEngineKey)
    if (engine == null || !engine.isLoaded) {
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            ApiError("Model not loaded yet. Please wait a few seconds and retry.")
        )
        return null
    }
    return engine
}

private fun PipelineContext<Unit, ApplicationCall>.requireRegistry(): ModelRegistry =
    call.application.attributes[ModelRegistryKey]

private fun isoNow(): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.now())

private fun estimateTokens(text: String): Int =
    (text.split(Regex("\\s+")).size * 1.33).toInt().coerceAtLeast(1)