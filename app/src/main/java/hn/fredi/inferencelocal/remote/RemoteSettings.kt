package hn.fredi.inferencelocal.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun newRemoteToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
@Serializable data class RemoteSettings(val port: Int = 11435, val token: String = newRemoteToken()) {
    fun validate() { require(port in 1024..65535) { "Puerto: 1024 a 65535" }; require(token.length >= 32) { "La clave de acceso es demasiado corta" } }
}
class RemoteSettingsStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir,"remote-access.enc"))
    companion object { private val lock = Any() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("remote-access", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("remote-access",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): RemoteSettings = synchronized(lock) {
        if(!file.baseFile.exists()) return@synchronized RemoteSettings().also { save(it) }
        val bytes = file.readFully(); require(bytes.size >= 28) { "Configuración remota dañada" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12))) }
        Json.decodeFromString<RemoteSettings>(cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8)).also { it.validate() }
    }
    fun save(settings: RemoteSettings) = synchronized(lock) {
        check(!RemoteAccessService.state.value.running) { "Detén el acceso remoto para cambiar puerto o clave" }
        settings.validate()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val bytes=cipher.iv+cipher.doFinal(Json.encodeToString(settings).toByteArray())
        val out=file.startWrite(); try { out.write(bytes);file.finishWrite(out) } catch(e: Exception) { file.failWrite(out);throw e }
    }
}
