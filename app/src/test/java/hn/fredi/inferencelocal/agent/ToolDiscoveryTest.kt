package hn.fredi.inferencelocal.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolDiscoveryTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{},"required":[]}""").jsonObject
    private fun tool(name: String) = RemoteTool(name, "Consulta de estado", Transport("ssh", "pc", "$name.ps1"), schema)
    private fun call(name: String, args: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("tool_call", buildJsonObject { put("name", name); put("arguments", args) })
    }.toString()
    private fun discovery(query: String) = call(ToolCatalogBudget.SEARCH, buildJsonObject { put("query", query) })

    @Test fun selectsMoreThanThreeWithinBudgetAndNeverCutsSchemas() {
        val candidates = (1..16).map { tool("status_$it") }
        val selection = ToolCatalogBudget.select(candidates, 1500, true)
        assertTrue(selection.tools.count { it.transport.type == "ssh" } > 3)
        assertTrue(selection.estimatedTokens <= 1500)
        assertTrue(selection.tools.any { it.name == ToolCatalogBudget.SEARCH })
        selection.tools.filter { it.transport.type == "ssh" }.forEach { assertEquals(schema, it.parameters) }
        val huge = tool("huge").copy(parameters = Json.parseToJsonElement("""{"type":"object","properties":{"key":{"type":"string","enum":["${"x".repeat(10000)}"]}}}""").jsonObject)
        val skipped = ToolCatalogBudget.select(listOf(huge, tool("small")), 1500, true)
        assertFalse(skipped.tools.any { it.name == "huge" })
        assertTrue(skipped.tools.any { it.name == "small" })
        assertEquals(1, skipped.omitted)
    }

    @Test fun discoversUsefulToolEvenWithNonemptyInitialResults() = runBlocking {
        val original = "Consulta el disco en Windows"
        val searches = mutableListOf<String>()
        var evaluations = 0
        var executions = 0
        val worker = AgenticLoopWorker({ searches += it; listOf(tool("unrelated")) }, AgentInference { prompt, offered ->
            assertTrue(prompt.contains(original))
            assertFalse(prompt.contains("\"properties\"")) // schemas are sent once as native definitions
            when (evaluations++) {
                0 -> { assertTrue(offered.any { it.name == ToolCatalogBudget.SEARCH }); discovery("disco almacenamiento") }
                1 -> { assertTrue(offered.any { it.name == "disk" }); assertFalse(offered.any { it.name == "unrelated" }); call("disk") }
                else -> { assertTrue(prompt.contains("free_mb")); """{"final_answer":"Disco consultado"}""" }
            }
        }, ToolExecutor { _, _ -> executions++; CommandOutput(0, """{"free_mb":1000}""", "") }, listOf(HostConfig("pc", "agent")),
            discover = { searches += it; listOf(tool("disk")) })
        assertEquals("Disco consultado", worker.processUserIntent(original))
        assertEquals(listOf(original, "disco almacenamiento"), searches)
        assertEquals(1, executions)
    }

    @Test fun searchMissKeepsExistingToolsAndDoesNotExecuteAnything() = runBlocking {
        var rounds = 0
        val worker = AgenticLoopWorker({ listOf(tool("status")) }, AgentInference { prompt, offered ->
            if (rounds++ == 0) discovery("nonexistent capability") else {
                assertTrue(offered.any { it.name == "status" }); assertTrue(prompt.contains("Sin coincidencias"))
                """{"final_answer":"No encontré esa capacidad"}"""
            }
        }, ToolExecutor { _, _ -> error("Search must never execute SSH") }, listOf(HostConfig("pc", "agent")), discover = { emptyList() })
        assertEquals("No encontré esa capacidad", worker.processUserIntent("Busca capacidad"))
    }

    @Test fun repeatedSearchesAreBoundedAndCached() = runBlocking {
        var rounds = 0; var requests = 0
        val worker = AgenticLoopWorker({ listOf(tool("status")) }, AgentInference { _, offered ->
            if (rounds++ < 3) discovery("specific status") else {
                assertFalse(offered.any { it.name == ToolCatalogBudget.SEARCH })
                """{"final_answer":"Terminado"}"""
            }
        }, ToolExecutor { _, _ -> error("No execution") }, listOf(HostConfig("pc", "agent")), discover = { requests++; emptyList() })
        assertEquals("Terminado", worker.processUserIntent("Estado"))
        assertEquals(1, requests)
        assertEquals(4, rounds)
    }

    @Test fun rejectsUnavailableToolBeforeAnyExecution() = runBlocking {
        val worker = AgenticLoopWorker({ listOf(tool("status")) }, AgentInference { _, _ -> call("not_retrieved") },
            ToolExecutor { _, _ -> error("Must not execute") }, listOf(HostConfig("pc", "agent")))
        try { worker.processUserIntent("Estado"); fail() } catch (_: IllegalStateException) { }
    }

    @Test fun internalSearchIsValidatedAndReservedNamesCannotBeSuppliedByCatalogs() {
        GuidedDecodingValidator.parse(discovery("disco"), listOf(ToolCatalogBudget.searchTool))
        assertThrows(IllegalArgumentException::class.java) { GuidedDecodingValidator.parse(discovery("x".repeat(513)), listOf(ToolCatalogBudget.searchTool)) }
        assertThrows(IllegalArgumentException::class.java) { GuidedDecodingValidator.parse(call(ToolCatalogBudget.SEARCH), listOf(ToolCatalogBudget.searchTool)) }
        assertThrows(IllegalArgumentException::class.java) { ToolCatalogBudget.select(listOf(tool(ToolCatalogBudget.SEARCH)), 1500, true) }
    }
}
