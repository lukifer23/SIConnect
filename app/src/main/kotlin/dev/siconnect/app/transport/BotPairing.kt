package dev.siconnect.app.transport

import android.util.Base64
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.data.*
import dev.siconnect.core.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class PairingInvitation(val code: String, val agentId: String, val expiresAt: Long, val sealed: String, val label: String = "Dots")

/** Owner UI only. Display labels never select or authenticate an identity. */
class BotPairing(private val g: RuntimeGraph) {
    private val lock = Mutex()

    /** Each explicit new-agent action creates an independent identity with least-privileged defaults. */
    suspend fun create(label: String): PairingInvitation = lock.withLock {
        g.ready.await()
        val name=label.trim()
        require(name.length in 1..60) { "Enter a bot name" }
        requireOnline()
        require(g.settings.pendingPairing()==null) { "Resume or dismiss the existing invitation before pairing another bot" }
        val agentId=Settings.randomToken("bot_",12)
        val token=Settings.randomToken("sia_")
        g.settings.setPairedBotToken(agentId,token)
        val grant=AgentGrant(agentId,name,scopes=listOf("device.*","requests.*","apps.list","apps.inspect","apps.inspect_detailed","shell.*","notifications.post","alarms.next","reminders.*","weather.forecast"),autoApproveUpTo=RiskClass.LOW_IMPACT)
        g.agents.put(AgentRecord(grant,Settings.sha256Hex(token),System.currentTimeMillis(),tokenHint="OAuth pairing"))
        publish(makeInvitation(agentId,name,token))
    }

    /** Explicit owner selection of one stable identity. Never searches by label or copies grants. */
    suspend fun reconnect(agentId: String): PairingInvitation = lock.withLock { reconnectLocked(agentId) }
    private suspend fun reconnectLocked(agentId:String):PairingInvitation {
        g.ready.await();requireOnline()
        val pending=g.settings.pendingPairing()
        require(pending==null || pending.agentId==agentId) { "Resume or dismiss the other pending invitation first" }
        val agent=requireNotNull(g.agents.get(agentId)) { "Bot no longer exists" }
        require(!agent.grant.revoked && (agent.grant.expiresAtMs?.let { it>System.currentTimeMillis() } ?: true)) { "Bot is revoked or expired" }
        val token=g.settings.pairedBotToken(agentId)
            ?: pending?.let { decrypt(it).getValue("agentToken").jsonPrimitive.content }
            ?: error("This bot uses a separately configured bearer connection")
        require(g.authenticate(token)?.grant?.agentId==agentId) { "Stored credential no longer belongs to this bot" }
        g.settings.setPairedBotToken(agentId,token)
        val invitation=pending?.takeIf { it.expiresAt>System.currentTimeMillis() }
            ?: makeInvitation(agentId,agent.grant.label,token)
        return publish(invitation)
    }

    /** Retry/renew targets the persisted invitation ID, including after ordinary process death. */
    suspend fun renew(agentId: String): PairingInvitation = lock.withLock {
        require(g.settings.pendingPairing()?.agentId==agentId) { "Invitation changed or was consumed" }
        reconnectLocked(agentId)
    }

    /** Closing the dialog does not abandon an invitation; this explicit owner action does. */
    suspend fun dismissInvitation(agentId: String) = lock.withLock {
        val pending=g.settings.pendingPairing() ?: return@withLock
        require(pending.agentId==agentId) { "Invitation changed" }
        g.settings.setPendingPairing(null)
        // Preserve the identity and its grants. Previously published codes still expire normally.
    }
    private fun requireOnline() {
        require(g.gateway.state.value == ConnectionState.ONLINE) { "Connect the phone first" }
    }
    private fun makeInvitation(agentId:String,name:String,token:String):PairingInvitation {
        val code=g.settings.deviceId+"."+Settings.randomToken("pair_",18)
        val expiry=System.currentTimeMillis()+600_000
        val payload=buildJsonObject { put("agentId",agentId);put("deviceId",g.settings.deviceId);put("agentToken",token) }.toString()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key(code)) }
        cipher.updateAAD(aad(agentId,expiry))
        val sealed=Base64.encodeToString(cipher.iv+cipher.doFinal(payload.toByteArray()),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return PairingInvitation(code,agentId,expiry,sealed,name).also { g.settings.setPendingPairing(it) }
    }
    private suspend fun publish(invitation:PairingInvitation):PairingInvitation {
        g.gateway.publishPairing(invitation)
        g.events.emit(Event(System.currentTimeMillis(),eventType="agent.pairing.created",agentId=invitation.agentId,deviceId=g.settings.deviceId,detail="Owner prepared temporary OAuth invitation for selected identity"))
        return invitation
    }
    private fun key(code:String)=SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(code.toByteArray()),"AES")
    private fun aad(agentId:String,expiry:Long)="${g.settings.deviceId}|$agentId|$expiry".toByteArray()
    private fun decrypt(invitation:PairingInvitation):JsonObject {
        val bytes=Base64.decode(invitation.sealed,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(invitation.code),GCMParameterSpec(128,bytes,0,12));cipher.updateAAD(aad(invitation.agentId,invitation.expiresAt))
        return Json.parseToJsonElement(String(cipher.doFinal(bytes,12,bytes.size-12))).jsonObject
    }
    suspend fun consumed(agentId: String) = lock.withLock {
        val pending=g.settings.pendingPairing()?.takeIf { it.agentId==agentId }
        if(pending!=null) {
            // Recover owner token for invitations created by earlier app versions before clearing.
            if(g.settings.pairedBotToken(agentId)==null) g.settings.setPairedBotToken(agentId,decrypt(pending).getValue("agentToken").jsonPrimitive.content)
            g.settings.setPendingPairing(null)
        }
        g.events.emit(Event(System.currentTimeMillis(),eventType="agent.paired",agentId=agentId,deviceId=g.settings.deviceId,detail="OAuth invitation consumed; phone policy remains authoritative"))
    }
}
