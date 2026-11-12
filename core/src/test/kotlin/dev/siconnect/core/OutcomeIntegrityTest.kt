package dev.siconnect.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

/** Outcome honesty and liveness: validation is not UNKNOWN_OUTCOME, and no request is left open. */
class OutcomeIntegrityTest {
    private class Boundary(id: String, private val declares: Boolean, private val failAfterMutation: Boolean) : Capability {
        override val descriptor = CapabilityDescriptor(id, title = id, description = "test", inputSchema = objectSchema(),
            backend = BackendKind.NATIVE, risk = RiskClass.LOW_IMPACT, sideEffects = listOf("writes"))
        override val declaresMutationBoundary get() = declares
        override fun availability() = Availability.IMPLEMENTED
        override suspend fun execute(args: JsonObject, ctx: ExecutionContext): CapabilityResult {
            if (failAfterMutation) { ctx.beginMutation(); throw CapabilityException("WRITE_FAILED", "provider failed midway") }
            throw IllegalArgumentException("bad input rejected before any write")
        }
    }

    private fun engine(registry: CapabilityRegistry, store: RequestStore, scope: CoroutineScope, clock: Clock = Clock { System.currentTimeMillis() }) =
        ExecutionEngine("device", registry, PolicyEngine(), store, {}, RecordingApprovals(),
            { AgentGrant(it, it, scopes = listOf("*"), autoApproveUpTo = RiskClass.LOW_IMPACT) }, { DeviceControls() }, scope, clock)

    @Test fun validationBeforeDeclaredMutationBoundaryIsNotUnknownOutcome() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = CapabilityRegistry()
            registry.register(Boundary("test.validated", declares = true, failAfterMutation = false))
            registry.register(Boundary("test.midway", declares = true, failAfterMutation = true))
            registry.register(Boundary("test.legacy", declares = false, failAfterMutation = false))
            val engine = engine(registry, MemoryStore(), scope)
            val rejected = engine.submit(InvocationRequest(requestId = "v", agentId = "a", capabilityId = "test.validated"))
            assertEquals(RequestState.FAILED, rejected.state)
            assertEquals("EXECUTION_ERROR", rejected.errorCode)
            assertEquals(RequestState.UNKNOWN_OUTCOME,
                engine.submit(InvocationRequest(requestId = "m", agentId = "a", capabilityId = "test.midway")).state,
                "A failure after the declared write boundary must stay uncertain")
            assertEquals(RequestState.UNKNOWN_OUTCOME,
                engine.submit(InvocationRequest(requestId = "l", agentId = "a", capabilityId = "test.legacy")).state,
                "Capabilities without a declared boundary keep the conservative default")
        } finally { scope.cancel() }
    }

    @Test fun awaitObservesTerminalStateEvenWithoutAnInMemoryUpdate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = CapabilityRegistry()
            val store = MemoryStore()
            val engine = engine(registry, store, scope)
            val now = System.currentTimeMillis()
            val record = RequestRecord(requestId = "r", traceId = "t", agentId = "a", deviceId = "device", capabilityId = "x",
                capabilityVersion = 1, arguments = JsonObject(emptyMap()), argsHash = "h", origin = Origin.REMOTE.name, state = RequestState.EXECUTING,
                createdAtMs = now, updatedAtMs = now, expiresAtMs = now + 60_000)
            store.insert(record)
            val waiter = async { engine.await("r", 10_000) }
            delay(100)
            // Simulates a dropped flow emission: the durable record changes with no update published.
            store.update(record.copy(state = RequestState.SUCCEEDED), RequestState.EXECUTING)
            val observed = withTimeout(5_000) { waiter.await() }
            assertEquals(RequestState.SUCCEEDED, observed?.state)
        } finally { scope.cancel() }
    }

    @Test fun sweepClosesOrphanedRequestsWithoutTouchingLiveOnes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val clock = FakeClock()
            val store = MemoryStore()
            val engine = engine(CapabilityRegistry(), store, scope, clock)
            fun record(id: String, state: RequestState, updated: Long) = RequestRecord(requestId = id, traceId = id, agentId = "a",
                deviceId = "device", capabilityId = "x", capabilityVersion = 1, arguments = JsonObject(emptyMap()), argsHash = "h", origin = Origin.REMOTE.name,
                state = state, createdAtMs = updated, updatedAtMs = updated, expiresAtMs = clock.now + 3_600_000)
            val old = clock.now - STALLED_REQUEST_MS - 1
            store.insert(record("stuck-received", RequestState.RECEIVED, old))
            store.insert(record("stuck-approved", RequestState.APPROVED, old))
            store.insert(record("orphan-executing", RequestState.EXECUTING, old))
            store.insert(record("fresh-validated", RequestState.VALIDATED, clock.now))
            engine.expireStale()
            assertEquals(RequestState.FAILED, store.get("stuck-received")!!.state)
            assertEquals("ADMISSION_STALLED", store.get("stuck-approved")!!.errorCode)
            assertEquals(RequestState.UNKNOWN_OUTCOME, store.get("orphan-executing")!!.state)
            assertEquals(RequestState.VALIDATED, store.get("fresh-validated")!!.state)
        } finally { scope.cancel() }
    }
}
