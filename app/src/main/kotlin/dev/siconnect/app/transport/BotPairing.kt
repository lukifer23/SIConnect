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

/** Owner UI only. Retries reuse the same local bot and preserve its owner-selected grant. */
class BotPairing(private val g: RuntimeGraph) {
    private val lock = Mutex()
    suspend fun create(label: String): PairingInvitation = lock.withLock {
        g.ready.await()
        val name=label.trim()
        require(name.length in 1..60) { "Enter a bot name" }
        require(g.gateway.state.value == ConnectionState.ONLINE) { "Connect the phone first" }
        val previous=g.settings.pendingPairing()
        val now=System.currentTimeMillis()
        val reusable=previous?.takeIf { it.label==name }?.let { invitation ->
            g.agents.get(invitation.agentId)?.takeIf { !it.grant.revoked }?.let { agent ->
                val token=g.settings.pairedBotToken(agent.grant.agentId) ?: decrypt(invitation).getValue("agentToken").jsonPrimitive.content
                if(g.authenticate(token)?.grant?.agentId==agent.grant.agentId) agent to token else null
            }
        } ?: g.agents.all().firstNotNullOfOrNull { agent ->
            if(agent.grant.label!=name || agent.grant.revoked) null else g.settings.pairedBotToken(agent.grant.agentId)?.let { token ->
                if(g.authenticate(token)?.grant?.agentId==agent.grant.agentId) agent to token else null
            }
        }
        val invitation=if(previous!=null && previous.label==name && previous.expiresAt>now && reusable?.first?.grant?.agentId==previous.agentId) previous else {
            val agentId=reusable?.first?.grant?.agentId ?: Settings.randomToken("bot_",12)
            val token=reusable?.second ?: Settings.randomToken("sia_")
            // Commit the encrypted owner credential before making it available remotely.
            g.settings.setPairedBotToken(agentId,token)
            if(reusable==null) {
                val grant=AgentGrant(agentId,name,scopes=listOf("device.*","requests.*","apps.list","apps.inspect","shell.*","notifications.post","alarms.next","reminders.*","weather.forecast"),autoApproveUpTo=RiskClass.LOW_IMPACT)
                g.agents.put(AgentRecord(grant,Settings.sha256Hex(token),now,tokenHint="OAuth pairing"))
            }
            val code=g.settings.deviceId+"."+Settings.randomToken("pair_",18)
            val expiry=now+600_000
            val payload=buildJsonObject { put("agentId",agentId);put("deviceId",g.settings.deviceId);put("agentToken",token) }.toString()
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key(code)) }
            cipher.updateAAD(aad(agentId,expiry))
            val sealed=Base64.encodeToString(cipher.iv+cipher.doFinal(payload.toByteArray()),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            PairingInvitation(code,agentId,expiry,sealed,name).also { g.settings.setPendingPairing(it) }
        }
        reusable?.let { g.settings.setPairedBotToken(it.first.grant.agentId,it.second) }
        g.gateway.publishPairing(invitation)
        g.events.emit(Event(now,eventType="agent.pairing.created",agentId=invitation.agentId,deviceId=g.settings.deviceId,detail="Owner prepared temporary OAuth pairing invitation"))
        invitation
    }
    private fun key(code:String)=SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(code.toByteArray()),"AES")
    private fun aad(agentId:String,expiry:Long)="${g.settings.deviceId}|$agentId|$expiry".toByteArray()
    private fun decrypt(invitation:PairingInvitation):JsonObject {
        val bytes=Base64.decode(invitation.sealed,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(invitation.code),GCMParameterSpec(128,bytes,0,12));cipher.updateAAD(aad(invitation.agentId,invitation.expiresAt))
        return Json.parseToJsonElement(String(cipher.doFinal(bytes,12,bytes.size-12))).jsonObject
    }
    fun consumed(agentId: String) {
        val pending=g.settings.pendingPairing()?.takeIf { it.agentId==agentId }
        if(pending!=null) {
            // Recover owner token for invitations created by earlier app versions before clearing.
            if(g.settings.pairedBotToken(agentId)==null) g.settings.setPairedBotToken(agentId,decrypt(pending).getValue("agentToken").jsonPrimitive.content)
            g.settings.setPendingPairing(null)
        }
        g.events.emit(Event(System.currentTimeMillis(),eventType="agent.paired",agentId=agentId,deviceId=g.settings.deviceId,detail="OAuth invitation consumed; phone policy remains authoritative"))
    }
}
