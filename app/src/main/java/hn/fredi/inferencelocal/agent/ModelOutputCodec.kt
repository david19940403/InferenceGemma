package hn.fredi.inferencelocal.agent

import kotlinx.serialization.json.*

object ModelOutputCodec {
    fun encode(calls: List<Pair<String, JsonObject>>, text: String): String {
        val unique = calls.distinct()
        if (unique.isEmpty()) {
            require(text.isNotBlank()) { "El modelo no emitió una respuesta ni una llamada" }
            // Text is only a final answer, never an executable call or a shell command.
            return buildJsonObject { put("final_answer", text.trim()) }.toString()
        }
        require(unique.size == 1) { "El modelo emitió ${unique.size} llamadas distintas; no se ejecutó ninguna. Solicita una acción por paso." }
        val (name, args) = unique.single()
        return buildJsonObject {
            if (name == "finish_transaction") {
                require(args.keys == setOf("answer") && args.getValue("answer").jsonPrimitive.isString && args.getValue("answer").jsonPrimitive.content.isNotBlank())
                put("final_answer", args.getValue("answer"))
            } else put("tool_call", buildJsonObject { put("name", name); put("arguments", args) })
        }.toString()
    }
}
