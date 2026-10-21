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
class LiteRTAgentEngine(private val context: Context, private val settings: EngineSettings) : AgentInference, AutoCloseable {
    private val lock = Mutex()
    private var engine: Engine? = null
    @OptIn(ExperimentalApi::class)
    private fun initialize(): Engine {
        engine?.let { return it }
        settings.validate()
        val file = File(settings.modelPath)
        require(file.isFile && file.extension == "litertlm") { "Se requiere un modelo .litertlm accesible por la APK" }
        val memory = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memory)
        require(memory.availMem / 1024 / 1024 >= settings.minAvailableRamMb) { "RAM disponible insuficiente para el umbral configurado" }
        EngineOwnership.claim("terminal")
        EngineMonitor.update { it.copy(phase = EnginePhase.LOADING, model = file.name, backend = "—", contextTokens = settings.contextTokens, message = "Cargando ${file.name}…", error = null) }
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val started = android.os.SystemClock.elapsedRealtime()
        val backend = when (settings.backend) {
            "NPU" -> { android.system.Os.setenv("ADSP_LIBRARY_PATH", nativeDir, true); Backend.NPU(nativeLibraryDir = nativeDir) }
            "GPU" -> Backend.GPU()
            else -> Backend.CPU()
        }
        ExperimentalFlags.enableBenchmark = true
        val created = Engine(EngineConfig(modelPath = file.path, backend = backend, maxNumTokens = settings.contextTokens, cacheDir = context.cacheDir.path))
        try { created.initialize() } catch (e: Exception) { runCatching { created.close() }; EngineOwnership.release("terminal"); throw e }
        engine = created
        EngineMonitor.update { it.copy(phase = EnginePhase.READY, modelLoaded = true, backend = settings.backend, loadTimeMs = android.os.SystemClock.elapsedRealtime() - started, message = "Engine listo · ${settings.backend}") }
        return created
    }
    suspend fun warmup() = lock.withLock { withContext(Dispatchers.IO) { initialize(); Unit } }
    @OptIn(ExperimentalApi::class)
    override suspend fun generate(prompt: String, tools: List<RemoteTool>): String = lock.withLock {
        withContext(Dispatchers.IO) {
            require(tools.none { it.name == "finish_transaction" })
            val descriptions = tools.map(ToolCatalogBudget::definition) + ToolCatalogBudget.finishDefinition
            val providers = descriptions.map { description -> tool(object : OpenApiTool {
                override fun getToolDescriptionJsonString(): String = description.toString()
                override fun execute(paramsJsonString: String): String = error("La ejecución automática está deshabilitada")
            }) }
            ExperimentalFlags.enableConversationConstrainedDecoding = true
            val conversation = initialize().createConversation(ConversationConfig(tools = providers, automaticToolCalling = false, extraContext = mapOf("enable_thinking" to false),
                systemInstruction = Contents.of("Emite exactamente una llamada por turno. No combines una herramienta con finish_transaction en el mismo turno."),
                samplerConfig = SamplerConfig(temperature = settings.temperature, topK = settings.topK, topP = settings.topP)))
            EngineMonitor.update { it.copy(phase = EnginePhase.INFERENCING, message = "Inferencia local en curso", error = null,
                catalogToolCount = tools.count { tool -> tool.transport.type == "ssh" },
                catalogTokensEstimate = ToolCatalogBudget.estimatedTokens(tools), catalogBudgetTokens = settings.toolBudgetTokens) }
            try {
                val calls = mutableListOf<ToolCall>()
                val text = StringBuilder()
                var outputBytes = 0
                conversation.sendMessageAsync(prompt).collect { chunk ->
                    outputBytes += chunk.contents.contents.filterIsInstance<Content.Text>().sumOf { it.text.toByteArray().size }
                    text.append(chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
                    require(outputBytes <= 4096) { "Respuesta del modelo demasiado larga" }
                    calls.addAll(chunk.toolCalls)
                }
                runCatching { conversation.getBenchmarkInfo() }.getOrNull()?.let { benchmark ->
                    EngineMonitor.update { it.copy(
                        ttftMs = (benchmark.timeToFirstTokenInSecond * 1000).toLong().coerceAtLeast(0),
                        tokensPerSecond = benchmark.lastDecodeTokensPerSecond.takeIf { speed -> speed.isFinite() && speed >= 0 } ?: 0.0,
                        inputTokens = benchmark.lastPrefillTokenCount.coerceAtLeast(0),
                        outputTokens = it.outputTokens + benchmark.lastDecodeTokenCount.coerceAtLeast(0),
                        evaluations = it.evaluations + 1
                    ) }
                }
                ModelOutputCodec.encode(calls.map { call -> call.name to buildJsonObject {
                    call.arguments.forEach { (name, value) -> require(value is String); put(name, value) }
                } }, text.toString())
            } finally {
                conversation.close()
                EngineMonitor.update { it.copy(phase = EnginePhase.READY, message = "Engine listo · ${settings.backend}") }
            }
        }
    }
    override fun close() {
        EngineMonitor.update { it.copy(phase = EnginePhase.RELEASING, message = "Liberando modelo…") }
        try { engine?.close() } finally {
            engine = null; EngineOwnership.release("terminal")
            EngineMonitor.update { it.copy(phase = EnginePhase.IDLE, modelLoaded = false, backend = "—", message = "Modelo descargado") }
        }
    }
}
