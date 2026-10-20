package hn.fredi.inferencelocal.agent

import org.junit.Assert.*
import org.junit.Test

class EngineSettingsTest {
    private val valid = EngineSettings(modelPath = "/models/gemma.litertlm")
    @Test fun validatesRuntimeLimits() {
        valid.validate()
        listOf(valid.copy(contextTokens = 2048), valid.copy(contextTokens = 0), valid.copy(topK = 0),
            valid.copy(temperature = Double.NaN), valid.copy(topP = 0.0), valid.copy(backend = "AUTO"),
            valid.copy(minAvailableRamMb = 1), valid.copy(modelPath = "model.gguf")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { invalid.validate() }
        }
    }
    @Test fun preventsCompetingNativeModels() {
        EngineOwnership.release("terminal"); EngineOwnership.release("servidor API")
        try {
            EngineOwnership.claim("terminal")
            assertThrows(IllegalStateException::class.java) { EngineOwnership.claim("servidor API") }
            EngineOwnership.release("servidor API")
            assertEquals("terminal", EngineOwnership.owner.value)
            EngineOwnership.release("terminal")
            EngineOwnership.claim("servidor API")
            assertEquals("servidor API", EngineOwnership.owner.value)
        } finally { EngineOwnership.release("terminal"); EngineOwnership.release("servidor API") }
    }
}
