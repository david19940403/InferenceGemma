package hn.fredi.inferencelocal.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import hn.fredi.inferencelocal.UiPhase
import hn.fredi.inferencelocal.ui.components.*
import hn.fredi.inferencelocal.ui.viewmodels.LlmServerViewModel

@Composable
fun LlmServerScreen(viewModel: LlmServerViewModel = viewModel()) {
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600 || configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val scroll = rememberScrollState()
    val ui = viewModel.uiState
    val isRunning = ui.phase == UiPhase.RUNNING
    val isBusy = ui.phase == UiPhase.LOADING_MODEL ||
            ui.phase == UiPhase.STARTING_SERVER ||
            ui.phase == UiPhase.STOPPING

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .drawBehind {
                val step = 36.dp.toPx()
                val dotR = 1.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    var y = 0f
                    while (y < size.height) {
                        drawCircle(
                            color = Color(0xFF1A2A3A),
                            radius = dotR,
                            center = Offset(x, y)
                        )
                        y += step
                    }
                    x += step
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            AccentCyan.copy(alpha = 0.06f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.5f, 0f),
                        radius = size.width * 0.8f
                    ),
                    radius = size.width * 0.8f,
                    center = Offset(size.width * 0.5f, 0f)
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scroll)
                .padding(horizontal = if (isTablet) 40.dp else 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Header()

            if (isTablet) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        StatusOrb(phase = ui.phase)
                        StateLabel(phase = ui.phase, message = ui.message)
                        ConnectionCard(ip = viewModel.localIp, port = ui.port, isRunning = isRunning)
                        AnimatedVisibility(visible = isRunning) {
                            StatsCard(ui)
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ModelSelectorCard(
                            selectedModel = viewModel.modelSelected,
                            isDisabled = isRunning || isBusy,
                            onSelected = { viewModel.modelSelected = it }
                        )

                        AnimatedVisibility(visible = !isRunning && !isBusy) {
                            ModelConfigCard(
                                temp = viewModel.configTemp,
                                onTempChange = { viewModel.configTemp = it },
                                topK = viewModel.configTopK,
                                onTopKChange = { viewModel.configTopK = it },
                                topP = viewModel.configTopP,
                                onTopPChange = { viewModel.configTopP = it },
                                numCtx = viewModel.configNumCtx,
                                onNumCtxChange = { viewModel.configNumCtx = it },
                                prefBackend = viewModel.prefBackend,
                                onPrefBackendChange = { viewModel.prefBackend = it },
                                maxSessions = viewModel.maxSessions,
                                onMaxSessionsChange = { viewModel.maxSessions = it },
                                minRamMb = viewModel.minRamMb,
                                onMinRamMbChange = { viewModel.minRamMb = it }
                            )
                        }

                        AnimatedVisibility(visible = ui.phase == UiPhase.ERROR) {
                            ErrorCard(detail = ui.errorDetail ?: ui.message)
                        }

                        MainActionButton(
                            phase = ui.phase,
                            isBusy = isBusy,
                            onClick = {
                                if (isRunning || ui.phase == UiPhase.ERROR) {
                                    viewModel.stopServer()
                                } else if (!isBusy) {
                                    viewModel.startServer()
                                }
                            }
                        )

                        AnimatedVisibility(visible = isRunning) {
                            Text(
                                text = "Compatible con clientes Ollama · OpenAI API",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                StatusOrb(phase = ui.phase)
                StateLabel(phase = ui.phase, message = ui.message)
                ConnectionCard(ip = viewModel.localIp, port = ui.port, isRunning = isRunning)
                AnimatedVisibility(visible = isRunning) {
                    StatsCard(ui)
                }
                ModelSelectorCard(
                    selectedModel = viewModel.modelSelected,
                    isDisabled = isRunning || isBusy,
                    onSelected = { viewModel.modelSelected = it }
                )
                AnimatedVisibility(visible = !isRunning && !isBusy) {
                    ModelConfigCard(
                        temp = viewModel.configTemp,
                        onTempChange = { viewModel.configTemp = it },
                        topK = viewModel.configTopK,
                        onTopKChange = { viewModel.configTopK = it },
                        topP = viewModel.configTopP,
                        onTopPChange = { viewModel.configTopP = it },
                        numCtx = viewModel.configNumCtx,
                        onNumCtxChange = { viewModel.configNumCtx = it },
                        prefBackend = viewModel.prefBackend,
                        onPrefBackendChange = { viewModel.prefBackend = it },
                        maxSessions = viewModel.maxSessions,
                        onMaxSessionsChange = { viewModel.maxSessions = it },
                        minRamMb = viewModel.minRamMb,
                        onMinRamMbChange = { viewModel.minRamMb = it }
                    )
                }
                AnimatedVisibility(visible = ui.phase == UiPhase.ERROR) {
                    ErrorCard(detail = ui.errorDetail ?: ui.message)
                }
                MainActionButton(
                    phase = ui.phase,
                    isBusy = isBusy,
                    onClick = {
                        if (isRunning || ui.phase == UiPhase.ERROR) {
                            viewModel.stopServer()
                        } else if (!isBusy) {
                            viewModel.startServer()
                        }
                    }
                )
                AnimatedVisibility(visible = isRunning) {
                    Text(
                        text = "Compatible con clientes Ollama · OpenAI API",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
