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
 * Settings are stored in private SharedPreferences. Secret values use a non-exportable
 * Android Keystore AES-GCM key. The app is excluded from backups (see data_extraction_rules.xml).
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

    // Older versions kept the encrypted token in another preferences file. Copy the
    // ciphertext before deleting the old entry so interrupted migration can resume.
    init {
        synchronized(KEY_LOCK) {
            secretPrefs.getString("enroll_token", null)?.let { legacy ->
                if (!prefs.getBoolean("enrolled", false) && !prefs.contains("enroll_token")) {
                    check(prefs.edit().putString("enroll_token", legacy).commit()) { "Could not migrate gateway enrollment" }
                }
                check(secretPrefs.edit().remove("enroll_token").commit()) { "Could not finish enrollment migration" }
            }
        }
    }
    private val _gateway = MutableStateFlow(read("gateway", GatewayConfig.serializer()) ?: GatewayConfig())
    val gateway: StateFlow<GatewayConfig> = _gateway

    @Synchronized fun setGateway(g: GatewayConfig) {
        val edit=prefs.edit()
        if(g.baseUrl!=_gateway.value.baseUrl) edit.remove("enroll_token").putBoolean("enrolled",false)
        saveGateway(edit,g)
    }

    @Synchronized fun beginEnrollment(config:GatewayConfig,token:String) {
        require(token.isNotBlank())
        saveGateway(prefs.edit().putString("enroll_token",encryptSecret(token.trim())).putBoolean("enrolled",false),config)
    }

    private fun saveGateway(edit:android.content.SharedPreferences.Editor,config:GatewayConfig) {
        check(edit.putString("gateway",AppJson.encodeToString(GatewayConfig.serializer(),config)).commit()) { "Could not save gateway settings" }
        _gateway.value=config
    }

    @Synchronized fun pendingEnrollment(config:GatewayConfig):String? {
        if(_gateway.value!=config || prefs.getBoolean("enrolled",false)) return null
        return prefs.getString("enroll_token",null)?.let(::decryptSecret)
    }

    @Synchronized fun completeEnrollment(config:GatewayConfig,token:String) {
        if(pendingEnrollment(config)!=token) return
        check(prefs.edit().putBoolean("enrolled",true).remove("enroll_token").commit()) { "Could not finish gateway enrollment" }
    }

    var weather: WeatherDefaults
        get() = read("weather", WeatherDefaults.serializer()) ?: WeatherDefaults()
        set(v) = write("weather", WeatherDefaults.serializer(), v)

    var commute: CommuteProfile
        get() = read("commute", CommuteProfile.serializer()) ?: CommuteProfile()
        set(v) { check(prefs.edit().putString("commute", AppJson.encodeToString(CommuteProfile.serializer(), v.validated())).commit()) { "Could not save commute" } }

    var eventRetentionDays: Int
        get() = prefs.getInt("event_retention_days", 30)
        set(v) = prefs.edit().putInt("event_retention_days", v.coerceIn(1, 365)).apply()

    var executionRetention: RetentionPolicy
        get() = RetentionPolicy(prefs.getInt("execution_retention_days", 0).takeIf { it in RetentionPolicy.OPTIONS })
        set(value) { check(prefs.edit().putInt("execution_retention_days", value.days ?: 0).commit()) { "Could not save retention policy" } }

    /** Device credential presented to the gateway. Generated once; rotated by re-enrollment. */
    fun deviceSecret(): String = getSecret("device_secret") ?: randomToken("sid_").also { putSecret("device_secret", it) }
    fun setDeviceSecret(value: String) { require(value.length in 20..300); putSecret("device_secret", value.trim()) }
    fun rotateDeviceSecret(): String = randomToken("sid_").also { putSecret("device_secret", it) }

    /** Owner-accessible pairing credential, encrypted with the same non-exportable Keystore key. */
    fun botToken(): String? = getSecret("bot_token")
    fun setBotToken(token: String) { require(token.length in 20..300); putSecret("bot_token", token) }

    fun pairedBotToken(agentId:String):String? = getSecret("paired_"+agentId)
    fun setPairedBotToken(agentId:String,token:String) { require(agentId.matches(Regex("bot_[A-Za-z0-9_-]{16}")));require(token.length in 20..300);putSecret("paired_"+agentId,token) }
    fun pendingPairing(): dev.siconnect.app.transport.PairingInvitation? = getSecret("oauth_pairing")?.let {
        runCatching { AppJson.decodeFromString(dev.siconnect.app.transport.PairingInvitation.serializer(),it) }.getOrNull()
    }
    private val pairingChanges = MutableStateFlow(0L)
    val pairingRevision: StateFlow<Long> = pairingChanges
    fun setPendingPairing(value: dev.siconnect.app.transport.PairingInvitation?) {
        if(value==null) check(secretPrefs.edit().remove("oauth_pairing").commit()) { "Could not clear consumed pairing" }
        else putSecret("oauth_pairing",AppJson.encodeToString(dev.siconnect.app.transport.PairingInvitation.serializer(),value))
        pairingChanges.value++
    }

    internal fun clearRemotePairingSecrets(agentId:String?=null) {
        val edit=secretPrefs.edit()
        if(agentId==null || pendingPairing()?.agentId==agentId) edit.remove("oauth_pairing")
        if(agentId==null || agentId==DEVELOPMENT_AGENT_ID) edit.remove("bot_token")
        if(agentId==null) secretPrefs.all.keys.filter {it.startsWith("paired_")}.forEach { edit.remove(it) }
        else edit.remove("paired_" + agentId)
        check(edit.commit()) { "Access revoked. Retry to clear saved pairing credentials." }
        pairingChanges.value++
    }

    private fun <T> read(key: String, s: kotlinx.serialization.KSerializer<T>): T? =
        prefs.getString(key, null)?.let { runCatching { AppJson.decodeFromString(s, it) }.getOrNull() }

    private fun <T> write(key: String, s: kotlinx.serialization.KSerializer<T>, v: T) =
        prefs.edit().putString(key, AppJson.encodeToString(s, v)).apply()

    private fun putSecret(name:String,value:String) {
        check(secretPrefs.edit().putString(name,encryptSecret(value)).commit()) { "Could not persist encrypted credential" }
    }

    private fun encryptSecret(value:String):String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun getSecret(name:String):String? = secretPrefs.getString(name,null)?.let(::decryptSecret)

    private fun decryptSecret(encoded:String):String {
        val blob=Base64.decode(encoded,Base64.NO_WRAP)
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
