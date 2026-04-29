package hn.kwikstop.inferencelocal.service

import android.util.Log
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import io.ktor.http.HttpHeaders.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.w3c.dom.TypeInfo

// ─────────────────────────────────────────────────────────────────────────────
// Clave para pasar LlmInference al módulo Ktor sin DI framework
// ─────────────────────────────────────────────────────────────────────────────

val LlmInferenceKey = AttributeKey<LlmInference>("LlmInference")

// ─────────────────────────────────────────────────────────────────────────────
// Módulo Ktor  –  instala plugins y define las rutas
// ─────────────────────────────────────────────────────────────────────────────

fun Application.llmServerModule() {

    // ── Serialización JSON ────────────────────────────────────────────────────
    install(ContentNegotiation) {
        json(Json {
            prettyPrint        = false
            isLenient          = true
            ignoreUnknownKeys  = true
            encodeDefaults     = true
        })
    }

    // ── Manejo global de errores ──────────────────────────────────────────────
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(error = "Solicitud inválida: ${cause.message}")
            )
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(error = "Error interno: ${cause.message}")
            )
        }
    }

    // ── Rutas ─────────────────────────────────────────────────────────────────
    routing {

        // ── GET /  –  Verificación de que el servidor está vivo ───────────────
        get("/") {
            Log.d("KtorServer", "Petición recibida en /")
            //call.respondText("Servidor funcionando perfectamente")
            // Forzamos la respuesta con el serializador explícito
            val response = HealthResponse(status = "ok", model = "gemma-2b-it")
            val jsonString = Json.encodeToString(HealthResponse.serializer(), response)
            call.respondText(jsonString, io.ktor.http.ContentType.Application.Json)

        }

        // ── GET /api/health  –  Health check compatible con Ollama ───────────
        get("/api/health") {
            Log.d("KtorServer", "Petición recibida en /api/health")
            call.respond(
                HttpStatusCode.OK,
                HealthResponse(
                    status = "ok",
                    model  = "gemma-2b-it"
                )
            )
        }

        // ── POST /api/generate  –  Inferencia LLM ────────────────────────────
        // Formato de entrada (compatible con Ollama):
        //   { "prompt": "¿Qué es Kotlin?", "max_tokens": 256, "temperature": 0.8 }
        //
        // Formato de salida:
        //   { "response": "...", "model": "gemma-2b-it", "done": true }

        post("/api/generate") {
            // Obtener la instancia de LlmInference inyectada por el servicio
            val llm = call.application.attributes.getOrNull(LlmInferenceKey)
                ?: return@post call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse(error = "El modelo LLM no está disponible aún. Intente de nuevo.")
                )

            // Parsear el cuerpo de la solicitud
            val request = runCatching { call.receive<GenerateRequest>() }.getOrElse { e ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "JSON inválido: ${e.message}")
                )
                return@post
            }

            if (request.prompt.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "El campo 'prompt' no puede estar vacío.")
                )
                return@post
            }

            // ── Formatear el prompt para Gemma Instruction Tuned ─────────────
            // Gemma-2b-it usa el formato de chat "<start_of_turn>user\n...\n<end_of_turn>"
            val formattedPrompt = buildGemmaPrompt(request.prompt)
            if (request.stream) {
                call.respondTextWriter(contentType = io.ktor.http.ContentType.Text.Plain, writer = {
                    try {
                        // Llamada correcta con callback para streaming
                        llm.generateResponseAsync(formattedPrompt) { text, done ->
                            write(text)
                            flush()
                            if (done) {
                                write("[DONE]")
                            }
                        }
                    } catch (e: Exception) {
                        write("\n[Error de inferencia: ${e.message}]")
                        Log.e("KtorServer", "Error en stream", e)
                    }
                    write(formattedPrompt)
                })
            }else{
                // ── Inferencia en hilo IO para no bloquear Ktor ───────────────────
                val responseText = withContext(Dispatchers.IO) {

                    llm.generateResponse(formattedPrompt)
                }

                call.respond(
                    HttpStatusCode.OK,
                    GenerateResponse(
                        response = responseText.trim(),
                        model    = "gemma-2b-it",
                        done     = true
                    )
                )
            }

        }

        // ── POST /api/chat  –  Formato conversacional (bonus) ────────────────
        // Entrada: { "messages": [{"role":"user","content":"..."}] }
        post("/api/chat") {
            val llm = call.application.attributes.getOrNull(LlmInferenceKey)
                ?: return@post call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse(error = "El modelo no está disponible.")
                )

            val body = runCatching { call.receive<ChatRequest>() }.getOrElse { e ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "JSON inválido: ${e.message}")
                )
                return@post
            }

            // Construir prompt con historial de mensajes
            val prompt = buildGemmaChatPrompt(body.messages)

            val responseText = withContext(Dispatchers.IO) {
                llm.generateResponse(prompt)
            }

            call.respond(
                HttpStatusCode.OK,
                ChatResponse(
                    message = ChatMessage(role = "assistant", content = responseText.trim()),
                    done    = true
                )
            )
        }
    }

}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers de formato de prompt  (Gemma Instruction Tuned Template)
// https://ai.google.dev/gemma/docs/formatting
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Envuelve un prompt de texto plano en el template de Gemma-IT.
 */
private fun buildGemmaPrompt(userText: String): String = buildString {
    append("<start_of_turn>user\n")
    append(userText.trim())
    append("\n<end_of_turn>\n")
    append("<start_of_turn>model\n")
}

/**
 * Construye un prompt multi-turno a partir de una lista de mensajes.
 */
private fun buildGemmaChatPrompt(messages: List<ChatMessage>): String = buildString {
    for (msg in messages) {
        when (msg.role.lowercase()) {
            "user" -> {
                append("<start_of_turn>user\n")
                append(msg.content.trim())
                append("\n<end_of_turn>\n")
            }
            "assistant", "model" -> {
                append("<start_of_turn>model\n")
                append(msg.content.trim())
                append("\n<end_of_turn>\n")
            }
            "system" -> {
                // Gemma no tiene rol "system" oficial; lo incluimos como contexto del usuario
                append("<start_of_turn>user\n")
                append("[Sistema]: ${msg.content.trim()}")
                append("\n<end_of_turn>\n")
            }
        }
    }
    // Indicar al modelo que debe responder ahora
    append("<start_of_turn>model\n")
}

// ─────────────────────────────────────────────────────────────────────────────
// Data classes adicionales para /api/chat
// ─────────────────────────────────────────────────────────────────────────────

@kotlinx.serialization.Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@kotlinx.serialization.Serializable
data class ChatRequest(
    val messages: List<ChatMessage>,
    val max_tokens: Int = 512,
    val temperature: Float = 0.8f
)

@kotlinx.serialization.Serializable
data class ChatResponse(
    val message: ChatMessage,
    val model: String = "gemma-2b-it",
    val done: Boolean = true
)

// ─────────────────────────────────────────────────────────────────────────────
// Data classes para la API REST
// ─────────────────────────────────────────────────────────────────────────────
@kotlinx.serialization.Serializable
data class HealthResponse(
    val status: String,
    val model: String
)

@kotlinx.serialization.Serializable
data class ErrorResponse(
    val error: String
)

@kotlinx.serialization.Serializable
data class GenerateRequest(
    val prompt: String,
    val max_tokens: Int = 512,
    val temperature: Float = 0.8f,
    val stream: Boolean = false
)

@kotlinx.serialization.Serializable
data class GenerateResponse(
    val response: String,
    val model: String,
    val done: Boolean
)





