package hn.fredi.inferencelocal.api.engine

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import hn.fredi.inferencelocal.api.models.ChatMessage

object PromptFormatter {
    private const val TAG = "PromptFormatter"
    private const val DEFAULT_SYSTEM_PROMPT = "You are a helpful AI assistant."

    fun buildGemmaPrompt(system: String, user: String): String {
        return "<start_of_turn>system\n$system<end_of_turn>\n" +
               "<start_of_turn>user\n$user<end_of_turn>\n" +
               "<start_of_turn>model\n"
    }

    fun buildGemmaChatPrompt(
        messages: List<ChatMessage>,
        imageDecoder: (String) -> Content.ImageBytes?
    ): Contents {
        val promptText = buildString {
            val systemMsgs = messages.filter { it.role.lowercase() == "system" }
                .joinToString("\n") { it.content.trim() }
                .takeIf { it.isNotEmpty() } ?: DEFAULT_SYSTEM_PROMPT

            val chatTurns = messages.filter { it.role.lowercase() != "system" }

            chatTurns.forEachIndexed { index, msg ->
                val role = msg.role.lowercase()
                val gemmaRole = when (role) {
                    "assistant", "model" -> "model"
                    "tool" -> "user"
                    else -> "user"
                }

                append("<start_of_turn>$gemmaRole\n")
                if (index == 0 && gemmaRole == "user") {
                    append("$systemMsgs\n\n")
                }
                if (role == "tool") {
                    append("[TOOL_RESULT]: ")
                }
                append(msg.content.trim())
                append("<end_of_turn>\n")
            }
            append("<start_of_turn>model\n")
        }

        val textContent = Content.Text(promptText)
        val lastUserMessage = messages.lastOrNull { it.role.lowercase() == "user" }
        val images = lastUserMessage?.images
            ?.takeIf { it.isNotEmpty() }
            ?.mapNotNull { imageDecoder(it) }
            ?: emptyList()

        return if (images.isEmpty()) {
            Contents.of(textContent)
        } else {
            try {
                Contents.of(*images.toTypedArray(), textContent)
            } catch (e: Throwable) {
                Log.w(TAG, "Model does not support images, sending text only")
                Contents.of(textContent)
            }
        }
    }
}
