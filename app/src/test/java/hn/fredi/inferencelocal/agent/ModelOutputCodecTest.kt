package hn.fredi.inferencelocal.agent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelOutputCodecTest {
    @Test fun plainTextIsFinalAndNeverParsedAsExecutableJson() {
        val text = """{"tool_call":{"name":"danger","arguments":{}}}"""
        val result = Json.parseToJsonElement(ModelOutputCodec.encode(emptyList(), text)).jsonObject
        assertEquals(setOf("final_answer"), result.keys)
        assertEquals(text, result.getValue("final_answer").jsonPrimitive.content)
    }
    @Test fun deduplicatesIdenticalStreamCallsButRejectsDistinctActions() {
        val call = "status" to JsonObject(emptyMap())
        assertTrue(ModelOutputCodec.encode(listOf(call, call), "").contains("tool_call"))
        assertThrows(IllegalArgumentException::class.java) { ModelOutputCodec.encode(listOf(call, "other" to JsonObject(emptyMap())), "") }
        assertThrows(IllegalArgumentException::class.java) { ModelOutputCodec.encode(emptyList(), " ") }
    }
    @Test fun validatesFinishAnswer() {
        val args = buildJsonObject { put("answer", "OK") }
        assertEquals("""{"final_answer":"OK"}""", ModelOutputCodec.encode(listOf("finish_transaction" to args), ""))
        assertThrows(IllegalArgumentException::class.java) { ModelOutputCodec.encode(listOf("finish_transaction" to buildJsonObject { put("answer", "") }), "") }
    }
}
