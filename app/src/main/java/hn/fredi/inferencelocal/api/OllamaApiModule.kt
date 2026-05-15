package hn.fredi.inferencelocal.api

import hn.fredi.inferencelocal.api.engine.LlmEngine
import hn.fredi.inferencelocal.api.models.ChatFinalResponse
import hn.fredi.inferencelocal.api.models.ChatMessage
import hn.fredi.inferencelocal.api.models.ChatRequest
import hn.fredi.inferencelocal.api.models.ChatStreamChunk
import hn.fredi.inferencelocal.api.models.ChatUnloadResponse
import hn.fredi.inferencelocal.api.models.CopyRequest
import hn.fredi.inferencelocal.api.models.CreateRequest
import hn.fredi.inferencelocal.api.models.CreateStatusLine
import hn.fredi.inferencelocal.api.models.DeleteRequest
import hn.fredi.inferencelocal.api.models.EmbedRequest
import hn.fredi.inferencelocal.api.models.EmbedResponse
import hn.fredi.inferencelocal.api.models.GenerateFinalChunk
import hn.fredi.inferencelocal.api.models.GenerateRequest
import hn.fredi.inferencelocal.api.models.GenerateStreamChunk
import hn.fredi.inferencelocal.api.models.LegacyEmbeddingsRequest
import hn.fredi.inferencelocal.api.models.LegacyEmbeddingsResponse
import hn.fredi.inferencelocal.api.models.OaiChatRequest
import hn.fredi.inferencelocal.api.models.OaiChatResponse
import hn.fredi.inferencelocal.api.models.OaiChoice
import hn.fredi.inferencelocal.api.models.OaiCompletionChoice
import hn.fredi.inferencelocal.api.models.OaiCompletionRequest
import hn.fredi.inferencelocal.api.models.OaiCompletionResponse
import hn.fredi.inferencelocal.api.models.OaiDelta
import hn.fredi.inferencelocal.api.models.OaiEmbeddingData
import hn.fredi.inferencelocal.api.models.OaiEmbeddingRequest
import hn.fredi.inferencelocal.api.models.OaiEmbeddingResponse
import hn.fredi.inferencelocal.api.models.OaiMessage
import hn.fredi.inferencelocal.api.models.OaiModelData
import hn.fredi.inferencelocal.api.models.OaiModelsResponse
import hn.fredi.inferencelocal.api.models.OaiStreamChoice
import hn.fredi.inferencelocal.api.models.OaiStreamChunk
import hn.fredi.inferencelocal.api.models.OaiUsage
import hn.fredi.inferencelocal.api.models.OllamaError
import hn.fredi.inferencelocal.api.models.OllamaMessage
import hn.fredi.inferencelocal.api.models.PullRequest
import hn.fredi.inferencelocal.api.models.PullStatusLine
import hn.fredi.inferencelocal.api.models.ShowRequest
import hn.fredi.inferencelocal.api.registry.ModelRegistry
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// Inyección de dependencias
// ─────────────────────────────────────────────────────────────────────────────

val LlmEngineKey     = AttributeKey<LlmEngine>("LlmEngine")
val ModelRegistryKey = AttributeKey<ModelRegistry>("ModelRegistry")

// ─────────────────────────────────────────────────────────────────────────────
// Serializer compartido (lenient para máxima compatibilidad con clientes)
// ─────────────────────────────────────────────────────────────────────────────

private val apiJson = Json {
    prettyPrint       = false
    isLenient         = true
    ignoreUnknownKeys = true
    encodeDefaults    = true
    explicitNulls     = false   // no enviar campos null → menos ruido en clientes
}

// ─────────────────────────────────────────────────────────────────────────────
// Módulo principal
// ─────────────────────────────────────────────────────────────────────────────

fun Application.ollamaApiModule() {

    install(ContentNegotiation) { json(apiJson) }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest,
                OllamaError("bad request: ${cause.message}")
            )
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

        // ─── Health ──────────────────────────────────────────────────────────
        get("/") {
            // Ollama real responde con el string "Ollama is running"
            call.respondText("Ollama is running", ContentType.Text.Plain)
        }

        get("/api/health") {
            call.respondText("OK", ContentType.Text.Plain)
        }

        // ════════════════════════════════════════════════════════════════════
        // SECCIÓN 1 — INFERENCIA
        // ════════════════════════════════════════════════════════════════════

        // ── POST /api/generate ───────────────────────────────────────────────
        // Compatibilidad: Open WebUI, tg-bot-ollama, shell scripts, LangChain
        //
        // Ollama stream=true (default): NDJSON donde cada línea es un chunk con
        //   { model, created_at, response: "<delta>", done: false }
        // Ollama stream=false: objeto único con response completa + stats
        // ────────────────────────────────────────────────────────────────────
        post("/api/generate") {
            val engine   = requireEngine() ?: return@post
            val registry = requireRegistry()

            val req = safeReceive<GenerateRequest>() ?: return@post

            if (req.prompt.isBlank()) {
                call.respond(HttpStatusCode.BadRequest,
                    OllamaError("'prompt' is required")
                )
                return@post
            }
            if (!registry.exists(req.model)) {
                call.respond(HttpStatusCode.NotFound,
                    OllamaError("model '${req.model}' not found")
                )
                return@post
            }

            // stream=true es el default de Ollama
            val wantStream = req.stream ?: true

            if (wantStream) {
                streamHeaders()
                call.respondBytesWriter(status = HttpStatusCode.OK) {
                    var accumulated = ""
                    engine.chatStream(
                        messages = listOf(
                            ChatMessage(
                                role = "system",
                                content = req.system ?: "You are a helpful assistant."
                            ),
                            ChatMessage(role = "user", content = req.prompt)
                        )
                    )
                        .catch { e ->
                            // Error mid-stream: enviamos done=true con mensaje de error
                            val errChunk = GenerateStreamChunk(
                                model = req.model,
                                createdAt = isoNow(),
                                response = "\n[Error: ${e.message}]",
                                done = true
                            )
                            writeStringUtf8(apiJson.encodeToString(errChunk) + "\n")
                            flush()
                        }
                        .collect { token ->
                            if (token.text.isNotEmpty()) {
                                accumulated += token.text
                                val chunk = GenerateStreamChunk(
                                    model = req.model,
                                    createdAt = isoNow(),
                                    response = token.text,
                                    done = false
                                )
                                writeStringUtf8(apiJson.encodeToString(chunk) + "\n")
                                flush()
                            }
                            if (token.isDone) {
                                val finalChunk = GenerateFinalChunk(
                                    model = req.model,
                                    createdAt = isoNow(),
                                    response = "",
                                    done = true,
                                    doneReason = "stop",
                                    totalDuration = 0L,
                                    loadDuration = 0L,
                                    promptEvalCount = estimateTokens(req.prompt),
                                    promptEvalDuration = 0L,
                                    evalCount = estimateTokens(accumulated),
                                    evalDuration = 0L
                                )
                                writeStringUtf8(apiJson.encodeToString(finalChunk) + "\n")
                                flush()
                            }
                        }
                }
            } else {
                val startMs = System.currentTimeMillis()
                val result  = engine.generate(
                    prompt  = req.prompt,
                    system  = req.system,
                    options = req.options
                )
                val elapsed = System.currentTimeMillis() - startMs

                call.respond(HttpStatusCode.OK,
                    GenerateFinalChunk(
                        model = req.model,
                        createdAt = isoNow(),
                        response = result.text,
                        done = true,
                        doneReason = "stop",
                        totalDuration = elapsed * 1_000_000L,
                        loadDuration = 0L,
                        promptEvalCount = estimateTokens(req.prompt),
                        promptEvalDuration = 0L,
                        evalCount = result.evalCount,
                        evalDuration = result.totalDurationNs
                    )
                )
            }
        }

        // ── POST /api/chat ────────────────────────────────────────────────────
        // Compatibilidad: Open WebUI, Chatbot UI, Msty, Hollama, Continue.dev
        //
        // Caso especial de Ollama: messages=[] con keep_alive=0 → descargar modelo
        //
        // Stream chunks:
        //   { model, created_at, message: {role:"assistant", content:"<delta>"}, done:false }
        // Final chunk:
        //   { ..., message: {role:"assistant", content:""}, done:true, done_reason:"stop",
        //     total_duration, load_duration, prompt_eval_count, eval_count, ... }
        // ─────────────────────────────────────────────────────────────────────
        post("/api/chat") {
            val engine   = requireEngine() ?: return@post
            val registry = requireRegistry()

            val req = safeReceive<ChatRequest>() ?: return@post

            // Caso especial: vaciar modelo de memoria
            if (req.messages.isEmpty()) {
                val reason = if ((req.keepAlive ?: 1) <= 0) "unload" else "load"
                if (reason == "unload") engine.unload()
                call.respond(HttpStatusCode.OK,
                    ChatUnloadResponse(
                        model = req.model,
                        createdAt = isoNow(),
                        message = OllamaMessage(role = "assistant", content = ""),
                        doneReason = reason,
                        done = true
                    )
                )
                return@post
            }

            if (!registry.exists(req.model)) {
                call.respond(HttpStatusCode.NotFound,
                    OllamaError("model '${req.model}' not found")
                )
                return@post
            }

            val wantStream = req.stream ?: true
            val startMs    = System.currentTimeMillis()

            if (wantStream) {
                streamHeaders()
                call.respondBytesWriter(status = HttpStatusCode.OK) {
                    var tokensEmitted = 0
                    var fullText      = StringBuilder()

                    engine.chatStream(messages = req.messages)
                        .catch { e ->
                            val errChunk = buildChatChunk(
                                model     = req.model,
                                content   = "\n[Error: ${e.message}]",
                                done      = true,
                                doneReason = "error"
                            )
                            writeStringUtf8(apiJson.encodeToString(errChunk) + "\n")
                            flush()
                        }
                        .collect { token ->
                            if (token.text.isNotEmpty()) {
                                tokensEmitted++
                                fullText.append(token.text)
                                val chunk = buildChatChunk(
                                    model   = req.model,
                                    content = token.text,
                                    done    = false
                                )
                                writeStringUtf8(apiJson.encodeToString(chunk) + "\n")
                                flush()
                            }
                            if (token.isDone) {
                                val elapsed = System.currentTimeMillis() - startMs
                                val final   = buildChatFinal(
                                    model              = req.model,
                                    doneReason         = "stop",
                                    totalDuration      = elapsed * 1_000_000L,
                                    promptEvalCount    = req.messages.sumOf { estimateTokens(it.content) },
                                    evalCount          = tokensEmitted
                                )
                                writeStringUtf8(apiJson.encodeToString(final) + "\n")
                                flush()
                            }
                        }
                }
            } else {
                val result  = engine.chat(messages = req.messages, options = req.options)
                val elapsed = System.currentTimeMillis() - startMs

                call.respond(HttpStatusCode.OK,
                    ChatFinalResponse(
                        model = req.model,
                        createdAt = isoNow(),
                        message = OllamaMessage(
                            role = "assistant",
                            content = result.text
                        ),
                        done = true,
                        doneReason = "stop",
                        totalDuration = elapsed * 1_000_000L,
                        loadDuration = 0L,
                        promptEvalCount = req.messages.sumOf { estimateTokens(it.content) },
                        promptEvalDuration = 0L,
                        evalCount = result.evalCount,
                        evalDuration = result.totalDurationNs
                    )
                )
            }
        }

        // ── POST /api/embed  (Ollama ≥ 0.1.25, reemplaza /api/embeddings) ────
        // Soporta batch: input puede ser string o array de strings
        // ─────────────────────────────────────────────────────────────────────
        post("/api/embed") {
            val engine   = requireEngine() ?: return@post
            val registry = requireRegistry()
            val req      = safeReceive<EmbedRequest>() ?: return@post

            if (!registry.exists(req.model)) {
                call.respond(HttpStatusCode.NotFound,
                    OllamaError("model '${req.model}' not found")
                )
                return@post
            }

            val texts = when {
                req.input != null      -> listOf(req.input)
                req.prompt != null     -> listOf(req.prompt)   // legacy
                else -> {
                    call.respond(HttpStatusCode.BadRequest,
                        OllamaError("'input' is required")
                    )
                    return@post
                }
            }

            val embeddings = texts.map { engine.embed(it) }

            call.respond(HttpStatusCode.OK,
                EmbedResponse(
                    model = req.model,
                    embeddings = embeddings
                )
            )
        }

        // ── POST /api/embeddings  (endpoint legacy, mantenemos compatibilidad) ─
        post("/api/embeddings") {
            val engine   = requireEngine() ?: return@post
            val registry = requireRegistry()
            val req      = safeReceive<LegacyEmbeddingsRequest>() ?: return@post

            if (req.prompt.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest,
                    OllamaError("'prompt' is required")
                )
                return@post
            }
            if (!registry.exists(req.model)) {
                call.respond(HttpStatusCode.NotFound,
                    OllamaError("model '${req.model}' not found")
                )
                return@post
            }

            val embedding = engine.embed(req.prompt)
            call.respond(HttpStatusCode.OK,
                LegacyEmbeddingsResponse(embedding = embedding)
            )
        }

        // ════════════════════════════════════════════════════════════════════
        // SECCIÓN 2 — GESTIÓN DE MODELOS
        // ════════════════════════════════════════════════════════════════════

        // ── GET /api/tags ─────────────────────────────────────────────────────
        get("/api/tags") {
            call.respond(HttpStatusCode.OK, requireRegistry().listModels())
        }

        // ── POST /api/show ────────────────────────────────────────────────────
        // Devuelve Modelfile, parámetros, template, info del modelo
        post("/api/show") {
            val registry = requireRegistry()
            val req      = safeReceive<ShowRequest>() ?: return@post

            val details = registry.showModel(req.name)
            if (details == null) {
                call.respond(HttpStatusCode.NotFound,
                    OllamaError("model '${req.name}' not found")
                )
                return@post
            }
            call.respond(HttpStatusCode.OK, details)
        }

        // ── POST /api/create ──────────────────────────────────────────────────
        post("/api/create") {
            val registry = requireRegistry()
            val req      = safeReceive<CreateRequest>() ?: return@post

            if (req.name.isBlank()) {
                call.respond(HttpStatusCode.BadRequest,
                    OllamaError("'name' is required")
                )
                return@post
            }

            // Ollama retorna un stream NDJSON de progreso al crear
            streamHeaders()
            call.respondBytesWriter(status = HttpStatusCode.OK) {
                val result = registry.createModel(req.name, req.modelfile)
                val statusLine = CreateStatusLine(status = result.status)
                writeStringUtf8(apiJson.encodeToString(statusLine) + "\n")
                flush()
            }
        }

        // ── POST /api/copy ────────────────────────────────────────────────────
        post("/api/copy") {
            val registry = requireRegistry()
            val req      = safeReceive<CopyRequest>() ?: return@post

            if (!registry.copyModel(req.source, req.destination)) {
                call.respond(HttpStatusCode.NotFound,
                    OllamaError("source model '${req.source}' not found")
                )
                return@post
            }
            // Ollama devuelve 200 sin body en este endpoint
            call.respond(HttpStatusCode.OK, emptyMap<String, String>())
        }

        // ── DELETE /api/delete ────────────────────────────────────────────────
        delete("/api/delete") {
            val registry = requireRegistry()
            val req      = safeReceive<DeleteRequest>() ?: return@delete

            if (!registry.deleteModel(req.name)) {
                call.respond(HttpStatusCode.NotFound,
                    OllamaError("model '${req.name}' not found")
                )
                return@delete
            }
            call.respond(HttpStatusCode.OK, emptyMap<String, String>())
        }

        // ── POST /api/pull ────────────────────────────────────────────────────
        // Ollama retorna un stream NDJSON con { status, digest?, total?, completed? }
        // En Android no podemos descargar modelos de múltiples GB directamente,
        // informamos al usuario.
        post("/api/pull") {
            val req      = safeReceive<PullRequest>() ?: return@post
            val registry = requireRegistry()
            val known    = registry.knownRemoteModels()

            streamHeaders()
            call.respondBytesWriter(status = HttpStatusCode.OK) {
                if (req.name !in known) {
                    val line = PullStatusLine(
                        status = "error: model '${req.name}' not found. " +
                                "Use ADB to push .bin files: " +
                                "adb push model.bin /sdcard/Android/data/<pkg>/files/"
                    )
                    writeStringUtf8(apiJson.encodeToString(line) + "\n")
                } else {
                    val line = PullStatusLine(
                        status = "Pull from Android is not supported. " +
                                "Copy via ADB: adb push model.bin /sdcard/Android/data/<pkg>/files/"
                    )
                    writeStringUtf8(apiJson.encodeToString(line) + "\n")
                }
                flush()
            }
        }

        // ── POST /api/push ────────────────────────────────────────────────────
        post("/api/push") {
            streamHeaders()
            call.respondBytesWriter(status = HttpStatusCode.OK) {
                val line = PullStatusLine(
                    status = "Push is not supported from Android."
                )
                writeStringUtf8(apiJson.encodeToString(line) + "\n")
                flush()
            }
        }

        // ── POST /api/blobs/:digest ───────────────────────────────────────────
        // Stub: necesario para /api/create con archivos remotos
        post("/api/blobs/{digest}") {
            call.respond(HttpStatusCode.NotImplemented,
                OllamaError("blob upload not supported on Android")
            )
        }

        // ── HEAD /api/blobs/:digest ───────────────────────────────────────────
        head("/api/blobs/{digest}") {
            call.respond(HttpStatusCode.NotFound)
        }

        // ════════════════════════════════════════════════════════════════════
        // SECCIÓN 3 — ESTADO DEL SISTEMA
        // ════════════════════════════════════════════════════════════════════

        // ── GET /api/ps ───────────────────────────────────────────────────────
        // Lista modelos activos en memoria (running models)
        get("/api/ps") {
            call.respond(HttpStatusCode.OK, requireRegistry().listRunningModels())
        }

        // ── GET /api/version ──────────────────────────────────────────────────
        get("/api/version") {
            call.respond(HttpStatusCode.OK, requireRegistry().version())
        }

        // ════════════════════════════════════════════════════════════════════
        // SECCIÓN 4 — COMPATIBILIDAD OPENAI  /v1/...
        // Necesario para: Continue.dev, Cursor, LM Studio clients, Aider, etc.
        // ════════════════════════════════════════════════════════════════════

        route("/v1") {

            // ── POST /v1/chat/completions ─────────────────────────────────────
            // stream=true: SSE con chunks `data: {...}\n\n`
            // stream=false: objeto único estilo ChatCompletion
            post("/chat/completions") {
                val engine   = requireEngine() ?: return@post
                val registry = requireRegistry()
                val req      = safeReceive<OaiChatRequest>() ?: return@post

                if (req.messages.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest,
                        OllamaError("'messages' cannot be empty")
                    )
                    return@post
                }
                if (!registry.exists(req.model)) {
                    call.respond(HttpStatusCode.NotFound,
                        OllamaError("model '${req.model}' not found")
                    )
                    return@post
                }

                val chatMessages = req.messages.map {
                    ChatMessage(role = it.role, content = it.content ?: "")
                }
                val wantStream   = req.stream ?: false
                val completionId = "chatcmpl-${UUID.randomUUID()}"
                val created      = Instant.now().epochSecond

                if (wantStream) {
                    // SSE headers
                    call.response.header(HttpHeaders.ContentType, "text/event-stream")
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.response.header(HttpHeaders.Connection, "keep-alive")
                    call.response.header("X-Accel-Buffering", "no")

                    call.respondBytesWriter(status = HttpStatusCode.OK) {
                        engine.chatStream(messages = chatMessages)
                            .catch { e ->
                                val chunk = oaiStreamChunk(
                                    id      = completionId,
                                    created = created,
                                    model   = req.model,
                                    content = "\n[Error: ${e.message}]",
                                    finish  = "stop"
                                )
                                writeStringUtf8("data: ${apiJson.encodeToString(chunk)}\n\n")
                                writeStringUtf8("data: [DONE]\n\n")
                                flush()
                            }
                            .collect { token ->
                                if (token.text.isNotEmpty()) {
                                    val chunk = oaiStreamChunk(
                                        id      = completionId,
                                        created = created,
                                        model   = req.model,
                                        content = token.text,
                                        finish  = null
                                    )
                                    writeStringUtf8("data: ${apiJson.encodeToString(chunk)}\n\n")
                                    flush()
                                }
                                if (token.isDone) {
                                    // Chunk final con finish_reason
                                    val finalChunk = oaiStreamChunk(
                                        id      = completionId,
                                        created = created,
                                        model   = req.model,
                                        content = null,
                                        finish  = "stop"
                                    )
                                    writeStringUtf8("data: ${apiJson.encodeToString(finalChunk)}\n\n")
                                    writeStringUtf8("data: [DONE]\n\n")
                                    flush()
                                }
                            }
                    }
                } else {
                    val result = engine.chat(messages = chatMessages)
                    call.respond(HttpStatusCode.OK,
                        OaiChatResponse(
                            id = completionId,
                            created = created,
                            model = req.model,
                            choices = listOf(
                                OaiChoice(
                                    index = 0,
                                    message = OaiMessage(
                                        role = "assistant",
                                        content = result.text
                                    ),
                                    finishReason = "stop"
                                )
                            ),
                            usage = OaiUsage(
                                promptTokens = chatMessages.sumOf { estimateTokens(it.content) },
                                completionTokens = result.evalCount,
                                totalTokens = chatMessages.sumOf { estimateTokens(it.content) } + result.evalCount
                            )
                        )
                    )
                }
            }

            // ── POST /v1/completions  (legacy completions endpoint) ───────────
            post("/completions") {
                val engine   = requireEngine() ?: return@post
                val registry = requireRegistry()
                val req      = safeReceive<OaiCompletionRequest>() ?: return@post

                if (req.prompt.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest,
                        OllamaError("'prompt' is required")
                    )
                    return@post
                }
                if (!registry.exists(req.model)) {
                    call.respond(HttpStatusCode.NotFound,
                        OllamaError("model '${req.model}' not found")
                    )
                    return@post
                }

                val result = engine.generate(prompt = req.prompt)
                call.respond(HttpStatusCode.OK,
                    OaiCompletionResponse(
                        id = "cmpl-${UUID.randomUUID()}",
                        created = Instant.now().epochSecond,
                        model = req.model,
                        choices = listOf(
                            OaiCompletionChoice(
                                text = result.text,
                                index = 0,
                                finishReason = "stop"
                            )
                        ),
                        usage = OaiUsage(
                            promptTokens = estimateTokens(req.prompt),
                            completionTokens = result.evalCount,
                            totalTokens = estimateTokens(req.prompt) + result.evalCount
                        )
                    )
                )
            }

            // ── POST /v1/embeddings ───────────────────────────────────────────
            post("/embeddings") {
                val engine   = requireEngine() ?: return@post
                val registry = requireRegistry()
                val req      = safeReceive<OaiEmbeddingRequest>() ?: return@post

                if (req.input.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest,
                        OllamaError("'input' is required")
                    )
                    return@post
                }
                if (!registry.exists(req.model)) {
                    call.respond(HttpStatusCode.NotFound,
                        OllamaError("model '${req.model}' not found")
                    )
                    return@post
                }

                val embedding = engine.embed(req.input)
                call.respond(HttpStatusCode.OK,
                    OaiEmbeddingResponse(
                        data = listOf(OaiEmbeddingData(index = 0, embedding = embedding)),
                        model = req.model,
                        usage = OaiUsage(
                            promptTokens = estimateTokens(req.input),
                            totalTokens = estimateTokens(req.input)
                        )
                    )
                )
            }

            // ── GET /v1/models ────────────────────────────────────────────────
            get("/models") {
                val tags = requireRegistry().listModels()
                call.respond(HttpStatusCode.OK,
                    OaiModelsResponse(
                        data = tags.models.map { m ->
                            OaiModelData(
                                id = m.name,
                                created = Instant.now().epochSecond - 86_400L,
                                ownedBy = "local"
                            )
                        }
                    )
                )
            }

            // ── GET /v1/models/:model ─────────────────────────────────────────
            get("/models/{model}") {
                val name    = call.parameters["model"] ?: ""
                val registry = requireRegistry()

                if (!registry.exists(name)) {
                    call.respond(HttpStatusCode.NotFound,
                        OllamaError("model '$name' not found")
                    )
                    return@get
                }

                call.respond(HttpStatusCode.OK,
                    OaiModelData(
                        id = name,
                        created = Instant.now().epochSecond - 86_400L,
                        ownedBy = "local"
                    )
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers privados
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Cabeceras estándar para NDJSON streaming (Ollama nativo).
 * Extension de PipelineContext para poder llamarse directamente
 * dentro de los handlers de routing sin necesidad de `call.`.
 */
private fun PipelineContext<Unit, ApplicationCall>.streamHeaders() {
    call.response.header(HttpHeaders.ContentType, "application/x-ndjson")
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.response.header(HttpHeaders.Connection, "keep-alive")
    call.response.header("X-Accel-Buffering", "no")   // Desactiva buffer en Nginx
    call.response.header("Transfer-Encoding", "chunked")
}

private suspend fun PipelineContext<Unit, ApplicationCall>.requireEngine(): LlmEngine? {
    val engine = call.application.attributes.getOrNull(LlmEngineKey)
    if (engine == null || !engine.isLoaded) {
        call.respond(HttpStatusCode.ServiceUnavailable,
            OllamaError("model not loaded — retry in a few seconds")
        )
        return null
    }
    return engine
}

private fun PipelineContext<Unit, ApplicationCall>.requireRegistry(): ModelRegistry =
    call.application.attributes[ModelRegistryKey]

private suspend inline fun <reified T : Any> PipelineContext<Unit, ApplicationCall>.safeReceive(): T? {
    return runCatching { call.receive<T>() }.getOrElse {
        call.respond(HttpStatusCode.BadRequest,
            OllamaError("invalid JSON: ${it.message}")
        )
        null
    }
}

private fun isoNow(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

private fun estimateTokens(text: String): Int =
    (text.split(Regex("\\s+")).size * 1.33).toInt().coerceAtLeast(1)

// ─── Builders para chunks de stream ──────────────────────────────────────────

private fun buildChatChunk(
    model: String,
    content: String,
    done: Boolean,
    doneReason: String? = null
) = ChatStreamChunk(
    model = model,
    createdAt = isoNow(),
    message = OllamaMessage(role = "assistant", content = content),
    done = done,
    doneReason = doneReason
)

private fun buildChatFinal(
    model: String,
    doneReason: String,
    totalDuration: Long,
    promptEvalCount: Int,
    evalCount: Int
) = ChatFinalResponse(
    model = model,
    createdAt = isoNow(),
    message = OllamaMessage(role = "assistant", content = ""),
    done = true,
    doneReason = doneReason,
    totalDuration = totalDuration,
    loadDuration = 0L,
    promptEvalCount = promptEvalCount,
    promptEvalDuration = 0L,
    evalCount = evalCount,
    evalDuration = 0L
)

private fun oaiStreamChunk(
    id: String,
    created: Long,
    model: String,
    content: String?,
    finish: String?
) = OaiStreamChunk(
    id = id,
    created = created,
    model = model,
    choices = listOf(
        OaiStreamChoice(
            index = 0,
            delta = OaiDelta(
                role = if (content != null) "assistant" else null,
                content = content
            ),
            finishReason = finish
        )
    )
)