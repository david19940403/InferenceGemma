package hn.fredi.inferencelocal.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentFlowTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"url":{"type":"string"},"format":{"type":"string","enum":["video_best","audio_mp3"],"default":"video_best"}},"required":["url"]}""").jsonObject
    private val tool = RemoteTool("download", "Descarga un video", Transport("ssh", "fedora.vpn", "/opt/agent-tools/download_video.sh \"{url}\" \"{format}\""), schema)
    private val args = buildJsonObject { put("url", "https://example.com/a?x=$(touch /tmp/pwn)&q='test'") }
    private fun call() = buildJsonObject { put("tool_call", buildJsonObject { put("name", "download"); put("arguments", args) }) }.toString()
    @Test fun escapesEveryShellArgument() {
        val cmd = CommandBuilder.build(tool, args, "posix")
        assertTrue(cmd.startsWith("'/opt/agent-tools/download_video.sh' 'https://"))
        assertTrue(cmd.endsWith("'video_best'"))
        assertTrue(cmd.contains("'\"'\"'test'\"'\"'"))
        val windows = CommandBuilder.build(tool.copy(transport = tool.transport.copy(command_template = "C:\\AgentTools\\download.ps1 {url} {format}")), args, "powershell")
        val decoded = java.util.Base64.getDecoder().decode(windows.substringAfter("-EncodedCommand ")).toString(Charsets.UTF_16LE)
        assertTrue(decoded.contains("q=''test''"))
    }
    @Test fun rejectsUnknownArgumentsAndInvalidEnums() {
        val unknown = JsonObject(args + ("command" to JsonPrimitive("rm -rf /")))
        assertThrows(IllegalArgumentException::class.java) { GuidedDecodingValidator.arguments(tool, unknown) }
        assertThrows(IllegalArgumentException::class.java) { GuidedDecodingValidator.arguments(tool, JsonObject(args + ("format" to JsonPrimitive("bad")))) }
        assertThrows(IllegalArgumentException::class.java) { GuidedDecodingValidator.arguments(tool, buildJsonObject {}) }
        assertThrows(IllegalArgumentException::class.java) { CommandBuilder.build(tool.copy(transport = tool.transport.copy(command_template = "script.sh;evil {url}")), args, "posix") }
    }
    @Test fun feedsExecutionResultIntoNextInference() = runBlocking {
        val prompts = mutableListOf<String>(); var executions = 0
        val worker = AgenticLoopWorker({ listOf(tool) }, AgentInference { prompt, _ ->
            prompts += prompt
            if (prompts.size == 1) call() else """{"final_answer":"Archivo descargado"}"""
        }, ToolExecutor { _, _ -> executions++; CommandOutput(0, """{"file":"video.mkv"}""", "") }, listOf(HostConfig("fedora.vpn", "agent")))
        assertEquals("Archivo descargado", worker.processUserIntent("Descarga video"))
        assertEquals(1, executions)
        assertTrue(prompts[1].contains("video.mkv"))
        assertTrue(prompts[1].contains("exit_code"))
    }
    @Test fun preventsDuplicateSideEffects() = runBlocking {
        var count = 0
        val worker = AgenticLoopWorker({ listOf(tool) }, AgentInference { _, _ -> call() },
            ToolExecutor { _, _ -> count++; CommandOutput(0, "{}", "") }, listOf(HostConfig("fedora.vpn", "agent")))
        try { worker.processUserIntent("Descarga video"); fail("Expected duplicate rejection") } catch (_: IllegalStateException) { }
        assertEquals(1, count)
    }
    @Test fun propagatesRemoteFailuresToEvaluation() = runBlocking {
        var prompt = ""
        var step = 0
        val worker = AgenticLoopWorker({ listOf(tool) }, AgentInference { p, _ -> prompt = p; if (step++ == 0) call() else """{"final_answer":"La descarga falló"}""" },
            ToolExecutor { _, _ -> CommandOutput(1, "", """{"error":"sin red"}""") }, listOf(HostConfig("fedora.vpn", "agent")))
        assertEquals("La descarga falló", worker.processUserIntent("Descarga video"))
        assertTrue(prompt.contains("sin red"))
        assertTrue(prompt.contains("\"exit_code\":1"))
    }
    @Test fun answersFromExistingResultAfterDuplicateProposal() = runBlocking {
        var count = 0
        var evaluations = 0
        val worker = AgenticLoopWorker({ listOf(tool) }, AgentInference { prompt, offered ->
            evaluations++
            if (evaluations < 3) call() else {
                assertTrue(offered.isEmpty())
                assertTrue(prompt.contains("video.mkv"))
                """{"final_answer":"Archivo descargado"}"""
            }
        }, ToolExecutor { _, _ -> count++; CommandOutput(0, """{"file":"video.mkv"}""", "") }, listOf(HostConfig("fedora.vpn", "agent")))
        assertEquals("Archivo descargado", worker.processUserIntent("Descarga video"))
        assertEquals(1, count)
        assertEquals(3, evaluations)
    }
}
