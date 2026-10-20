package hn.fredi.inferencelocal.agent

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolSearchResolverTest {
    private val tool = RemoteTool("ping", "Ping Windows", Transport("ssh","pc","ping.ps1 {ip}"), Json.parseToJsonElement("""{"type":"object","properties":{"ip":{"type":"string"}},"required":["ip"]}""").jsonObject)
    @Test fun rewritesOnlyForRetrievalAndExecutesOriginalIntent() = runBlocking {
        val original = "Averigua si 192.0.2.10 está vivo"
        val alternative = "ping Windows conectividad alternativa"
        val searches = mutableListOf<String>()
        val executionPrompts = mutableListOf<String>()
        var rewrites = 0
        var executions = 0
        val inference = AgentInference { prompt, offered ->
            if(prompt.startsWith("Eres un reformulador")) {
                rewrites++; assertTrue(offered.isEmpty())
                buildJsonObject { put("final_answer",alternative) }.toString()
            } else {
                executionPrompts += prompt
                if(executionPrompts.size == 1) """{"tool_call":{"name":"ping","arguments":{"ip":"192.0.2.10"}}}"""
                else """{"final_answer":"El destino respondió"}"""
            }
        }
        val resolver = ToolSearchResolver({ q -> searches += q; if(q == alternative) listOf(tool) else emptyList() }, inference)
        val worker = AgenticLoopWorker(resolver::find,inference,ToolExecutor { _,command ->
            executions++;assertTrue(command.contains("192.0.2.10"));CommandOutput(0,"""{"status":"success"}""","")
        },listOf(HostConfig("pc","agent")))
        assertEquals("El destino respondió",worker.processUserIntent(original))
        assertEquals(listOf(original,alternative),searches)
        assertEquals(1,rewrites);assertEquals(1,executions)
        executionPrompts.forEach { assertTrue(it.contains(original));assertFalse(it.contains(alternative)) }
    }
    @Test fun directMatchesAndDisabledRewritesDoNotLoadModel() = runBlocking {
        val noInference = AgentInference { _,_ -> fail("Unexpected model load");"" }
        assertEquals(listOf(tool),ToolSearchResolver({listOf(tool)},noInference).find("ping"))
        assertTrue(ToolSearchResolver({emptyList()},noInference,false).find("otro").isEmpty())
    }
    @Test fun boundsRetriesAndRejectsOversizedOrDuplicateQueries() = runBlocking {
        var searches = 0;var generations = 0
        val resolver = ToolSearchResolver({searches++;emptyList()},AgentInference { _,tools ->
            assertTrue(tools.isEmpty());generations++
            if(generations == 1) buildJsonObject { put("final_answer","á".repeat(257)) }.toString()
            else """{"final_answer":"original"}"""
        })
        assertTrue(resolver.find("original").isEmpty());assertEquals(1,searches);assertEquals(2,generations)
        searches=0;generations=0
        assertTrue(ToolSearchResolver({searches++;emptyList()},AgentInference { _,_-> generations++;"""{"final_answer":"alternative $generations"}""" }).find("original").isEmpty())
        assertEquals(3,searches);assertEquals(2,generations)
    }
    @Test fun connectivityErrorsAndCancellationAreNotReformulated() = runBlocking {
        var generations=0
        val inference=AgentInference { _,_->generations++;"" }
        try {ToolSearchResolver({throw IllegalStateException("HTTP 401")},inference).find("ping");fail("Expected auth failure")}
        catch(e: IllegalStateException){assertEquals("HTTP 401",e.message)}
        assertEquals(0,generations)
        try {ToolSearchResolver({emptyList()},AgentInference { _,_->throw CancellationException("cancel") }).find("ping");fail("Expected cancellation")}
        catch(_: CancellationException) { }
        try {ToolSearchResolver({emptyList()},AgentInference { _,_->error("Modelo no disponible") }).find("ping");fail("Expected model failure")}
        catch(e: IllegalStateException) {assertEquals("Modelo no disponible",e.message)}
    }
    @Test fun validatesNetworkAddressesWithoutRestrictingLanOrVpn() {
        listOf("http://10.12.14.1:8000","http://192.168.9.1:18000","https://pc.vpn","http://[::1]:8000").forEach {assertEquals(it,ToolStoreEndpoint.normalize(" $it/ "))}
        listOf("http://10.12.14.1/24","file:///tmp/store","http://user:password@pc:8000","http://pc:0","http://pc:65536","http://pc:8000?token=x","http://pc:8000#x").forEach {
            assertThrows(IllegalArgumentException::class.java) {ToolStoreEndpoint.normalize(it)}
        }
        assertEquals("http://10.12.14.1:18000",ToolStoreEndpoint.preset("http://127.0.0.1:18000","10.12.14.1"))
        assertEquals("https://192.168.9.1:443",ToolStoreEndpoint.preset("https://pc.vpn","192.168.9.1"))
    }
}
