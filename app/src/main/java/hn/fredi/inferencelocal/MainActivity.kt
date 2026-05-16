package hn.fredi.inferencelocal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import hn.fredi.inferencelocal.ui.components.*
import hn.fredi.inferencelocal.ui.screens.LlmServerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                LlmServerScreen()
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BG,
            surface    = Surface1,
            primary    = AccentCyan,
            onPrimary  = BG,
            onSurface  = TextPrimary
        ),
        content = content
    )
}

enum class UiPhase { IDLE, LOADING_MODEL, STARTING_SERVER, RUNNING, STOPPING, ERROR }

data class UiState(
    val phase      : UiPhase  = UiPhase.IDLE,
    val model      : String   = "",
    val port       : Int      = 8080,
    val message    : String   = "Servidor detenido",
    val errorDetail: String?  = null,
    val sessions   : Int      = 0,
    val tokens     : Long     = 0L,
    val maxTokens  : Int      = 12000,
    val backend    : String   = "N/A",
    val tps        : Double   = 0.0,
    val ttft       : Long     = 0L,
    val initTime   : Long     = 0L,
    val ram        : String   = "—",
    val minRam     : Int      = 1536,
    val defTemp    : Float    = 0.7f,
    val defTopK    : Int      = 40,
    val defTopP    : Float    = 0.9f,
    val defNumCtx  : Int      = 4096,
    val prefBackend: String   = "GPU",
    val maxSessions: Int      = 1
)

fun getAvailableModels(context: android.content.Context): List<String> =
    context.getExternalFilesDir(null)
        ?.listFiles { f -> f.extension in listOf("bin", "litert", "tflite", "litertlm", "task") }
        ?.map { it.name }
        ?.sorted()
        ?: emptyList()
