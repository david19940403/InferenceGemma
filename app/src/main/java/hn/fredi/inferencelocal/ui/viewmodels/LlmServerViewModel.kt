package hn.fredi.inferencelocal.ui.viewmodels

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import hn.fredi.inferencelocal.service.LlmServerService
import hn.fredi.inferencelocal.UiPhase
import hn.fredi.inferencelocal.UiState
import hn.fredi.inferencelocal.getAvailableModels
import java.net.Inet4Address
import java.net.NetworkInterface

class LlmServerViewModel(application: Application) : AndroidViewModel(application) {
    var uiState by mutableStateOf(UiState())
        private set

    var modelSelected by mutableStateOf(
        getAvailableModels(application).firstOrNull() ?: LlmServerService.DEFAULT_MODEL_FILENAME
    )

    var configTemp by mutableFloatStateOf(0.7f)
    var configTopK by mutableIntStateOf(40)
    var configTopP by mutableFloatStateOf(0.9f)
    var configNumCtx by mutableIntStateOf(4096)
    var prefBackend by mutableStateOf("GPU")
    var maxSessions by mutableIntStateOf(3)
    var minRamMb by mutableIntStateOf(1536)

    val localIp: String = getLocalIpAddress() ?: "—"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val code = intent.getStringExtra(LlmServerService.EXTRA_STATE_CODE) ?: return
            val msg = intent.getStringExtra(LlmServerService.EXTRA_STATE_MSG) ?: ""
            val err = intent.getStringExtra(LlmServerService.EXTRA_STATE_ERROR)
            val port = intent.getIntExtra(
                LlmServerService.EXTRA_STATE_PORT,
                LlmServerService.DEFAULT_PORT
            )
            val model = intent.getStringExtra(LlmServerService.EXTRA_STATE_MODEL) ?: ""
            val sessions = intent.getIntExtra(LlmServerService.EXTRA_STATE_SESSIONS, 0)
            val tokens = intent.getLongExtra(LlmServerService.EXTRA_STATE_TOKENS, 0L)
            val maxTokens = intent.getIntExtra(LlmServerService.EXTRA_STATE_MAX_TOKENS, 2048)
            val backend = intent.getStringExtra(LlmServerService.EXTRA_STATE_BACKEND) ?: "N/A"
            val tps = intent.getDoubleExtra(LlmServerService.EXTRA_STATE_TPS, 0.0).let {
                if (it.isFinite()) it else 0.0
            }
            val ttft = intent.getLongExtra(LlmServerService.EXTRA_STATE_TTFT, 0L)
            val initTime = intent.getLongExtra(LlmServerService.EXTRA_STATE_INIT_TIME, 0L)
            val ram = intent.getStringExtra(LlmServerService.EXTRA_STATE_RAM) ?: "—"

            // Configuración
            val dTemp = intent.getFloatExtra(LlmServerService.EXTRA_DEFAULT_TEMP, 0.7f)
            val dTopK = intent.getIntExtra(LlmServerService.EXTRA_DEFAULT_TOP_K, 40)
            val dTopP = intent.getFloatExtra(LlmServerService.EXTRA_DEFAULT_TOP_P, 0.9f)
            val dNumCtx = intent.getIntExtra(LlmServerService.EXTRA_DEFAULT_NUM_CTX, 2048)
            val pb = intent.getStringExtra(LlmServerService.EXTRA_PREFERRED_BACKEND) ?: "GPU"
            val ms = intent.getIntExtra(LlmServerService.EXTRA_MAX_SESSIONS, 3)
            val minR = intent.getIntExtra(LlmServerService.EXTRA_MIN_RAM_MB, 1536)

            uiState = when (code) {
                "LOADING_MODEL" -> UiState(
                    UiPhase.LOADING_MODEL,
                    model,
                    port,
                    msg,
                    null,
                    sessions,
                    tokens,
                    maxTokens,
                    backend,
                    tps,
                    ttft,
                    initTime,
                    ram,
                    minR,
                    dTemp,
                    dTopK,
                    dTopP,
                    dNumCtx,
                    pb,
                    ms
                )

                "STARTING_SERVER" -> UiState(
                    UiPhase.STARTING_SERVER,
                    model,
                    port,
                    msg,
                    null,
                    sessions,
                    tokens,
                    maxTokens,
                    backend,
                    tps,
                    ttft,
                    initTime,
                    ram,
                    minR,
                    dTemp,
                    dTopK,
                    dTopP,
                    dNumCtx,
                    pb,
                    ms
                )

                "RUNNING" -> UiState(
                    UiPhase.RUNNING,
                    model,
                    port,
                    msg,
                    null,
                    sessions,
                    tokens,
                    maxTokens,
                    backend,
                    tps,
                    ttft,
                    initTime,
                    ram,
                    minR,
                    dTemp,
                    dTopK,
                    dTopP,
                    dNumCtx,
                    pb,
                    ms
                )

                "STOPPING" -> UiState(
                    UiPhase.STOPPING,
                    model,
                    port,
                    msg,
                    null,
                    sessions,
                    tokens,
                    maxTokens,
                    backend,
                    tps,
                    ttft,
                    initTime,
                    ram,
                    minR,
                    dTemp,
                    dTopK,
                    dTopP,
                    dNumCtx,
                    pb,
                    ms
                )

                "STOPPED" -> UiState(UiPhase.IDLE, model, port, "Servidor detenido", null, 0, 0, 4096)
                "ERROR" -> UiState(
                    UiPhase.ERROR,
                    model,
                    port,
                    msg,
                    err,
                    sessions,
                    tokens,
                    maxTokens,
                    backend,
                    tps,
                    ttft,
                    initTime,
                    ram,
                    minR,
                    dTemp,
                    dTopK,
                    dTopP,
                    dNumCtx,
                    pb,
                    ms
                )

                else -> uiState
            }
        }
    }

    init {
        val filter = IntentFilter(LlmServerService.BROADCAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            application.registerReceiver(receiver, filter)
        }
        queryServiceState()
    }

    private fun queryServiceState() {
        val queryIntent = Intent(getApplication(), LlmServerService::class.java).apply {
            action = LlmServerService.ACTION_QUERY_STATE
        }
        getApplication<Application>().startService(queryIntent)
    }

    fun startServer() {
        val intent = LlmServerService.startIntent(
            context = getApplication(),
            modelName = modelSelected,
            numCtx = configNumCtx,
            temp = configTemp,
            topK = configTopK,
            topP = configTopP,
            preferredBackend = prefBackend,
            maxSessions = maxSessions,
            minRamMb = minRamMb
        )
        getApplication<Application>().startForegroundService(intent)
        uiState = uiState.copy(phase = UiPhase.LOADING_MODEL, message = "Cargando modelo…")
    }

    fun stopServer() {
        getApplication<Application>().startService(LlmServerService.stopIntent(getApplication()))
        uiState = uiState.copy(phase = UiPhase.STOPPING, message = "Deteniendo…")
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(receiver)
    }

    private fun getLocalIpAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.filter { !it.isLoopback && it.isUp }
            ?.sortedByDescending { it.name.startsWith("wlan") }
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
