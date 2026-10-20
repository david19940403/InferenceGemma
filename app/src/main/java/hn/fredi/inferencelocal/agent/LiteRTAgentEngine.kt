package hn.fredi.inferencelocal.agent

import android.content.Context
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlinx.serialization.json.*

/** One loaded engine, a fresh conversation per step, no persisted task history. */
class LiteRTAgentEngine(private val context: Context, private val config: AgentConfig) : AgentInference, AutoCloseable {
    private val lock = Mutex()
    private var engine: Engine? = null
    private fun initialize(): Engine {
        engine?.let { return it }
        val file = File(config.model_path)
        require(file.isFile && file.extension == "litertlm") { "Se requiere un modelo .litertlm accesible por la APK" }
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val backend = when (config.backend) {
            "NPU" -> { android.system.Os.setenv("ADSP_LIBRARY_PATH", nativeDir, true); Backend.NPU(nativeLibraryDir = nativeDir) }
            "GPU" -> Backend.GPU()
            else -> Backend.CPU()
        }
        val created = Engine(EngineConfig(modelPath = file.path, backend = backend, maxNumTokens = 1984, cacheDir = context.cacheDir.path))
        try { created.initialize() } catch (e: Exception) { runCatching { created.close() }; throw e }
        engine = created
        return created
    }
    @OptIn(ExperimentalApi::class)
    override suspend fun generate(prompt: String, tools: List<RemoteTool>): String = lock.withLock {
        withContext(Dispatchers.IO) {
            require(tools.none { it.name == "finish_transaction" })
            val descriptions = tools.map { buildJsonObject { put("name", it.name); put("description", it.description); put("parameters", it.parameters) } } + buildJsonObject {
                put("name", "finish_transaction"); put("description", "Finaliza la transacción con una respuesta al usuario")
                put("parameters", Json.parseToJsonElement("""{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}"""))
            }
            val providers = descriptions.map { description -> tool(object : OpenApiTool {
                override fun getToolDescriptionJsonString(): String = description.toString()
                override fun execute(paramsJsonString: String): String = error("La ejecución automática está deshabilitada")
            }) }
            ExperimentalFlags.enableConversationConstrainedDecoding = true
            val conversation = initialize().createConversation(ConversationConfig(tools = providers, automaticToolCalling = false,
                samplerConfig = SamplerConfig(temperature = 0.0, topK = 1, topP = 1.0)))
            try {
                val calls = mutableListOf<ToolCall>()
                var outputBytes = 0
                conversation.sendMessageAsync(prompt).collect { chunk ->
                    outputBytes += chunk.contents.contents.filterIsInstance<Content.Text>().sumOf { it.text.toByteArray().size }
                    require(outputBytes <= 4096) { "Respuesta del modelo demasiado larga" }
                    calls.addAll(chunk.toolCalls)
                }
                require(calls.size == 1) { "El modelo debe emitir exactamente una llamada a herramienta" }
                val call = calls.single()
                val args = buildJsonObject { call.arguments.forEach { (name, value) -> require(value is String); put(name, value) } }
                buildJsonObject {
                    if (call.name == "finish_transaction") put("final_answer", args.getValue("answer"))
                    else put("tool_call", buildJsonObject { put("name", call.name); put("arguments", args) })
                }.toString()
            } finally { conversation.close() }
        }
    }
    override fun close() { engine?.close(); engine = null }
}
