package hn.fredi.inferencelocal

import android.app.ActivityManager
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in, on-device measurements. Never changes the operator's model settings. */
@RunWith(AndroidJUnit4::class)
class EngineContextProbeTest {
    @OptIn(ExperimentalApi::class)
    @Test fun measureActualContext() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runNativeProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = args.getString("modelPath") ?: error("modelPath required")
        val tokens = args.getString("contextTokens", "4096")!!.toInt()
        require(tokens in 1024..131072)
        val backendName = args.getString("backend", "GPU")!!
        require(backendName in listOf("GPU", "CPU"))
        val runId = java.util.UUID.randomUUID().toString()
        val recallOnly = args.getString("recallOnly") == "true"
        val report = File(context.filesDir, "context-probe.jsonl")
        fun record(kind: String, fields: JsonObjectBuilder.() -> Unit = {}) {
            val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            val line = buildJsonObject {
                put("event", kind); put("context_tokens", tokens)
                put("run_id", runId); put("timestamp_ms", System.currentTimeMillis()); put("backend", backendName)
                put("available_ram_mb", memory.availMem / 1048576)
                put("process_pss_mb", Debug.getPss() / 1024)
                fields()
            }.toString()
            report.appendText(line + "\n")
            android.util.Log.i("EngineContextProbe", line)
        }
        require(File(path).isFile) { "Model not accessible to target app" }
        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableConversationConstrainedDecoding = false
        record("starting") { put("model", File(path).name) }
        val engine = Engine(EngineConfig(modelPath = path, backend = if (backendName == "GPU") Backend.GPU() else Backend.CPU(), maxNumTokens = tokens, cacheDir = context.cacheDir.path))
        val loadStart = SystemClock.elapsedRealtime()
        try {
            engine.initialize()
            record("loaded") { put("load_ms", SystemClock.elapsedRealtime() - loadStart) }
            val freeAfterLoad = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            require(freeAfterLoad.availMem / 1048576 >= 768) { "Probe stopped: less than 768 MiB RAM available after loading" }
            var ratio = 1.0
            var overhead = 40
            var firstPrefill = 0
            val cases = if (recallOnly) listOf("calibration", "calibration_check", "recall_short", "recall", "recall_varied") else
                listOf("calibration", "calibration_check", "half", "near_limit", "overflow", "recovery", "recall_short", "recall", "recall_again", "recall_varied") +
                if (tokens == 4096) listOf("generation") else emptyList()
            for (label in cases) {
                val freeBeforeRequest = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                require(freeBeforeRequest.availMem / 1048576 >= 768) { "Probe stopped: less than 768 MiB RAM available before $label" }
                val repeats = when (label) {
                    "calibration" -> 128
                    "calibration_check" -> 256
                    "half" -> ((tokens / 2 - overhead) / ratio).toInt().coerceAtLeast(1)
                    "near_limit", "recall", "recall_again", "recall_varied" -> ((tokens - 256 - overhead) / ratio).toInt().coerceAtLeast(1)
                    "recovery", "generation", "recall_short" -> 128
                    else -> ((tokens + 256 - overhead) / ratio).toInt()
                }
                val prompt = if (label == "generation") {
                    "Write every integer from 1 to 100 in order, separated by spaces. Nothing else."
                } else if (label == "recall_varied") {
                    "Remember these codes. START=CEDAR7.\n" + "oak river cloud stone ".repeat(repeats / 8) +
                        "\nMIDDLE=ORBIT9.\n" + "leaf hill sand water ".repeat(repeats / 8) +
                        "\nEND=MAPLE3. Reply only with the START, MIDDLE and END codes in that order."
                } else if (label.startsWith("recall")) {
                    "Remember these codes. START=CEDAR7.\n" + "x ".repeat(repeats / 2) +
                        "\nMIDDLE=ORBIT9.\n" + "x ".repeat(repeats / 2) +
                        "\nEND=MAPLE3. Reply only with the START, MIDDLE and END codes in that order."
                } else "Read the following data, do not repeat it:\n" + "x ".repeat(repeats) + "\nReply with exactly OK. No explanation."
                val started = SystemClock.elapsedRealtime()
                record("request_start") { put("case", label); put("prompt_chars", prompt.length) }
                val conversation = engine.createConversation(ConversationConfig(extraContext = mapOf("enable_thinking" to false)))
                try {
                    val response = StringBuilder()
                    withTimeout(120_000) {
                        conversation.sendMessageAsync(prompt).collect { message ->
                            response.append(message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
                            if (response.length > 512) { conversation.cancelProcess(); error("Output exceeded probe budget") }
                        }
                    }
                    val benchmark = conversation.getBenchmarkInfo()
                    if (label == "calibration") firstPrefill = benchmark.lastPrefillTokenCount
                    if (label == "calibration_check") {
                        ratio = ((benchmark.lastPrefillTokenCount - firstPrefill).toDouble() / 128).coerceIn(0.5, 4.0)
                        overhead = (firstPrefill - 128 * ratio).toInt()
                    }
                    record("request_ok") {
                        put("case", label); put("repeats", repeats); put("prefill_tokens", benchmark.lastPrefillTokenCount)
                        put("decode_tokens", benchmark.lastDecodeTokenCount); put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                        put("ttft_ms", (benchmark.timeToFirstTokenInSecond * 1000).toLong()); put("decode_tps", benchmark.lastDecodeTokensPerSecond)
                        put("answer", response.toString().take(100))
                        if (label == "generation") put("generation_pass", Regex("\\d+").findAll(response).map { it.value.toIntOrNull() }.toList() == (1..100).toList())
                        if (label.startsWith("recall")) put("recall_pass", Regex("CEDAR7[\\s\\S]*ORBIT9[\\s\\S]*MAPLE3").containsMatchIn(response))
                    }
                } catch (error: Exception) {
                    record("request_error") { put("case", label); put("repeats", repeats); put("elapsed_ms", SystemClock.elapsedRealtime() - started); put("error", error.message.orEmpty().take(1000)) }
                } finally { conversation.close() }
            }
            if (tokens == 4096 && !recallOnly) {
                ExperimentalFlags.enableConversationConstrainedDecoding = true
                val definitions = listOf(
                    """{"name":"locate_file","description":"Find a file by name","parameters":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}""",
                    """{"name":"finish_transaction","description":"Report the result to the user","parameters":{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}}"""
                )
                val providers = definitions.map { definition -> tool(object : OpenApiTool {
                    override fun getToolDescriptionJsonString() = definition
                    override fun execute(paramsJsonString: String): String = error("Probe never executes external tools")
                }) }
                for (correctRole in listOf(true, false)) {
                    val conversation = engine.createConversation(ConversationConfig(
                        tools = providers, automaticToolCalling = false,
                        extraContext = mapOf("enable_thinking" to false),
                        samplerConfig = SamplerConfig(temperature = 0.0, topK = 1, topP = 1.0)
                    ))
                    try {
                        val calls = mutableListOf<ToolCall>()
                        withTimeout(60_000) {
                            conversation.sendMessageAsync("Call locate_file with name report.txt. After receiving its result, call finish_transaction with the returned path as your answer.").collect { chunk ->
                                calls.addAll(chunk.toolCalls)
                            }
                        }
                        record("tool_first_turn") {
                            put("correct_role", correctRole); put("stream_call_count", calls.size)
                            put("calls", calls.joinToString().take(500))
                        }
                        require(calls.distinct().single().name == "locate_file") { "Expected locate_file" }
                        val reply = if (correctRole) Message.tool(Contents.of(Content.ToolResponse("locate_file", mapOf("path" to "C:/probe/report.txt", "found" to true))))
                            else Message.user("Tool locate_file returned: {\"path\":\"C:/probe/report.txt\",\"found\":true}")
                        val nextCalls = mutableListOf<ToolCall>()
                        withTimeout(60_000) { conversation.sendMessageAsync(reply).collect { nextCalls.addAll(it.toolCalls) } }
                        record("tool_followup") {
                            put("correct_role", correctRole); put("calls", nextCalls.joinToString().take(500))
                            put("pass", nextCalls.distinct().singleOrNull()?.let { it.name == "finish_transaction" && it.arguments["answer"] == "C:/probe/report.txt" } == true)
                        }
                    } catch (error: Exception) {
                        record("tool_error") { put("correct_role", correctRole); put("error", error.message.orEmpty().take(1000)) }
                    } finally { conversation.close() }
                }
            }
        } catch (error: Exception) {
            record("engine_error") { put("error", error.message.orEmpty().take(1000)) }
        } finally {
            runCatching { engine.close() }
            record("closed")
        }
    }
}
