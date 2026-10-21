package hn.fredi.inferencelocal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hn.fredi.inferencelocal.agent.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real model and worker, synthetic catalog/results. No network actions or SSH. */
@RunWith(AndroidJUnit4::class)
class ToolDiscoveryDeviceTest {
    @Test fun modelCanRequestMissingCapability() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runAgentDiscovery") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = EngineSettingsStore(context).load().copy(contextTokens = 4096, toolBudgetTokens = 1500, backend = "GPU")
        val emptySchema = Json.parseToJsonElement("""{"type":"object","properties":{},"required":[]}""").jsonObject
        val initial = (1..16).map { RemoteTool("probe_cpu_$it", "Consulta información del procesador, no busca archivos", Transport("ssh", "probe", "cpu.ps1"), emptySchema) }
        val find = RemoteTool("probe_find_file", "Busca un archivo por nombre y devuelve su ruta", Transport("ssh", "probe", "find.ps1 {name}"),
            Json.parseToJsonElement("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""").jsonObject)
        val searches = mutableListOf<String>()
        val steps = mutableListOf<JsonObject>()
        var executions = 0
        val engine = LiteRTAgentEngine(context, settings)
        try {
            val worker = AgenticLoopWorker({ initial }, AgentInference { prompt, tools ->
                val answer = engine.generate(prompt, tools)
                steps += buildJsonObject {
                    put("external_tools", tools.count { it.transport.type == "ssh" })
                    put("estimated_catalog_tokens", ToolCatalogBudget.estimatedTokens(tools))
                    put("input_tokens", EngineMonitor.state.value.inputTokens)
                    put("output", answer)
                }
                answer
            }, ToolExecutor { host, command ->
                assertEquals("probe", host); assertTrue(command.contains("find.ps1")); assertTrue(command.contains("reporte.txt"))
                executions++; CommandOutput(0, """{"found":true,"path":"C:/probe/reporte.txt"}""", "")
            }, listOf(HostConfig("probe", "agent")), contextTokens = 4096, toolBudgetTokens = 1500,
                discover = { query -> searches += query; listOf(find) })
            val result = withTimeout(180_000) { worker.processUserIntent("En probe busca el archivo reporte.txt. Si falta la herramienta adecuada, solicítala al catálogo. Informa el resultado.") }
            File(context.filesDir, "tool-discovery-smoke.json").writeText(buildJsonObject {
                put("searches", JsonArray(searches.map(::JsonPrimitive))); put("executions", executions)
                put("steps", JsonArray(steps)); put("answer", result)
            }.toString())
            assertTrue("Model must request tools", searches.isNotEmpty())
            assertEquals(1, executions)
            assertTrue(steps.any { it.getValue("external_tools").jsonPrimitive.int > 3 })
            assertTrue(steps.all { it.getValue("estimated_catalog_tokens").jsonPrimitive.int <= 1500 })
            assertTrue(steps.all { it.getValue("input_tokens").jsonPrimitive.int < 3072 })
            assertTrue(result.contains("reporte.txt"))
        } finally { engine.close() }
    }
}
