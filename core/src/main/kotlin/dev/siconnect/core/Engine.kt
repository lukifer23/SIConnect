package dev.siconnect.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class InvocationRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val traceId: String = requestId,
    val agentId: String,
    val capabilityId: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val idempotencyKey: String? = null,
    val expiresAtMs: Long? = null,
    val parentRequestId: String? = null,
)

@Serializable
data class RequestRecord(
    val requestId: String,
    val traceId: String,
    val agentId: String,
    val deviceId: String,
    val capabilityId: String,
    val capabilityVersion: Int = 0,
    val arguments: JsonObject,
    val argsHash: String,
    val idempotencyKey: String? = null,
    val origin: String,
    val state: RequestState,
    val policy: PolicyDecision? = null,
    val assessment: RiskAssessment? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val expiresAtMs: Long,
    val approvalExpiresAtMs: Long? = null,
    val approvedBy: String? = null,
    val result: CapabilityResult? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val parentRequestId: String? = null,
)

interface RequestStore {
    /** Returns false if a record with the same id or (agentId, idempotencyKey) already exists. */
    fun insert(record: RequestRecord): Boolean
    /** Compare-and-set on state; returns false if the stored state differs from [expected]. */
    fun update(record: RequestRecord, expected: RequestState): Boolean
    fun get(requestId: String): RequestRecord?
    fun findByIdempotencyKey(agentId: String, key: String): RequestRecord?
    fun listByStates(states: Set<RequestState>): List<RequestRecord>
}

@Serializable
data class Event(
    val timestampMs: Long,
    val eventId: String = UUID.randomUUID().toString(),
    val eventType: String,
    val traceId: String? = null,
    val requestId: String? = null,
    val agentId: String? = null,
    val deviceId: String? = null,
    val capabilityId: String? = null,
    val backend: String? = null,
    val policyDecision: String? = null,
    val executionStatus: String? = null,
    val durationMs: Long? = null,
    val errorCode: String? = null,
    val verificationStatus: String? = null,
    /** Short, already-redacted detail. Never tokens, keys or message bodies. */
    val detail: String? = null,
)

fun interface EventSink { fun emit(event: Event) }

interface ApprovalGate {
    fun approvalRequested(record: RequestRecord)
    fun approvalClosed(record: RequestRecord)
}

fun interface AgentDirectory { fun grant(agentId: String): AgentGrant? }

fun interface Clock { fun nowMs(): Long }

class ExecutionEngine(
    private val deviceId: String,
    private val registry: CapabilityRegistry,
    private val policy: PolicyEngine,
    private val store: RequestStore,
    private val events: EventSink,
    private val approvals: ApprovalGate,
    private val agents: AgentDirectory,
    private val controls: () -> DeviceControls,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val defaultExpiryMs: Long = 15 * 60_000,
    private val approvalTtlMs: Long = 10 * 60_000,
) {
    private val updates = MutableSharedFlow<RequestRecord>(extraBufferCapacity = 256)
    val recordUpdates: SharedFlow<RequestRecord> = updates
    private val running = ConcurrentHashMap<String, Job>()

    /**
     * Submits a request and runs it to the point where it either finishes or waits for approval.
     * Duplicate submissions (same request id or idempotency key) return the existing record and never re-execute.
     */
    suspend fun submit(req: InvocationRequest, origin: Origin = Origin.REMOTE): RequestRecord {
        val now = clock.nowMs()
        req.idempotencyKey?.let { key ->
            store.findByIdempotencyKey(req.agentId, key)?.let { existing ->
                log(existing, "request.duplicate", detail = "idempotency key reuse")
                return existing
            }
        }
        store.get(req.requestId)?.let { existing ->
            log(existing, "request.duplicate", detail = "request id reuse")
            return existing
        }
        val capability = registry.get(req.capabilityId)
        val version = capability?.descriptor?.version ?: 0
        val capId = capability?.descriptor?.id ?: req.capabilityId
        var record = RequestRecord(
            requestId = req.requestId, traceId = req.traceId, agentId = req.agentId, deviceId = deviceId,
            capabilityId = capId, capabilityVersion = version, arguments = req.arguments,
            argsHash = Canonical.actionHash(capId, version, req.arguments), idempotencyKey = req.idempotencyKey,
            origin = origin.name, state = RequestState.RECEIVED, createdAtMs = now, updatedAtMs = now,
            expiresAtMs = req.expiresAtMs ?: (now + defaultExpiryMs), parentRequestId = req.parentRequestId,
        )
        if (!store.insert(record)) {
            // Lost a race with a concurrent duplicate; return whatever won.
            return store.get(req.requestId)
                ?: req.idempotencyKey?.let { store.findByIdempotencyKey(req.agentId, it) }
                ?: error("insert conflict without existing record")
        }
        log(record, "request.received")

        if (capability == null) return fail(record, "UNKNOWN_CAPABILITY", "No capability '${req.capabilityId}'")
        if (now >= record.expiresAtMs) return transition(record, RequestState.EXPIRED, "request.expired") { it }

        val errors = Schema.validate(capability.descriptor.inputSchema, req.arguments)
        if (errors.isNotEmpty()) return fail(record, "INVALID_ARGUMENTS", errors.take(10).joinToString("; "))

        val assessment = runCatching { capability.assess(req.arguments) }.getOrElse {
            return fail(record, "ASSESSMENT_FAILED", it.message ?: "assessment failed")
        }
        val availability = runCatching { capability.availability() }.getOrDefault(Availability.UNKNOWN)
        val decision = policy.evaluate(
            agents.grant(req.agentId), capability.descriptor, req.arguments, assessment,
            availability, controls(), origin, now,
        )
        record = transition(record, RequestState.VALIDATED, "policy.evaluated", decision = decision) {
            it.copy(policy = decision, assessment = assessment)
        }
        return when (decision.outcome) {
            PolicyOutcome.ALLOW -> execute(record, capability)
            PolicyOutcome.DENY -> transition(record, RequestState.DENIED, "request.denied") {
                it.copy(errorCode = "POLICY_DENIED", errorMessage = decision.reason)
            }
            PolicyOutcome.UNAVAILABLE -> fail(
                record, "UNAVAILABLE",
                listOfNotNull(decision.reason, capability.availabilityDetail()).joinToString(": "),
            )
            PolicyOutcome.REQUIRE_APPROVAL -> {
                val approvalExpiry = minOf(record.expiresAtMs, now + approvalTtlMs)
                val waiting = transition(record, RequestState.AWAITING_APPROVAL, "approval.requested") {
                    it.copy(approvalExpiresAtMs = approvalExpiry)
                }
                approvals.approvalRequested(waiting)
                waiting
            }
        }
    }

    /**
     * Owner decision on a pending approval. [expectedArgsHash] must match the stored record, so the
     * approval is bound to the exact action that was displayed. Execution uses the stored arguments only.
     */
    suspend fun resolveApproval(requestId: String, approve: Boolean, approver: String, expectedArgsHash: String): RequestRecord {
        val record = store.get(requestId) ?: throw IllegalArgumentException("No request $requestId")
        if (record.state != RequestState.AWAITING_APPROVAL) return record
        if (record.argsHash != expectedArgsHash) throw IllegalStateException("Approval does not match the pending action")
        val now = clock.nowMs()
        if (now >= (record.approvalExpiresAtMs ?: record.expiresAtMs)) {
            return transition(record, RequestState.EXPIRED, "approval.expired") { it }.also(approvals::approvalClosed)
        }
        if (!approve) {
            return transition(record, RequestState.DENIED, "approval.denied") {
                it.copy(errorCode = "OWNER_DENIED", errorMessage = "Denied by $approver", approvedBy = approver)
            }.also(approvals::approvalClosed)
        }
        val approved = transition(record, RequestState.APPROVED, "approval.granted") { it.copy(approvedBy = approver) }
        approvals.approvalClosed(approved)
        val capability = registry.get(approved.capabilityId)
            ?: return fail(approved, "UNKNOWN_CAPABILITY", "Capability disappeared")
        if (capability.descriptor.version != approved.capabilityVersion)
            return fail(approved, "CAPABILITY_CHANGED", "Capability version changed after approval")
        // Re-check availability and kill switches at execution time; approval does not override them.
        val c = controls()
        if (approved.origin == Origin.REMOTE.name && !c.remoteExecutionEnabled)
            return fail(approved, "EMERGENCY_STOP", "Remote execution disabled")
        if (capability.descriptor.backend in PolicyEngine.PRIVILEGED && !c.privilegedEnabled)
            return fail(approved, "PRIVILEGED_DISABLED", "Privileged execution disabled")
        if (!capability.availability().isExecutable)
            return fail(approved, "UNAVAILABLE", capability.availabilityDetail() ?: "Capability unavailable")
        return execute(approved, capability)
    }

    suspend fun cancel(requestId: String, by: String): RequestRecord? {
        val record = store.get(requestId) ?: return null
        return when (record.state) {
            RequestState.RECEIVED, RequestState.VALIDATED, RequestState.AWAITING_APPROVAL, RequestState.APPROVED ->
                transition(record, RequestState.CANCELLED, "request.cancelled", detail = "by $by") { it }
                    .also(approvals::approvalClosed)
            RequestState.EXECUTING -> { running[requestId]?.cancel(CancellationException("cancelled by $by")); store.get(requestId) }
            else -> record
        }
    }

    /** Waits up to [timeoutMs] for a terminal state or AWAITING_APPROVAL. */
    suspend fun await(requestId: String, timeoutMs: Long, untilTerminal: Boolean = true): RequestRecord? {
        fun done(r: RequestRecord) = r.state.isTerminal || (!untilTerminal && r.state == RequestState.AWAITING_APPROVAL)
        store.get(requestId)?.let { if (done(it)) return it }
        return withTimeoutOrNull(timeoutMs) {
            updates.first { it.requestId == requestId && done(it) }
        } ?: store.get(requestId)
    }

    /**
     * Startup reconciliation after process death: work that was mid-execution has an unknown outcome
     * (it may or may not have happened); expired approvals are closed. Nothing is re-executed.
     */
    fun recover(): Int {
        val now = clock.nowMs()
        var changed = 0
        for (r in store.listByStates(setOf(RequestState.EXECUTING, RequestState.VERIFYING))) {
            transitionBlocking(r, RequestState.UNKNOWN_OUTCOME, "request.recovered_unknown") {
                it.copy(errorCode = "INTERRUPTED", errorMessage = "Process ended during execution")
            }; changed++
        }
        for (r in store.listByStates(setOf(RequestState.RECEIVED, RequestState.VALIDATED, RequestState.APPROVED))) {
            transitionBlocking(r, RequestState.FAILED, "request.recovered_failed") {
                it.copy(errorCode = "INTERRUPTED", errorMessage = "Process ended before execution; not executed")
            }; changed++
        }
        for (r in store.listByStates(setOf(RequestState.AWAITING_APPROVAL))) {
            if (now >= (r.approvalExpiresAtMs ?: r.expiresAtMs)) {
                transitionBlocking(r, RequestState.EXPIRED, "approval.expired") { it }
                    ?.let(approvals::approvalClosed); changed++
            } else approvals.approvalRequested(r)
        }
        return changed
    }

    /** Periodic sweep closing approvals whose window lapsed. */
    fun expireStale() {
        val now = clock.nowMs()
        store.listByStates(setOf(RequestState.AWAITING_APPROVAL))
            .filter { now >= (it.approvalExpiresAtMs ?: it.expiresAtMs) }
            .forEach { r -> transitionBlocking(r, RequestState.EXPIRED, "approval.expired") { it }?.let(approvals::approvalClosed) }
    }

    private suspend fun execute(record: RequestRecord, capability: Capability): RequestRecord {
        if (clock.nowMs() >= record.expiresAtMs) return transition(record, RequestState.EXPIRED, "request.expired") { it }
        val executing = transition(record, RequestState.EXECUTING, "execution.started", backend = capability.descriptor.backend.name) { it }
        val ctx = ExecutionContext(executing.requestId, executing.traceId, executing.agentId, deviceId)
        val started = clock.nowMs()
        val job = scope.launch {
            try {
                val result = withTimeout(capability.descriptor.timeoutMs) { capability.execute(executing.arguments, ctx) }
                val state = if (result.verification == VerificationStatus.FAILED) RequestState.FAILED else RequestState.SUCCEEDED
                transition(executing, state, "execution.finished", backend = result.backend.name,
                    durationMs = clock.nowMs() - started, verification = result.verification.name) {
                    it.copy(result = result, errorCode = if (state == RequestState.FAILED) "VERIFICATION_FAILED" else null,
                        errorMessage = if (state == RequestState.FAILED) result.verificationDetail else null)
                }
            } catch (e: TimeoutCancellationException) {
                unknownOrFailed(executing, capability, "TIMEOUT", "Exceeded ${capability.descriptor.timeoutMs} ms", started)
            } catch (e: CancellationException) {
                unknownOrFailed(executing, capability, "CANCELLED", e.message ?: "cancelled", started, cancelled = true)
            } catch (e: CapabilityException) {
                fail(executing, e.code, e.message ?: e.code, clock.nowMs() - started)
            } catch (e: Exception) {
                fail(executing, "EXECUTION_ERROR", "${e::class.simpleName}: ${e.message}", clock.nowMs() - started)
            }
        }
        running[executing.requestId] = job
        try { job.join() } finally { running.remove(executing.requestId) }
        return store.get(executing.requestId) ?: executing
    }

    /** Side-effecting work interrupted midway may have partly happened: report UNKNOWN_OUTCOME, never success. */
    private fun unknownOrFailed(
        r: RequestRecord, c: Capability, code: String, msg: String, started: Long, cancelled: Boolean = false,
    ): RequestRecord {
        val sideEffects = c.descriptor.sideEffects.isNotEmpty() || (r.assessment?.sideEffects?.isNotEmpty() == true) ||
            r.assessment?.shellCommand != null
        val state = when {
            sideEffects -> RequestState.UNKNOWN_OUTCOME
            cancelled -> RequestState.CANCELLED
            else -> RequestState.FAILED
        }
        return transition(r, state, "execution.interrupted", durationMs = clock.nowMs() - started, error = code) {
            it.copy(errorCode = code, errorMessage = msg)
        }
    }

    private fun fail(r: RequestRecord, code: String, msg: String, durationMs: Long? = null): RequestRecord =
        transition(r, RequestState.FAILED, "request.failed", durationMs = durationMs, error = code) {
            it.copy(errorCode = code, errorMessage = msg)
        }

    private fun transition(
        r: RequestRecord, next: RequestState, eventType: String,
        decision: PolicyDecision? = null, backend: String? = null, durationMs: Long? = null,
        verification: String? = null, error: String? = null, detail: String? = null,
        mutate: (RequestRecord) -> RequestRecord,
    ): RequestRecord = transitionBlocking(r, next, eventType, decision, backend, durationMs, verification, error, detail, mutate)
        ?: store.get(r.requestId) ?: r

    private fun transitionBlocking(
        r: RequestRecord, next: RequestState, eventType: String,
        decision: PolicyDecision? = null, backend: String? = null, durationMs: Long? = null,
        verification: String? = null, error: String? = null, detail: String? = null,
        mutate: (RequestRecord) -> RequestRecord,
    ): RequestRecord? {
        check(r.state.canTransitionTo(next)) { "Illegal transition ${r.state} -> $next for ${r.requestId}" }
        val updated = mutate(r).copy(state = next, updatedAtMs = clock.nowMs())
        if (!store.update(updated, r.state)) return null // someone else moved it; theirs wins
        log(updated, eventType, decision, backend, durationMs, verification, error ?: updated.errorCode, detail)
        updates.tryEmit(updated)
        return updated
    }

    private fun log(
        r: RequestRecord, type: String, decision: PolicyDecision? = null, backend: String? = null,
        durationMs: Long? = null, verification: String? = null, error: String? = null, detail: String? = null,
    ) = events.emit(Event(
        timestampMs = clock.nowMs(), eventType = type, traceId = r.traceId, requestId = r.requestId,
        agentId = r.agentId, deviceId = r.deviceId, capabilityId = r.capabilityId, backend = backend,
        policyDecision = (decision ?: r.policy)?.outcome?.name, executionStatus = r.state.name,
        durationMs = durationMs, errorCode = error, verificationStatus = verification,
        detail = detail ?: decision?.reason,
    ))
}
