package hn.fredi.inferencelocal.api.tools
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ComfyUIImageTool : Tool {
    override val name = "comfyui_image"
    override val loadingMessage = "*(Generando imagen con ComfyUI...)*\n\n"
    override val description = """
        Genera imágenes usando ComfyUI.
        IMPORTANTE: Antes de llamar esta tool, el modelo DEBE incluir en los argumentos
        el prompt positivo y negativo en inglés, elaborados a partir de la descripción del usuario.
        Parámetros: description (descripción original), positive_prompt (en inglés, detallado),
        negative_prompt (en inglés), width, height, steps, cfg_scale, seed.
    """.trimIndent()

    override val parameters = """
        {
          "description": "un dragón de fuego en un castillo medieval",
          "positive_prompt": "dragon breathing fire, medieval castle, epic fantasy art, dramatic lighting, highly detailed, masterpiece, 8k",
          "negative_prompt": "worst quality, low quality, blurry, bad anatomy, watermark, text",
          "width": 512,
          "height": 512,
          "steps": 20,
          "cfg_scale": 7,
          "seed": -1
        }
    """.trimIndent()

    private val comfyHost    = "http://192.168.9.6:7865"
    private val outputDir    = "/sdcard/Pictures/ComfyUI"
    private val defaultModel = "gonzalomoXLFluxPony_v30UnityXLDMD.safetensors"

    private val qualityBoost = listOf(
        "masterpiece", "best quality", "highly detailed", "sharp focus", "8k uhd"
    )
    private val universalNegative = listOf(
        "worst quality", "low quality", "blurry", "bad anatomy", "bad hands",
        "extra fingers", "missing fingers", "watermark", "signature", "text",
        "username", "cropped", "out of frame", "deformed", "ugly", "jpeg artifacts"
    )

    override suspend fun call(args: Map<String, Any>, context: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val description = args["description"]?.toString() ?: ""

        val rawPositive = args["positive_prompt"]?.toString()
            ?: args["positive"]?.toString()
            ?: args["prompt"]?.toString()
            ?: return@withContext "Error: Falta positive_prompt. El modelo debe generarlo antes de llamar esta tool."

        val rawNegative = args["negative_prompt"]?.toString()
            ?: args["negative"]?.toString()
            ?: ""

        // Corrección de casteo defensivo usando Number
        //val width  = (args["width"]     as? Number)?.toInt() ?: (context["imageWidth"]  as? Number)?.toInt() ?: 1024
        //val height = (args["height"]    as? Number)?.toInt() ?: (context["imageHeight"] as? Number)?.toInt() ?: 1024
        //val steps  = (args["steps"]     as? Number)?.toInt() ?: (context["steps"]       as? Number)?.toInt() ?: 20
        //val cfg    = (args["cfg_scale"] as? Number)?.toFloat()
        //    ?: (args["cfg"]       as? Number)?.toFloat()
         //   ?: 7.0f
        val cfg    = 1.1f
        val width  = 1024
        val height = 1024
        val steps = 5

        val seed   = (args["seed"] as? Number)?.toInt()?.let { if (it == -1) (0..Int.MAX_VALUE).random() else it }
            ?: (0..Int.MAX_VALUE).random()

        val positive = enrichPositive(rawPositive)
        val negative = enrichNegative(rawNegative)

        Log.i("ComfyUITool", "Descripción : $description")
        Log.i("ComfyUITool", "Prompt+     : $positive")
        Log.i("ComfyUITool", "Prompt-     : $negative")

        try {
            pingComfyUI() ?: return@withContext "Error: No se puede conectar a ComfyUI en $comfyHost. ¿Está corriendo?"

            // ── Encolar workflow ─────────────────────────────────────────────
            val promptId = queuePrompt(positive, negative, width, height, steps, cfg, seed)
                ?: return@withContext "Error: ComfyUI no aceptó el trabajo."

            // ── Polling hasta que termine ────────────────────────────────────
            val filename = waitForOutput(promptId)
                ?: return@withContext "Error: Timeout esperando resultado (prompt_id=$promptId)."

            // ── Descargar y guardar ──────────────────────────────────────────
            val savedPath = downloadAndSave(filename, description)
                ?: return@withContext "Error: No se pudo descargar la imagen generada."
            val url = URL("$comfyHost/view?filename=${URLEncoder.encode(filename, "UTF-8")}&type=output")
            return@withContext buildString {
                appendLine("Notificar al usuario lo siguiente: ")
                appendLine("✅ Imagen generada")
                appendLine("🔗 [IMAGEGENERATION]($url)")
            }

        } catch (e: java.net.ConnectException) {
            "Error: No se puede conectar a ComfyUI en $comfyHost."
        } catch (e: java.net.SocketTimeoutException) {
            "Error: Timeout. La generación tardó demasiado."
        } catch (e: Exception) {
            "Error inesperado: ${e.message}"
        }
    }

    private fun enrichPositive(raw: String): String {
        val existing = raw.lowercase()
        val missing  = qualityBoost.filter { it.lowercase() !in existing }
        return if (missing.isEmpty()) raw else "$raw, ${missing.joinToString(", ")}"
    }

    private fun enrichNegative(raw: String): String {
        val existing = raw.lowercase()
        val missing  = universalNegative.filter { it.lowercase() !in existing }
        val extras   = if (missing.isEmpty()) "" else missing.joinToString(", ")
        return when {
            raw.isBlank()    -> extras
            extras.isBlank() -> raw
            else             -> "$raw, $extras"
        }
    }

    private fun pingComfyUI(): Boolean? = try {
        val url = URL("$comfyHost/system_stats")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout   = 5_000
        }
        if (connection.responseCode == 200) true else null
    } catch (_: Exception) { null }

    private fun queuePrompt(
        positive: String, negative: String,
        width: Int, height: Int, steps: Int, cfg: Float, seed: Int
    ): String? {
        val workflow = buildWorkflow(positive, negative, width, height, steps, cfg, seed)
        val body     = """{"prompt": $workflow}"""

        val url = URL("$comfyHost/prompt")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 10_000
            readTimeout   = 10_000
            doOutput = true
            outputStream.bufferedWriter().use { it.write(body) }
        }

        if (connection.responseCode != 200) {
            Log.e("ComfyUITool", "HTTP ${connection.responseCode}")
            return null
        }

        val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Regex(""""prompt_id"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)
    }

    private suspend fun waitForOutput(promptId: String): String? = withContext(Dispatchers.IO) {
        val maxWaitMs   = 5 * 60 * 1000L
        val pollEveryMs = 2_000L
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < maxWaitMs) {
            delay(pollEveryMs)
            try {
                val url = URL("$comfyHost/history/$promptId")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout   = 5_000
                }
                if (connection.responseCode != 200) continue

                val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

                if (!json.contains(promptId)) continue

                // Regex mejorada para aceptar png, jpg y webp
                val filename = Regex(""""filename"\s*:\s*"([^"]+\.(?:png|jpg|webp))"""")
                    .find(json)?.groupValues?.get(1)

                if (!filename.isNullOrBlank()) return@withContext filename

            } catch (_: Exception) { /* reintentar */ }
        }
        null
    }

    private fun downloadAndSave(filename: String, description: String): String? = try {
        val url = URL("$comfyHost/view?filename=${URLEncoder.encode(filename, "UTF-8")}&type=output")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout   = 60_000
        }
        if (connection.responseCode != 200) {
            Log.e("ComfyUITool", "HTTP ${connection.responseCode}")
            throw Exception("HTTP ${connection.responseCode}")
        }

        val dir  = File(outputDir).also { it.mkdirs() }
        val slug = description.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .take(30)
            .trimEnd('_')
        val ts   = System.currentTimeMillis()
        val file = File(dir, "${slug}_$ts.png")

        connection.inputStream.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
        file.absolutePath
    } catch (_: Exception) { null }

    private fun buildWorkflow(
        positive: String, negative: String,
        width: Int, height: Int, steps: Int, cfg: Float, seed: Int
    ) = """
        {
          "3": {
            "class_type": "KSampler",
            "inputs": {
              "seed": $seed, "steps": $steps, "cfg": $cfg,
              "sampler_name": "lcm", "scheduler": "exponential", "denoise": 1.0,
              "model":        ["4", 0],
              "positive":     ["6", 0],
              "negative":     ["7", 0],
              "latent_image": ["5", 0]
            }
          },
          "4": {
            "class_type": "CheckpointLoaderSimple",
            "inputs": { "ckpt_name": "$defaultModel" }
          },
          "5": {
            "class_type": "EmptyLatentImage",
            "inputs": { "width": $width, "height": $height, "batch_size": 1 }
          },
          "6": {
            "class_type": "CLIPTextEncode",
            "inputs": { "text": ${jsonString(positive)}, "clip": ["4", 1] }
          },
          "7": {
            "class_type": "CLIPTextEncode",
            "inputs": { "text": ${jsonString(negative)}, "clip": ["4", 1] }
          },
          "8": {
            "class_type": "VAEDecode",
            "inputs": { "samples": ["3", 0], "vae": ["4", 2] }
          },
          "9": {
            "class_type": "SaveImage",
            "inputs": { "filename_prefix": "claude_gen", "images": ["8", 0] }
          }
        }
    """.trimIndent()

    private fun jsonString(s: String) =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

    override fun shouldActivate(content: String): Boolean =
        content.startsWith("image:", ignoreCase = true)  ||
                content.startsWith("genera:", ignoreCase = true) ||
                content.startsWith("draw:", ignoreCase = true)   ||
                content.startsWith("pinta:", ignoreCase = true)
}