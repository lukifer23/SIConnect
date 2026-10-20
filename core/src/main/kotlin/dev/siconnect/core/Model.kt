package dev.siconnect.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Ordered: comparisons such as `risk <= grant.autoApproveUpTo` rely on declaration order. */
@Serializable
enum class RiskClass { READ_ONLY, LOW_IMPACT, MODERATE_IMPACT, HIGH_IMPACT, CRITICAL }

@Serializable
enum class Reversibility { NOT_APPLICABLE, REVERSIBLE, PARTIALLY_REVERSIBLE, IRREVERSIBLE }

/**
 * Availability is reported per capability at runtime. Only [isExecutable] states may run.
 * VERIFIED is reserved for capabilities that passed a physical-device test; the app never sets it
 * at runtime — it comes from the static verification ledger (docs/capability-coverage.md).
 */
@Serializable
enum class Availability {
    UNKNOWN, SUPPORTED, IMPLEMENTED, VERIFIED, DEGRADED,
    PERMISSION_REQUIRED, PRIVILEGE_REQUIRED, TEMPORARILY_UNAVAILABLE, UNSUPPORTED;

    val isExecutable: Boolean get() = this == IMPLEMENTED || this == VERIFIED || this == DEGRADED
}

@Serializable
enum class PolicyOutcome { ALLOW, REQUIRE_APPROVAL, DENY, UNAVAILABLE }

/** Execution identity that actually performs an operation. Reported verbatim in results. */
@Serializable
enum class BackendKind { NATIVE, APP_SHELL, SHIZUKU, ADB_SERVICE, ACCESSIBILITY, NETWORK, ROOT }

@Serializable
enum class RequestState {
    RECEIVED, VALIDATED, AWAITING_APPROVAL, APPROVED, DENIED, EXPIRED,
    EXECUTING, VERIFYING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN_OUTCOME;

    val isTerminal: Boolean get() = this in TERMINAL

    fun canTransitionTo(next: RequestState): Boolean = next in (TRANSITIONS[this] ?: emptySet())

    companion object {
        val TERMINAL = setOf(DENIED, EXPIRED, SUCCEEDED, FAILED, CANCELLED, UNKNOWN_OUTCOME)
        private val TRANSITIONS: Map<RequestState, Set<RequestState>> = mapOf(
            RECEIVED to setOf(VALIDATED, DENIED, FAILED, EXPIRED, CANCELLED),
            VALIDATED to setOf(AWAITING_APPROVAL, EXECUTING, DENIED, FAILED, EXPIRED, CANCELLED),
            AWAITING_APPROVAL to setOf(APPROVED, DENIED, EXPIRED, CANCELLED),
            APPROVED to setOf(EXECUTING, EXPIRED, CANCELLED, FAILED),
            EXECUTING to setOf(VERIFYING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN_OUTCOME),
            VERIFYING to setOf(SUCCEEDED, FAILED, UNKNOWN_OUTCOME),
        )
    }
}

@Serializable
data class CapabilityDescriptor(
    val id: String,
    val version: Int = 1,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val permissions: List<String> = emptyList(),
    val backend: BackendKind,
    /** Baseline risk; [Capability.assess] may raise it for specific arguments. */
    val risk: RiskClass,
    val sideEffects: List<String> = emptyList(),
    val reversibility: Reversibility = Reversibility.NOT_APPLICABLE,
    val timeoutMs: Long = 30_000,
    val retryable: Boolean = false,
    val verification: String = "none",
) {
    /** MCP / OpenAI-compatible tool name: dots are not allowed by several clients. */
    val toolName: String get() = id.replace('.', '_')
}

/** Per-invocation risk evaluation produced by the capability from concrete arguments. */
@Serializable
data class RiskAssessment(
    val risk: RiskClass,
    val summary: String,
    val target: String? = null,
    val sideEffects: List<String> = emptyList(),
    val reversibility: Reversibility = Reversibility.NOT_APPLICABLE,
    /** Exact shell command when the operation executes one; shown verbatim in approvals. */
    val shellCommand: String? = null,
    val externalCommunication: Boolean = false,
    val affectedItems: Int = 1,
)

@Serializable
data class CapabilityResult(
    val output: JsonElement,
    val backend: BackendKind,
    /** e.g. "uid=2000(shell)" — whoever actually executed. */
    val identity: String? = null,
    val verification: VerificationStatus = VerificationStatus.NOT_APPLICABLE,
    val verificationDetail: String? = null,
    val errorCode: String? = null,
)

@Serializable
enum class VerificationStatus { NOT_APPLICABLE, VERIFIED, NOT_VERIFIED, FAILED }

/** Thrown by capabilities for expected failures; carries a stable machine-readable code. */
class CapabilityException(val code: String, message: String, val retryable: Boolean = false) : Exception(message)

data class ExecutionContext(
    val requestId: String,
    val traceId: String,
    val agentId: String,
    val deviceId: String,
)

interface Capability {
    val descriptor: CapabilityDescriptor
    fun availability(): Availability
    /** Human-readable reason when not executable (missing permission, backend down...). */
    fun availabilityDetail(): String? = null
    fun assess(args: JsonObject): RiskAssessment = RiskAssessment(
        risk = descriptor.risk,
        summary = descriptor.title,
        sideEffects = descriptor.sideEffects,
        reversibility = descriptor.reversibility,
    )
    suspend fun execute(args: JsonObject, ctx: ExecutionContext): CapabilityResult
}
