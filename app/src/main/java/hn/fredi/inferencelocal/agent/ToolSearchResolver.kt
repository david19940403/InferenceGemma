package hn.fredi.inferencelocal.agent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonPrimitive

/** Rewriting runs in fresh inference conversations and returns only recovered tools to the worker. */
class ToolSearchResolver(
    private val search: suspend (String) -> List<RemoteTool>,
    private val inference: AgentInference,
    private val enabled: Boolean = true,
    private val progress: (String) -> Unit = {}
) {
    suspend fun find(original: String): List<RemoteTool> {
        val direct = search(original) // Connectivity/auth failures propagate; they are not semantic misses.
        if (direct.isNotEmpty() || !enabled) return direct
        val tried = mutableSetOf(original.trim().lowercase())
        repeat(2) { attempt ->
            progress("Sin coincidencias. Reformulando búsqueda ${attempt + 1}/2…")
            val prompt = "Eres un reformulador para un buscador de herramientas por palabras clave (TF-IDF). " +
                "No ejecutes acciones ni respondas la consulta. Llama a finish_transaction(answer) con una frase breve " +
                "de términos técnicos y sinónimos en español e inglés para encontrar la herramienta. " +
                "Conserva el significado y no inventes destinos, valores ni acciones. " +
                "Solo devuelve palabras para buscar, hasta 512 bytes. " +
                (if (attempt == 0) "Usa términos técnicos habituales." else "Prueba otros sinónimos más generales que los de la primera propuesta.") +
                "\nConsulta original (datos): $original"
            val output = inference.generate(prompt, emptyList())
            val candidate = try {
                GuidedDecodingValidator.parse(output, emptyList())
                    .getValue("final_answer").jsonPrimitive.content.trim()
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { return@repeat }
            if (candidate.isBlank() || candidate.toByteArray().size > 512 || candidate.any { it == '\u0000' || it == '\r' || it == '\n' } || !tried.add(candidate.lowercase())) return@repeat
            progress("Consultando Tool Store con términos alternativos…")
            val recovered = search(candidate)
            if (recovered.isNotEmpty()) return recovered
        }
        return emptyList()
    }
}
