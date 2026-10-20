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
import javax.crypto.spec.SecretKeySpec

@Serializable
data class PairingInvitation(val code: String, val agentId: String, val expiresAt: Long, val sealed: String, val label: String = "Dots")

/** Owner UI only. No remote invocation can create credentials or change grants. */
class BotPairing(private val g: RuntimeGraph) {
    private val lock = Mutex()
    suspend fun create(label: String): PairingInvitation = lock.withLock {
        g.ready.await()
        require(label.trim().length in 1..60) { "Enter a bot name" }
        require(g.gateway.state.value == ConnectionState.ONLINE) { "Connect the phone first" }
        val previous = g.settings.pendingPairing()
        val now = System.currentTimeMillis()
        val invitation = if (previous != null && previous.expiresAt > now && previous.label == label.trim() && g.agents.get(previous.agentId)?.grant?.revoked == false) previous else {
            val agentId = Settings.randomToken("bot_",12)
            val token = Settings.randomToken("sia_")
            val code = g.settings.deviceId + "." + Settings.randomToken("pair_",18)
            val expiry = now + 600_000
            val payload = buildJsonObject { put("agentId",agentId);put("deviceId",g.settings.deviceId);put("agentToken",token) }.toString()
            val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(code.toByteArray()),"AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key) }
            cipher.updateAAD("${g.settings.deviceId}|$agentId|$expiry".toByteArray())
            val sealed = Base64.encodeToString(cipher.iv+cipher.doFinal(payload.toByteArray()),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val grant = AgentGrant(agentId,label.trim(),scopes=listOf("device.*","requests.*","apps.list","apps.inspect","shell.*","notifications.post","alarms.next","reminders.*","weather.forecast"),autoApproveUpTo=RiskClass.LOW_IMPACT)
            g.agents.put(AgentRecord(grant,Settings.sha256Hex(token),now,tokenHint="OAuth paired"))
            PairingInvitation(code,agentId,expiry,sealed,label.trim()).also { g.settings.setPendingPairing(it) }
        }
        g.gateway.publishPairing(invitation)
        g.events.emit(Event(now,eventType="agent.pairing.created",agentId=invitation.agentId,deviceId=g.settings.deviceId,detail="Owner created temporary OAuth pairing invitation"))
        invitation
    }
    fun consumed(agentId: String) {
        if(g.settings.pendingPairing()?.agentId==agentId) g.settings.setPendingPairing(null)
        g.events.emit(Event(System.currentTimeMillis(),eventType="agent.paired",agentId=agentId,deviceId=g.settings.deviceId,detail="OAuth client paired; phone policy remains authoritative"))
    }
}
