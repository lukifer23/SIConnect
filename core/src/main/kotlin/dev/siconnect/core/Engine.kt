package dev.siconnect.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.onSubscription
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
    val operationFingerprint: String = "",
    val selectedBackend: BackendKind? = null,
)

interface RequestStore {
    /** Persistent stores wrap record transitions and their EventSink writes in one transaction.
     * In-memory implementations may use the default for compatibility. No suspending work inside. */
    fun <T> atomic(block:()->T):T = block()
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
    private val executionLock = Any()

    fun request(requestId: String): RequestRecord? = store.get(requestId)

    /**
     * Submits a request and runs it to the point where it either finishes or waits for approval.
     * Duplicate submissions (same request id or idempotency key) return the existing record and never re-execute.
     * With [awaitCompletion] false, execution remains owned by the engine scope and this returns
     * after durable admission. Callers can release their own locks before waiting via [await].
     */
    suspend fun submit(req: InvocationRequest, origin: Origin = Origin.REMOTE, awaitCompletion: Boolean = true): RequestRecord {
        val now = clock.nowMs()
        val capability = registry.get(req.capabilityId)
        val version = capability?.descriptor?.version ?: 0
        val capId = capability?.descriptor?.id ?: req.capabilityId
        require(req.requestId.length in 1..200 && req.agentId.length in 1..200) { "Invalid request identity" }
        Schema.checkPayload(req.arguments)
        val fingerprint = Canonical.sha256Hex(Canonical.encode(kotlinx.serialization.json.buildJsonObject {
            put("agent", kotlinx.serialization.json.JsonPrimitive(req.agentId))
            put("device", kotlinx.serialization.json.JsonPrimitive(deviceId))
            put("action", kotlinx.serialization.json.JsonPrimitive(Canonical.actionHash(capId, version, req.arguments)))
            put("origin", kotlinx.serialization.json.JsonPrimitive(origin.name))
            put("parent", kotlinx.serialization.json.JsonPrimitive(req.parentRequestId))
            put("expiry", kotlinx.serialization.json.JsonPrimitive(req.expiresAtMs))
        }))
        fun duplicate(existing: RequestRecord): RequestRecord {
            if (existing.operationFingerprint != fingerprint)
                throw CapabilityException("IDEMPOTENCY_CONFLICT", "Request identity or key belongs to a different operation")
            log(existing, "request.duplicate")
            return existing
        }
        store.get(req.requestId)?.let { return duplicate(it) }
        req.idempotencyKey?.let { key -> store.findByIdempotencyKey(req.agentId, key)?.let { return duplicate(it) } }
        var record = RequestRecord(
            requestId = req.requestId, traceId = req.traceId, agentId = req.agentId, deviceId = deviceId,
            capabilityId = capId, capabilityVersion = version, arguments = req.arguments,
            argsHash = Canonical.actionHash(capId, version, req.arguments), idempotencyKey = req.idempotencyKey,
            operationFingerprint = fingerprint, selectedBackend = capability?.descriptor?.backend, origin = origin.name, state = RequestState.RECEIVED, createdAtMs = now, updatedAtMs = now,
            expiresAtMs = req.expiresAtMs ?: (now + defaultExpiryMs), parentRequestId = req.parentRequestId,
        )
        val inserted=store.atomic {
            store.insert(record).also {if(it) log(record,"request.received")}
        }
        if (!inserted) {
            // Lost a race with a concurrent duplicate; return whatever won.
            return duplicate(store.get(req.requestId)
                ?: req.idempotencyKey?.let { store.findByIdempotencyKey(req.agentId, it) }
                ?: error("insert conflict without existing record"))
        }
        if (capability == null) return fail(record, "UNKNOWN_CAPABILITY", "No capability '${req.capabilityId}'")
        if (now >= record.expiresAtMs) return transition(record, RequestState.EXPIRED, "request.expired") { it }

        val errors = Schema.validate(capability.descriptor.inputSchema, req.arguments)
        if (errors.isNotEmpty()) return fail(record, "INVALID_ARGUMENTS", errors.take(10).joinToString("; "))

        val assessment = runCatching { capability.assess(req.arguments) }.getOrElse {
            return fail(record, "ASSESSMENT_FAILED", it.message ?: "assessment failed")
        }
        val availability = runCatching { capability.availability(req.arguments) }.getOrDefault(Availability.UNKNOWN)
        val grant = agents.grant(req.agentId)?.takeIf { it.agentId == req.agentId }
        val decision = policy.evaluate(
            grant, capability.descriptor, req.arguments, assessment,
            availability, controls(), origin, now,
        )
        record = transition(record, RequestState.VALIDATED, "policy.evaluated", decision = decision) {
            it.copy(policy = decision, assessment = assessment)
        }
        // Cancellation can win while assessment or availability is being checked.
        if (record.state != RequestState.VALIDATED) return record
        return when (decision.outcome) {
            PolicyOutcome.ALLOW -> execute(record, capability, awaitCompletion)
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
                if (waiting.state == RequestState.AWAITING_APPROVAL) approvals.approvalRequested(waiting)
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
        if (record.argsHash != Canonical.actionHash(record.capabilityId, record.capabilityVersion, record.arguments) || record.argsHash != expectedArgsHash) throw IllegalStateException("Approval does not match the pending action")
        val now = clock.nowMs()
        if (now >= (record.approvalExpiresAtMs ?: record.expiresAtMs)) {
            return transition(record, RequestState.EXPIRED, "approval.expired") { it }.also(approvals::approvalClosed)
        }
        if (!approve) {
            return transition(record, RequestState.DENIED, "approval.denied") {
                it.copy(errorCode = "OWNER_DENIED", errorMessage = "Denied by $approver", approvedBy = approver)
            }.also(approvals::approvalClosed)
        }
        val approved = transitionBlocking(record, RequestState.APPROVED, "approval.granted") { it.copy(approvedBy = approver) }
            ?: return store.get(requestId) ?: record
        approvals.approvalClosed(approved)
        val capability = registry.get(approved.capabilityId)
            ?: return fail(approved, "UNKNOWN_CAPABILITY", "Capability disappeared")
        if (capability.descriptor.version != approved.capabilityVersion || (approved.selectedBackend != null && approved.selectedBackend != capability.descriptor.backend))
            return fail(approved, "CAPABILITY_CHANGED", "Capability version changed after approval")
        // Approval satisfies the risk gate only. Current eligibility remains authoritative.
        val grant = agents.grant(approved.agentId)
        if (grant?.agentId != approved.agentId) return fail(approved, "POLICY_DENIED", "Authenticated agent no longer exists")
        val decision = policy.evaluate(grant, capability.descriptor, approved.arguments,
            approved.assessment ?: capability.assess(approved.arguments), runCatching { capability.availability(approved.arguments) }.getOrDefault(Availability.UNKNOWN), controls(),
            Origin.valueOf(approved.origin), now)
        if (decision.outcome == PolicyOutcome.DENY || decision.outcome == PolicyOutcome.UNAVAILABLE)
            return fail(approved, "POLICY_DENIED", decision.reason)
        return execute(approved, capability)
    }

    suspend fun cancel(requestId: String, by: String): RequestRecord? {
        return synchronized(executionLock) {
        val record = store.get(requestId) ?: return@synchronized null
        when (record.state) {
            RequestState.RECEIVED, RequestState.VALIDATED, RequestState.AWAITING_APPROVAL, RequestState.APPROVED ->
                transition(record, RequestState.CANCELLED, "request.cancelled", detail = "by $by") { it }
                    .also(approvals::approvalClosed)
            RequestState.EXECUTING -> { running[requestId]?.cancel(CancellationException("cancelled by $by")); store.get(requestId) }
            else -> record
        }
        }
    }

    /** Waits up to [timeoutMs] for a terminal state or AWAITING_APPROVAL. */
    suspend fun await(requestId: String, timeoutMs: Long, untilTerminal: Boolean = true): RequestRecord? {
        fun done(r: RequestRecord) = r.state.isTerminal || (!untilTerminal && r.state == RequestState.AWAITING_APPROVAL)
        store.get(requestId)?.let { if (done(it)) return it }
        return withTimeoutOrNull(timeoutMs) {
            updates.onSubscription { store.get(requestId)?.let { emit(it) } }.first { it.requestId == requestId && done(it) }
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

    private suspend fun execute(record: RequestRecord, capability: Capability, awaitCompletion: Boolean = true): RequestRecord {
        if (clock.nowMs() >= record.expiresAtMs) return transition(record, RequestState.EXPIRED, "request.expired") { it }
        val job: Job
        val executing: RequestRecord
        synchronized(executionLock) {
        // Eligibility can change after initial evaluation/owner review but before dispatch.
        // This gate blocks queued work; it does not pretend to stop an already running action.
        if(capability.descriptor.version!=record.capabilityVersion ||
            (record.selectedBackend!=null && record.selectedBackend!=capability.descriptor.backend))
            return fail(record,"CAPABILITY_CHANGED","Capability or selected backend changed before dispatch")
        val current=policy.evaluate(agents.grant(record.agentId),capability.descriptor,record.arguments,
            record.assessment ?: capability.assess(record.arguments),runCatching {capability.availability(record.arguments)}.getOrDefault(Availability.UNKNOWN),
            controls(),Origin.valueOf(record.origin),clock.nowMs())
        if(current.outcome==PolicyOutcome.DENY || current.outcome==PolicyOutcome.UNAVAILABLE)
            return fail(record,"POLICY_DENIED",current.reason)
        if(current.outcome==PolicyOutcome.REQUIRE_APPROVAL && record.state!=RequestState.APPROVED)
            return fail(record,"POLICY_CHANGED","Current policy now requires owner review; submit a new request")
        if(clock.nowMs()>=record.expiresAtMs)
            return transition(record,RequestState.EXPIRED,"request.expired") {it}
        executing = transitionBlocking(record, RequestState.EXECUTING, "execution.started", backend = capability.descriptor.backend.name) { it }
            ?: return store.get(record.requestId) ?: record
        val ctx = ExecutionContext(executing.requestId, executing.traceId, executing.agentId, deviceId)
        val started = clock.nowMs()
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val remaining=executing.expiresAtMs-clock.nowMs()
                if(remaining<=0) throw CapabilityException("EXPIRED","Request deadline reached before backend dispatch",noSideEffects=true)
                val budget=minOf(capability.descriptor.timeoutMs,remaining)
                val result = withTimeout(budget) { capability.execute(executing.arguments, ctx) }
                capability.descriptor.outputSchema?.let { schema ->
                    if (Schema.validate(schema, result.output).isNotEmpty())
                        throw CapabilityException("INVALID_OUTPUT", "Backend result violates output schema")
                }
                val state = if (result.verification == VerificationStatus.FAILED) {
                    if (mayHaveSideEffects(executing, capability))
                        RequestState.UNKNOWN_OUTCOME else RequestState.FAILED
                } else RequestState.SUCCEEDED
                transition(executing, state, "execution.finished", backend = result.backend.name,
                    durationMs = clock.nowMs() - started, verification = result.verification.name) {
                    it.copy(result = result, errorCode = if (state != RequestState.SUCCEEDED) result.errorCode ?: "VERIFICATION_FAILED" else null,
                        errorMessage = if (state != RequestState.SUCCEEDED) result.verificationDetail else null)
                }
            } catch (e: TimeoutCancellationException) {
                unknownOrFailed(executing, capability, "TIMEOUT", "Capability timeout or request deadline reached", started)
            } catch (e: CancellationException) {
                unknownOrFailed(executing, capability, "CANCELLED", e.message ?: "cancelled", started, cancelled = true)
            } catch (e: CapabilityException) {
                if (store.get(executing.requestId)?.state == RequestState.EXECUTING)
                    unknownOrFailed(executing, capability, e.code, e.message ?: e.code, started, noSideEffects=e.noSideEffects)
            } catch (e: Exception) {
                if (store.get(executing.requestId)?.state == RequestState.EXECUTING)
                    unknownOrFailed(executing, capability, "EXECUTION_ERROR", "${e::class.simpleName}: ${e.message}", started)
            }
        }
        running[executing.requestId] = job
        job.invokeOnCompletion { running.remove(executing.requestId, job) }
        job.start()
        }
        if (awaitCompletion) job.join()
        return store.get(executing.requestId) ?: executing
    }

    /** Side-effecting work interrupted midway may have partly happened: report UNKNOWN_OUTCOME, never success. */
    private fun unknownOrFailed(
        r: RequestRecord, c: Capability, code: String, msg: String, started: Long, cancelled: Boolean = false, noSideEffects: Boolean = false,
    ): RequestRecord {
        val state = when {
            mayHaveSideEffects(r, c) && !noSideEffects -> RequestState.UNKNOWN_OUTCOME
            cancelled -> RequestState.CANCELLED
            else -> RequestState.FAILED
        }
        return transition(r, state, "execution.interrupted", durationMs = clock.nowMs() - started, error = code) {
            it.copy(errorCode = code, errorMessage = msg)
        }
    }

    private fun mayHaveSideEffects(r: RequestRecord, c: Capability): Boolean =
        maxOf(c.descriptor.risk, r.assessment?.risk ?: c.descriptor.risk) > RiskClass.READ_ONLY ||
            c.descriptor.sideEffects.isNotEmpty() || r.assessment?.sideEffects?.isNotEmpty() == true ||
            r.assessment?.shellCommand != null

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
        val committed=store.atomic {
            if(!store.update(updated,r.state)) false
            else {log(updated,eventType,decision,backend,durationMs,verification,error ?: updated.errorCode,detail);true}
        }
        if(!committed) return null // someone else moved it; theirs wins
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
