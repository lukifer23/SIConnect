package dev.siconnect.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.siconnect.core.DeviceControls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class GatewayConfig(
    /** e.g. https://siconnect-gateway.example.workers.dev */
    val baseUrl: String = "",
    val enabled: Boolean = false,
)

@Serializable
data class WeatherDefaults(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val label: String = "",
    /** Fallback wake time (minutes after local midnight) when no alarm is visible. */
    val fallbackWakeMinutes: Int = 7 * 60,
)

/**
 * Non-secret settings in SharedPreferences; secrets encrypted with a non-exportable Android Keystore
 * AES-GCM key. The app is excluded from backups (see data_extraction_rules.xml).
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("siconnect", Context.MODE_PRIVATE)
    private val secretPrefs = context.getSharedPreferences("siconnect_secrets", Context.MODE_PRIVATE)

    /** Random, not derived from hardware identifiers. */
    val deviceId: String = prefs.getString("device_id", null) ?: ("and-" + UUID.randomUUID().toString().take(13)).also {
        prefs.edit().putString("device_id", it).apply()
    }

    var privilegeBackend: String
        get() = prefs.getString("privilege_backend", "direct")!!
        set(v) { require(v in setOf("direct", "shizuku")); prefs.edit().putString("privilege_backend", v).apply() }

    var deviceName: String
        get() = prefs.getString("device_name", null) ?: android.os.Build.MODEL
        set(v) = prefs.edit().putString("device_name", v).apply()

    private val _controls = MutableStateFlow(read("controls", DeviceControls.serializer()) ?: DeviceControls())
    val controls: StateFlow<DeviceControls> = _controls
    fun setControls(c: DeviceControls) { write("controls", DeviceControls.serializer(), c); _controls.value = c }

    private val _gateway = MutableStateFlow(read("gateway", GatewayConfig.serializer()) ?: GatewayConfig())
    val gateway: StateFlow<GatewayConfig> = _gateway
    fun setGateway(g: GatewayConfig) { write("gateway", GatewayConfig.serializer(), g); _gateway.value = g }

    var weather: WeatherDefaults
        get() = read("weather", WeatherDefaults.serializer()) ?: WeatherDefaults()
        set(v) = write("weather", WeatherDefaults.serializer(), v)

    var eventRetentionDays: Int
        get() = prefs.getInt("event_retention_days", 30)
        set(v) = prefs.edit().putInt("event_retention_days", v.coerceIn(1, 365)).apply()

    /** Device credential presented to the gateway. Generated once; rotated by re-enrollment. */
    fun deviceSecret(): String = getSecret("device_secret") ?: randomToken("sid_").also { putSecret("device_secret", it) }
    fun setDeviceSecret(value: String) { require(value.length in 20..300); putSecret("device_secret", value.trim()) }
    fun rotateDeviceSecret(): String = randomToken("sid_").also { putSecret("device_secret", it) }

    /** One-time enrollment token configured on the gateway (wrangler secret DEVICE_ENROLL_TOKEN). */
    fun enrollToken(): String? = getSecret("enroll_token")
    fun setEnrollToken(token: String?) = if (token.isNullOrBlank()) secretPrefs.edit().remove("enroll_token").apply() else putSecret("enroll_token", token.trim())

    /** Owner-accessible pairing credential, encrypted with the same non-exportable Keystore key. */
    fun botToken(): String? = getSecret("bot_token")
    fun setBotToken(token: String) { require(token.length in 20..300); putSecret("bot_token", token) }

    var enrolled: Boolean
        get() = prefs.getBoolean("enrolled", false)
        set(v) = prefs.edit().putBoolean("enrolled", v).apply()

    private fun <T> read(key: String, s: kotlinx.serialization.KSerializer<T>): T? =
        prefs.getString(key, null)?.let { runCatching { AppJson.decodeFromString(s, it) }.getOrNull() }

    private fun <T> write(key: String, s: kotlinx.serialization.KSerializer<T>, v: T) =
        prefs.edit().putString(key, AppJson.encodeToString(s, v)).apply()

    private fun putSecret(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(value.toByteArray())
        secretPrefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    private fun getSecret(name: String): String? {
        val blob = secretPrefs.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            String(cipher.doFinal(blob, 12, blob.size - 12))
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "siconnect_secrets_v1"
        private val rng = SecureRandom()

        fun randomToken(prefix: String, bytes: Int = 32): String {
            val b = ByteArray(bytes).also(rng::nextBytes)
            return prefix + Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
