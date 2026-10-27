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

@Serializable
data class CommuteProfile(
    val destination: String = "",
    val timezone: String = java.time.ZoneId.systemDefault().id,
    val wakeTime: String = "",
    val arrivalTime: String = "",
    val preparationMinutes: Int? = null,
    val bufferMinutes: Int? = null,
    val destinationLabel: String = "",
) {
    fun validated(): CommuteProfile {
        require(destination.length <= 500) { "Destination is too long" }
        require(destinationLabel.length <= 200) { "Destination name is too long" }
        java.time.ZoneId.of(timezone)
        listOf(wakeTime, arrivalTime).filter(String::isNotBlank).forEach {
            require(it.matches(Regex("[0-2][0-9]:[0-5][0-9]"))) { "Use HH:mm times" }
            java.time.LocalTime.parse(it)
        }
        require((preparationMinutes==null || preparationMinutes in 0..360) && (bufferMinutes==null || bufferMinutes in 0..180)) { "Check preparation and buffer minutes" }
        return copy(destination=destination.trim(),destinationLabel=destinationLabel.trim())
    }
}

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

    private val _appearance=MutableStateFlow(prefs.getString("appearance","SYSTEM")?.takeIf {it in setOf("SYSTEM","LIGHT","DARK")} ?: "SYSTEM")
    val appearance:StateFlow<String> = _appearance
    fun setAppearance(value:String) {require(value in setOf("SYSTEM","LIGHT","DARK"));prefs.edit().putString("appearance",value).apply();_appearance.value=value}

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

    var commute: CommuteProfile
        get() = read("commute", CommuteProfile.serializer()) ?: CommuteProfile()
        set(v) { check(prefs.edit().putString("commute", AppJson.encodeToString(CommuteProfile.serializer(), v.validated())).commit()) { "Could not save commute" } }

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

    fun pairedBotToken(agentId:String):String? = getSecret("paired_"+agentId)
    fun setPairedBotToken(agentId:String,token:String) { require(agentId.matches(Regex("bot_[A-Za-z0-9_-]{16}")));require(token.length in 20..300);putSecret("paired_"+agentId,token) }
    fun pendingPairing(): dev.siconnect.app.transport.PairingInvitation? = getSecret("oauth_pairing")?.let {
        runCatching { AppJson.decodeFromString(dev.siconnect.app.transport.PairingInvitation.serializer(),it) }.getOrNull()
    }
    fun setPendingPairing(value: dev.siconnect.app.transport.PairingInvitation?) {
        if(value==null) check(secretPrefs.edit().remove("oauth_pairing").commit()) { "Could not clear consumed pairing" }
        else putSecret("oauth_pairing",AppJson.encodeToString(dev.siconnect.app.transport.PairingInvitation.serializer(),value))
    }

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
        check(secretPrefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) { "Could not persist encrypted credential" }
    }

    private fun getSecret(name: String): String? {
        val blob = secretPrefs.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        // A damaged credential must never be mistaken for an absent one and regenerated.
        return try {
            require(blob.size>=28) { "Encrypted credential is truncated" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            String(cipher.doFinal(blob, 12, blob.size - 12))
        } catch(e:Exception) { throw IllegalStateException("Stored credential could not be decrypted; existing data was preserved",e) }
    }

    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    companion object {
        private val KEY_LOCK = Any()
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
