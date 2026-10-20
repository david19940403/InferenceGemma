package hn.fredi.inferencelocal.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ed25519 is imported into JSch only in memory; Android Keystore wraps it with AES-GCM. */
class ConfigVault(private val context: Context) {
    private val file = File(context.noBackupFilesDir, "infrastructure.enc")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("infrastructure-config", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("infrastructure-config", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(text: String) {
        check(!EngineMonitor.state.value.busy && !EngineMonitor.state.value.modelLoaded) { "Libera el engine en la pestaña Motor antes de importar otra configuración" }
        require(text.toByteArray().size <= 128 * 1024) { "Configuración demasiado grande" }
        val config = Json.decodeFromString<AgentConfig>(text).also { it.validate() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(Json.encodeToString(config).toByteArray())
        val temp = File(file.parentFile, "infrastructure.tmp")
        temp.writeBytes(cipher.iv + encrypted)
        check(temp.renameTo(file)) { "No se pudo guardar la configuración" }
        val store = EngineSettingsStore(context)
        store.save(store.load().copy(modelPath = config.model_path, backend = config.backend))
    }
    fun load(): AgentConfig {
        require(file.exists()) { "Importa una configuración para comenzar" }
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return Json.decodeFromString<AgentConfig>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)).also { it.validate() }
    }
}
