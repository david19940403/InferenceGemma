package hn.fredi.inferencelocal.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CatalogHelpTest {
    @Test fun distinguishesDiscoveryFromActualActions() {
        assertEquals("summary", CatalogHelp.arguments("Que herramientas tienes")!!["action"]!!.jsonPrimitive.content)
        assertEquals("3", CatalogHelp.arguments("Lista tools pagina 3")!!["page"]!!.jsonPrimitive.content)
        assertEquals("red", CatalogHelp.arguments("Ayuda de red")!!["query"]!!.jsonPrimitive.content)
        assertEquals("detail", CatalogHelp.arguments("Ayuda de files_read_text")!!["action"]!!.jsonPrimitive.content)
        assertNull(CatalogHelp.arguments("Lista archivos en C:\\Users\\David\\Documents"))
        assertNull(CatalogHelp.arguments("Reinicia Spooler usando las herramientas"))
    }

    @Test fun returnsLiveInventoryWithoutModelRewriting() = runBlocking {
        val parameters = Json.parseToJsonElement("""{"type":"object","properties":{"action":{"type":"string"},"query":{"type":"string"},"page":{"type":"string"},"limit":{"type":"string"}},"required":[]}""").jsonObject
        val tool = RemoteTool("tools_help", "Ayuda", Transport("ssh","pc","C:\\Tools\\help.ps1 {action} {query} {page} {limit}"), parameters)
        var executions = 0
        val worker = AgenticLoopWorker({ listOf(tool) }, AgentInference { _, _ -> error("Discovery must use the actual inventory") }, ToolExecutor { _, _ ->
            executions++
            CommandOutput(0,"""{"status":"success","total":21,"groups":{"archivos":8,"red":4,"windows":8,"ayuda":1}}""","")
        }, listOf(HostConfig("pc","agent",shell="powershell")))
        val result = worker.processUserIntent("Que herramientas tienes")
        assertTrue(result.contains("21 herramientas"))
        assertTrue(result.contains("archivos: 8"))
        assertEquals(1, executions)
    }
}
