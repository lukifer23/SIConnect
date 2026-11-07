package dev.siconnect.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class ShellMode {
    /** Every arbitrary shell command needs owner approval (default). */
    APPROVAL_REQUIRED,
    /** Only commands that exactly equal an entry in [AgentGrant.shellStandingCommands] run unattended. */
    STANDING_COMMANDS,
    /**
     * Any shell command (below CRITICAL) runs unattended. A broad shell grant can perform anything
     * the backend identity can — including actions that narrower capability rules would gate.
     */
    BROAD,
}

/** Owner-authored standing approval: an exact-match constraint over specific argument values. */
@Serializable
data class StandingGrant(
    val capabilityId: String,
    val constraints: Map<String, JsonElement> = emptyMap(),
    val maxRisk: RiskClass = RiskClass.MODERATE_IMPACT,
    val expiresAtMs: Long? = null,
    val note: String? = null,
    /** Exact reviewed capability version and canonical arguments. Null retains constrained-grant semantics. */
    val actionHash: String? = null,
    val backend: BackendKind? = null,
)

@Serializable
data class AgentGrant(
    val agentId: String,
    val label: String,
    /** Capability-id globs this agent may invoke at all, e.g. "device.*", "calendar.list". */
    val scopes: List<String> = listOf("device.*"),
    val denied: List<String> = emptyList(),
    /** Operations at or below this risk run without approval (subject to every other rule). */
    val autoApproveUpTo: RiskClass = RiskClass.READ_ONLY,
    val shellMode: ShellMode = ShellMode.APPROVAL_REQUIRED,
    val shellStandingCommands: List<String> = emptyList(),
    val standing: List<StandingGrant> = emptyList(),
    val revoked: Boolean = false,
    val expiresAtMs: Long? = null,
    /** Owner choice: reject shared-screen, possible-screen and unspecified interactions. */
    val allowSharedScreen: Boolean = true,
    /** Explicit durable local-owner full-control profile; normal eligibility checks still apply. */
    val fullControl: Boolean = false,
)

@Serializable
data class DeviceControls(
    /** Emergency stop: when false every remote request is denied before evaluation. */
    val remoteExecutionEnabled: Boolean = true,
    /** When false privileged backends refuse work even if healthy. */
    val privilegedEnabled: Boolean = true,
)

@Serializable
data class PolicyDecision(val outcome: PolicyOutcome, val reason: String, val rule: String)

enum class Origin { REMOTE, LOCAL_AUTOMATION, LOCAL_OWNER }

/**
 * The one policy engine. Pure and deterministic: no I/O, no model reasoning.
 * Rule order matters; the first matching terminal rule wins.
 */
class PolicyEngine {
    fun evaluate(
        agent: AgentGrant?,
        descriptor: CapabilityDescriptor,
        args: JsonObject,
        assessment: RiskAssessment,
        availability: Availability,
        controls: DeviceControls,
        origin: Origin,
        nowMs: Long,
    ): PolicyDecision {
        if (origin == Origin.REMOTE && !controls.remoteExecutionEnabled)
            return deny("Remote execution is disabled on the device (emergency stop)", "emergency_stop")
        if (agent == null) return deny("Unknown agent", "unknown_agent")
        if (agent.revoked) return deny("Agent access has been revoked", "agent_revoked")
        if (agent.expiresAtMs != null && nowMs >= agent.expiresAtMs) return deny("Agent grant expired", "agent_expired")
        if (agent.denied.any { globMatches(it, descriptor.id) }) return deny("Capability denied for this agent", "agent_denylist")
        if (agent.scopes.none { globMatches(it, descriptor.id) }) return deny("Capability outside agent scope", "agent_scope")
        if(descriptor.ownerOnly && (origin!=Origin.LOCAL_OWNER || agent.agentId!="local-owner"))
            return deny("Operation is restricted to the local owner runtime", "owner_only")
        if(!agent.allowSharedScreen && descriptor.interaction !in setOf(InteractionMode.BACKGROUND,InteractionMode.ISOLATED_SCREEN))
            return deny("Owner restricted this agent to background APIs and private displays", "shared_screen_disabled")
        if (!availability.isExecutable)
            return PolicyDecision(PolicyOutcome.UNAVAILABLE, "Capability is $availability", "availability")
        if (descriptor.backend in PRIVILEGED && !controls.privilegedEnabled &&
            !(origin==Origin.LOCAL_OWNER && descriptor.id=="headless.stop_all"))
            return PolicyDecision(PolicyOutcome.UNAVAILABLE, "Privileged execution is disabled by the owner", "privileged_disabled")

        val risk = maxOf(descriptor.risk, assessment.risk)
        if (descriptor.id == "shell.execute" && assessment.shellCommand.isNullOrBlank())
            return deny("Shell command assessment is required", "shell_assessment_missing")
        if (risk == RiskClass.CRITICAL && !agent.fullControl)
            return approval("CRITICAL operations require approval outside the owner-enabled full-control profile", "critical")

        agent.standing.firstOrNull {
            (assessment.shellCommand == null || it.actionHash != null) && matchesStanding(it, descriptor, args, risk, nowMs)
        }?.let { return allow("Standing grant for ${it.capabilityId}", "standing_grant") }

        assessment.shellCommand?.let { cmd ->
            return when (agent.shellMode) {
                ShellMode.BROAD -> allow("Agent holds a broad shell grant", "shell_broad")
                ShellMode.STANDING_COMMANDS ->
                    if (cmd in agent.shellStandingCommands) allow("Exact standing shell command", "shell_standing")
                    else approval("Shell command not covered by a standing grant", "shell_default")
                ShellMode.APPROVAL_REQUIRED -> approval("Arbitrary shell commands require approval", "shell_default")
            }
        }

        if (risk <= agent.autoApproveUpTo) return allow("Risk $risk within agent auto-approve level", "auto_risk")
        return approval("Risk $risk exceeds agent auto-approve level ${agent.autoApproveUpTo}", "risk_threshold")
    }

    private fun matchesStanding(g: StandingGrant, descriptor: CapabilityDescriptor, args: JsonObject, risk: RiskClass, now: Long): Boolean {
        if (g.capabilityId != descriptor.id) return false
        if (g.backend != null && g.backend != descriptor.backend) return false
        if (g.actionHash != null && g.actionHash != Canonical.actionHash(descriptor.id, descriptor.version, args)) return false
        if (g.expiresAtMs != null && now >= g.expiresAtMs) return false
        if (risk > g.maxRisk) return false
        return g.constraints.all { (k, v) -> args[k] == v }
    }

    private fun allow(r: String, rule: String) = PolicyDecision(PolicyOutcome.ALLOW, r, rule)
    private fun approval(r: String, rule: String) = PolicyDecision(PolicyOutcome.REQUIRE_APPROVAL, r, rule)
    private fun deny(r: String, rule: String) = PolicyDecision(PolicyOutcome.DENY, r, rule)

    companion object {
        val PRIVILEGED = setOf(BackendKind.SHIZUKU, BackendKind.ADB_SERVICE, BackendKind.ROOT)

        fun globMatches(pattern: String, id: String): Boolean {
            if (pattern == "*") return true
            if (pattern.endsWith(".*")) return id.startsWith(pattern.dropLast(1))
            return pattern == id
        }
    }
}
