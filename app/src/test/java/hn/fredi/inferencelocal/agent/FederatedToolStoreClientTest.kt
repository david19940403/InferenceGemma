package hn.fredi.inferencelocal.agent

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class FederatedToolStoreClientTest {
    private fun source(id: String) = ToolStoreConnection(id,id,"http://$id:8000",id)
    private fun tool(host: String = "pc") = RemoteTool("ping", "comprobar red", Transport("ssh",host,"ping {ip}"),JsonObject(emptyMap()))
    @Test fun partialFailureAndPerSourceCredentials() = runBlocking {
        val calls=mutableMapOf<String,Int>()
        val client=FederatedToolStoreClient(listOf(source("local"),source("vpn"))) { s,_ ->
            assertEquals(s.id,s.token);calls[s.id]=(calls[s.id]?:0)+1
            if(s.id=="vpn") throw java.net.ConnectException()
            listOf(tool())
        }
        assertEquals(1,client.search("ping").size)
        assertEquals(listOf("ok","offline"),client.statuses.map {it.status})
        client.search("red");assertEquals(1,calls["vpn"]);assertEquals(2,calls["local"])
    }
    @Test fun requestsActuallyRunConcurrently() = runBlocking {
        var started=0;val both=CompletableDeferred<Unit>()
        val client=FederatedToolStoreClient(listOf(source("a"),source("b"))) { _,_ ->
            if(++started==2)both.complete(Unit)
            withTimeout(1000){both.await()};emptyList()
        }
        assertTrue(client.search("none").isEmpty());assertEquals(2,started)
    }
    @Test fun allOfflineFailsInsteadOfPretendingNoMatchingTools() = runBlocking {
        val client=FederatedToolStoreClient(listOf(source("a"))) { _,_ -> throw java.net.ConnectException() }
        try { client.search("ping");fail() } catch(e: IllegalStateException) { assertTrue(e.message!!.contains("Ningún")) }
    }
    @Test fun duplicateCopiesCollapseAndConflictsGetStableAliases() {
        val a=tool();val b=tool("another")
        val merged=FederatedToolStoreClient.merge("ping",listOf("a" to a,"central" to a,"b" to b))
        assertEquals(2,merged.size);assertEquals(2,merged.map{it.name}.distinct().size)
        assertTrue(merged.all{it.name.matches(Regex("[a-z][a-z0-9_]{0,63}"))})
        assertEquals(setOf(a.transport,b.transport),merged.map{it.transport}.toSet())
        assertEquals(merged,FederatedToolStoreClient.merge("ping",listOf("a" to a,"central" to a,"b" to b)))
    }
    @Test fun globalBudgetAndInvalidConfiguration() {
        val tools=(1..9).map { "s$it" to tool().copy(name="tool_$it") }
        assertEquals(9,FederatedToolStoreClient.merge("red",tools).size)
        assertEquals(32,FederatedToolStoreClient.merge("red",(1..50).map { "s$it" to tool().copy(name="tool_$it") }).size)
        try { ToolStoreSettings("http://pc:80",stores=listOf(ToolStoreNode("other","Other","http://PC"))).normalized();fail() } catch(_:IllegalArgumentException){}
        try { ToolStoreSettings("http://pc",primaryEnabled=false).normalized();fail() } catch(_:IllegalArgumentException){}
    }
    @Test fun parentCancellationIsNeverReportedAsOffline() = runBlocking {
        val client=FederatedToolStoreClient(listOf(source("a"))) { _,_ -> awaitCancellation() }
        try { withTimeout(50){client.search("ping")};fail() } catch(_:TimeoutCancellationException){}
        assertTrue(client.statuses.isEmpty())
    }
}
