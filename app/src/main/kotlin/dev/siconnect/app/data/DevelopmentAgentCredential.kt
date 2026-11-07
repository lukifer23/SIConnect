package dev.siconnect.app.data

import dev.siconnect.core.AgentGrant
import dev.siconnect.core.RiskClass

internal const val DEVELOPMENT_AGENT_ID="development-client"

/** Rotating a credential preserves owner authorization, identity history, and the saved custom profile. */
internal fun developmentAgentWithCredential(existing:AgentRecord?,tokenSha256:String,tokenHint:String,nowMs:Long):AgentRecord {
    require(existing==null || existing.grant.agentId==DEVELOPMENT_AGENT_ID) {"Existing development identity does not match its record"}
    return existing?.copy(tokenSha256=tokenSha256,tokenHint=tokenHint) ?: AgentRecord(
        AgentGrant(DEVELOPMENT_AGENT_ID,"ADB development client",
            scopes=listOf("device.*","requests.*","apps.list","apps.inspect","shell.*","notifications.post","alarms.next","reminders.*","weather.forecast"),
            autoApproveUpTo=RiskClass.LOW_IMPACT),tokenSha256,nowMs,tokenHint=tokenHint)
}
