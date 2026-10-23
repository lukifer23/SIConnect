package dev.siconnect.app.bootstrap

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/** RSA private key stays nonexportable in Android Keystore, including during TLS signing. */
internal class AdbHostIdentity(context:Context,private val alias:String=ALIAS) {
    private val prefs=context.getSharedPreferences(if(alias==ALIAS) "local_adb" else alias,Context.MODE_PRIVATE)
    val pairedAt:Long get()=prefs.getLong("paired_at",0)
    val lastBootstrap:Long get()=prefs.getLong("last_bootstrap",0)
    var recover:Boolean
        get()=prefs.getBoolean("owner_recovery",false)
        set(value) {check(prefs.edit().putBoolean("owner_recovery",value).commit())}
    fun paired() {check(prefs.edit().putLong("paired_at",System.currentTimeMillis()).commit())}
    fun bootstrapped() {check(prefs.edit().putLong("last_bootstrap",System.currentTimeMillis()).commit())}
    data class Material(val key:PrivateKey,val certificate:X509Certificate) {
        val fingerprint:String get()=MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded).joinToString("") {"%02x".format(it)}
    }
    fun existing():Material?=synchronized(LOCK) {
        check(!prefs.getBoolean("reset_pending",false)) {"Host identity reset was interrupted. Retry Forget local pairing before creating another key."}
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        if(!store.containsAlias(alias)) {
            check(pairedAt==0L) {"Paired host key is missing; owner reset and re-pairing are required"}
            return null
        }
        val entry=store.getEntry(alias,null) as? KeyStore.PrivateKeyEntry ?: error("Host identity is unreadable; preserved for recovery")
        Material(entry.privateKey,entry.certificate as X509Certificate)
    }
    fun loadOrCreate():Material=synchronized(LOCK) {
        existing()?.let {return it}
        val generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN)
            .setKeySize(2048).setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            // Conscrypt encodes the PSS block itself and delegates a raw RSA private-key operation.
            // This enables signing that encoded block without exporting the key.
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setCertificateSubject(X500Principal("CN=SIConnect local ADB"))
            .setCertificateSerialNumber(BigInteger.ONE).setCertificateNotBefore(Date(0))
            .setCertificateNotAfter(Date(4102444800000L)).build())
        generator.generateKeyPair()
        requireNotNull(existing())
    }
    /** Called only after a separate, fresh local-owner authentication and explicit reset choice. */
    fun reset()=synchronized(LOCK) {
        // Keystore and preferences cannot share a transaction. Persist a fail-closed intent first.
        // A deletion/storage failure preserves the marker and never generates a replacement key.
        check(prefs.edit().putBoolean("reset_pending",true).putBoolean("owner_recovery",false).commit())
        KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}
        check(prefs.edit().putLong("paired_at",0).putLong("last_bootstrap",0).putBoolean("reset_pending",false).commit())
    }
    companion object {private val LOCK=Any();private const val ALIAS="siconnect_local_adb_rsa_v1"}
}
