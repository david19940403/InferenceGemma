package hn.fredi.inferencelocal.api.routes

import hn.fredi.inferencelocal.api.*
import hn.fredi.inferencelocal.api.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.encodeToString
import java.util.*

fun Route.inferenceRoutes() {
    post("/api/generate") {
        val engine = requireEngine() ?: run {
            call.respond(HttpStatusCode.ServiceUnavailable, OllamaError("engine not initialized"))
            return@post
        }
        val registry = requireRegistry()
        val req = safeReceive<GenerateRequest>() ?: return@post

        if (req.prompt.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, OllamaError("'prompt' is required"))
            return@post
        }
        
        req.options?.keepAlive?.let { keepAliveSec ->
            engine.inactivityTimeoutMs = if (keepAliveSec <= 0) 0L else keepAliveSec * 1000L
        }
        
        if (!registry.exists(req.model)) {
            call.respond(HttpStatusCode.NotFound, OllamaError("model '${req.model}' not found"))
            return@post
        }

        val modelPath = registry.filePath(req.model) ?: run {
            call.respond(HttpStatusCode.NotFound, OllamaError("model file for '${req.model}' not found"))
            return@post
        }

        try {
            engine.load(modelPath, req.options)
            registry.setActiveModel(req.model)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, OllamaError("Failed to load model: ${e.message}"))
            return@post
        }

        val wantStream = req.stream ?: true
        val chatMessages = listOf(
            ChatMessage(role = "system", content = req.system ?: "You are a helpful assistant."),
            ChatMessage(role = "user", content = req.prompt, images = req.images)
        )

        if (wantStream) {
            streamHeaders()
            call.respondBytesWriter(status = HttpStatusCode.OK) {
                var accumulated = ""
                engine.chatStream(chatMessages, options = req.options)
                    .catch { e ->
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
                                promptEvalCount = engine.estimateTokens(req.prompt),
                                promptEvalDuration = 0L,
                                evalCount = engine.estimateTokens(accumulated),
                                evalDuration = 0L
                            )
                            writeStringUtf8(apiJson.encodeToString(finalChunk) + "\n")
                            flush()
                        }
                    }
            }
        } else {
            val startMs = System.currentTimeMillis()
            val result = engine.chat(chatMessages, options = req.options)
            val elapsed = System.currentTimeMillis() - startMs

            call.respond(HttpStatusCode.OK, GenerateFinalChunk(
                model = req.model,
                createdAt = isoNow(),
                response = result.text,
                done = true,
                doneReason = "stop",
                totalDuration = elapsed * 1_000_000L,
                loadDuration = 0L,
                promptEvalCount = engine.estimateTokens(req.prompt),
                promptEvalDuration = 0L,
                evalCount = result.evalCount,
                evalDuration = 0L,
                context = arrayListOf(0)
            ))
        }
    }

    post("/api/chat") {
        val engine = requireEngine() ?: run {
            call.respond(HttpStatusCode.ServiceUnavailable, OllamaError("engine not initialized"))
            return@post
        }
        val registry = requireRegistry()
        val req = safeReceive<ChatRequest>() ?: return@post

        if (!registry.exists(req.model)) {
            call.respond(HttpStatusCode.NotFound, OllamaError("model '${req.model}' not found"))
            return@post
        }
        
        req.options?.keepAlive?.let { keepAliveSec ->
            engine.inactivityTimeoutMs = if (keepAliveSec <= 0) 0L else keepAliveSec * 1000L
        }
        
        val modelPath = registry.filePath(req.model) ?: run {
            call.respond(HttpStatusCode.NotFound, OllamaError("model file for '${req.model}' not found"))
            return@post
        }

        try {
            engine.load(modelPath, req.options)
            registry.setActiveModel(req.model)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, OllamaError("Failed to load model: ${e.message}"))
            return@post
        }

        if (req.messages.isEmpty()) {
            val reason = if ((req.keepAlive ?: 1) <= 0) "unload" else "load"
            if (reason == "unload") engine.unload()
            call.respond(HttpStatusCode.OK, ChatUnloadResponse(
                model = req.model,
                createdAt = isoNow(),
                message = OllamaMessage(role = "assistant", content = ""),
                doneReason = reason,
                done = true
            ))
            return@post
        }

        val toolManager = call.application.attributes[ToolManagerKey]
        val messagesWithTools = req.messages.toMutableList()
        val toolInstructions = toolManager.getSystemInstructions(req.tools)
        
        if (messagesWithTools.none { it.role == "system" }) {
            messagesWithTools.add(0, ChatMessage(role = "system", content = toolInstructions))
        } else {
            val systemIdx = messagesWithTools.indexOfFirst { it.role == "system" }
            messagesWithTools[systemIdx] = messagesWithTools[systemIdx].copy(
                content = "${messagesWithTools[systemIdx].content}\n\n$toolInstructions"
            )
        }

        val wantStream = req.stream ?: true
        val startMs = System.currentTimeMillis()

        if (wantStream) {
            streamHeaders()
            call.respondBytesWriter {
                val buffer = StringBuilder()
                var isToolCallCandidate = true
                var firstTokenReceived = false
                var evalCount = 0

                engine.chatStream(messagesWithTools, options = req.options).collect { token ->
                    if (token.text.isNotEmpty()) {
                        if (!firstTokenReceived) {
                            firstTokenReceived = true
                            val trimmed = token.text.trimStart()
                            if (!trimmed.startsWith("{") && !trimmed.startsWith("```")) {
                                isToolCallCandidate = false
                            }
                        }
                        
                        buffer.append(token.text)
                        evalCount++

                        if (!isToolCallCandidate) {
                            val chunk = buildChatChunk(req.model, token.text, false)
                            writeStringUtf8(apiJson.encodeToString(chunk) + "\n")
                            flush()
                        }
                    }
                }

                if (isToolCallCandidate) {
                    val fullText = buffer.toString()
                    val toolCallCandidate = toolManager.detectToolCall(fullText, req.tools)

                    if (toolCallCandidate != null) {
                        val (toolName, args) = toolCallCandidate
                        if (toolManager.isInternal(toolName)) {
                            val tool = toolManager.getInternalTool(toolName)!!
                            writeStringUtf8(apiJson.encodeToString(buildChatChunk(req.model, tool.loadingMessage, false)) + "\n")
                            flush()

                            val result = try { tool.call(args) } catch (e: Exception) { "Error: ${e.message}" }
                            messagesWithTools.add(ChatMessage(role = "assistant", content = fullText))
                            messagesWithTools.add(ChatMessage(role = "tool", content = result))
                            
                            var innerAccumulated = ""
                            engine.chatStream(messagesWithTools, options = req.options).collect { t ->
                                if (t.text.isNotEmpty()) {
                                    innerAccumulated += t.text
                                    writeStringUtf8(apiJson.encodeToString(buildChatChunk(req.model, t.text, false)) + "\n")
                                    flush()
                                }
                                if (t.isDone) {
                                    val elapsed = System.currentTimeMillis() - startMs
                                    writeStringUtf8(apiJson.encodeToString(buildChatFinal(req.model, "stop", elapsed * 1_000_000L, 0, engine.estimateTokens(messagesWithTools.last().content), 0, engine.estimateTokens(innerAccumulated))) + "\n")
                                    flush()
                                }
                            }
                        } else {
                            val toolCallId = "call_" + UUID.randomUUID().toString().substring(0, 8)
                            writeStringUtf8(apiJson.encodeToString(buildChatChunkWithToolCall(req.model, toolName, args, toolCallId)) + "\n")
                            writeStringUtf8(apiJson.encodeToString(buildChatFinal(req.model, "tool_calls", (System.currentTimeMillis() - startMs) * 1_000_000L, 0, engine.estimateTokens(req.messages.last().content), 0, evalCount)) + "\n")
                            flush()
                        }
                    } else {
                        writeStringUtf8(apiJson.encodeToString(buildChatChunk(req.model, fullText, false)) + "\n")
                        writeStringUtf8(apiJson.encodeToString(buildChatFinal(req.model, "stop", (System.currentTimeMillis() - startMs) * 1_000_000L, 0, engine.estimateTokens(req.messages.last().content), 0, evalCount)) + "\n")
                        flush()
                    }
                } else {
                    writeStringUtf8(apiJson.encodeToString(buildChatFinal(req.model, "stop", (System.currentTimeMillis() - startMs) * 1_000_000L, 0, engine.estimateTokens(req.messages.last().content), 0, evalCount)) + "\n")
                    flush()
                }
            }
        } else {
            val firstResponse = engine.chat(messagesWithTools, options = req.options)
            val toolCallCandidate = toolManager.detectToolCall(firstResponse.text, req.tools)
            
            if (toolCallCandidate != null) {
                val (toolName, args) = toolCallCandidate
                if (toolManager.isInternal(toolName)) {
                    val tool = toolManager.getInternalTool(toolName)!!
                    val result = try { tool.call(args) } catch (e: Exception) { "Error: ${e.message}" }
                    
                    messagesWithTools.add(ChatMessage(role = "assistant", content = firstResponse.text))
                    messagesWithTools.add(ChatMessage(role = "tool", content = result))
                    
                    val secondResponse = engine.chat(messagesWithTools, options = req.options)
                    val elapsed = System.currentTimeMillis() - startMs
                    call.respond(HttpStatusCode.OK, ChatFinalResponse(
                        model = req.model, createdAt = isoNow(),
                        message = OllamaMessage(role = "assistant", content = secondResponse.text),
                        done = true, doneReason = "stop",
                        totalDuration = elapsed * 1_000_000L,
                        loadDuration = 0L,
                        promptEvalCount = engine.estimateTokens(req.messages.last().content),
                        promptEvalDuration = 0L,
                        evalCount = engine.estimateTokens(secondResponse.text),
                        evalDuration = 0L
                    ))
                    return@post
                } else {
                    val elapsed = System.currentTimeMillis() - startMs
                    val toolCallId = "call_" + UUID.randomUUID().toString().substring(0, 8)
                    call.respond(HttpStatusCode.OK, ChatFinalResponse(
                        model = req.model, createdAt = isoNow(),
                        message = OllamaMessage(
                            role = "assistant", content = "",
                            toolCalls = listOf(ToolCall(id = toolCallId, function = ToolCallFunction(name = toolName, arguments = apiJson.encodeToString(args))))
                        ),
                        done = true, doneReason = "tool_calls",
                        totalDuration = elapsed * 1_000_000L,
                        loadDuration = 0L,
                        promptEvalCount = engine.estimateTokens(req.messages.last().content),
                        promptEvalDuration = 0L,
                        evalCount = firstResponse.evalCount,
                        evalDuration = 0L
                    ))
                    return@post
                }
            }

            val elapsed = System.currentTimeMillis() - startMs
            call.respond(HttpStatusCode.OK, ChatFinalResponse(
                model = req.model, createdAt = isoNow(),
                message = OllamaMessage(role = "assistant", content = firstResponse.text),
                done = true, doneReason = "stop",
                totalDuration = elapsed * 1_000_000L,
                loadDuration = 0L,
                promptEvalCount = engine.estimateTokens(req.messages.last().content),
                promptEvalDuration = 0L,
                evalCount = engine.estimateTokens(firstResponse.text),
                evalDuration = 0L
            ))
        }
    }

    post("/api/embed") {
        val engine = requireEngine() ?: run {
            call.respond(HttpStatusCode.ServiceUnavailable, OllamaError("engine not initialized"))
            return@post
        }
        val registry = requireRegistry()
        val req = safeReceive<EmbedRequest>() ?: return@post

        if (!registry.exists(req.model)) {
            call.respond(HttpStatusCode.NotFound, OllamaError("model '${req.model}' not found"))
            return@post
        }

        val texts = req.input?.let { listOf(it) } ?: req.prompt?.let { listOf(it) } ?: run {
            call.respond(HttpStatusCode.BadRequest, OllamaError("'input' or 'prompt' is required"))
            return@post
        }

        call.respond(HttpStatusCode.OK, EmbedResponse(
            model = req.model,
            embeddings = texts.map { engine.embed(it) }
        ))
    }
}

// ─── Internal Helpers ────────────────────────────────────────────────────────

private fun buildChatChunk(model: String, content: String, done: Boolean) = ChatStreamChunk(
    model = model, createdAt = isoNow(),
    message = OllamaMessage(role = "assistant", content = content),
    done = done
)

private fun buildChatChunkWithToolCall(model: String, toolName: String, args: Map<String, Any>, callId: String) = ChatStreamChunk(
    model = model, createdAt = isoNow(),
    message = OllamaMessage(
        role = "assistant", content = "",
        toolCalls = listOf(ToolCall(id = callId, function = ToolCallFunction(name = toolName, arguments = apiJson.encodeToString(args))))
    ),
    done = false
)

private fun buildChatFinal(
    model: String,
    doneReason: String,
    totalDuration: Long,
    loadDuration: Long,
    promptEvalCount: Int,
    promptEvalDuration: Long,
    evalCount: Int,
    evalDuration: Long = 0L
) = ChatFinalResponse(
    model = model, createdAt = isoNow(),
    message = OllamaMessage(role = "assistant", content = ""),
    done = true, doneReason = doneReason,
    totalDuration = totalDuration, loadDuration = loadDuration,
    promptEvalCount = promptEvalCount, promptEvalDuration = promptEvalDuration,
    evalCount = evalCount, evalDuration = evalDuration
)
