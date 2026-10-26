package dev.siconnect.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AuditRegressionTest {
    private class Runs : SkillRunStore {
        private val records = ConcurrentHashMap<String, SkillExecution>()
        override fun insert(run: SkillExecution) = records.putIfAbsent(run.runId, run) == null
        override fun get(runId: String) = records[runId]
        @Synchronized override fun update(run: SkillExecution, expectedRevision: Long): Boolean {
            if (records[run.runId]?.revision != expectedRevision) return false
            records[run.runId] = run
            return true
        }
        override fun active() = records.values.filter { it.state in setOf(SkillState.READY, SkillState.RUNNING, SkillState.AWAITING_APPROVAL) }
    }

    private fun engine(registry: CapabilityRegistry, store: RequestStore, scope: CoroutineScope,
                       clock: Clock = Clock { System.currentTimeMillis() }, approvals: ApprovalGate = RecordingApprovals()) =
        ExecutionEngine("device", registry, PolicyEngine(), store, {}, approvals,
            { AgentGrant(it, it, scopes = listOf("*"), autoApproveUpTo = RiskClass.LOW_IMPACT) },
            { DeviceControls() }, scope, clock)

    @Test fun maximumLengthRunIdentitySupportsDistinctStableChildRequests() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = CapabilityRegistry()
            val cap = CountingCapability("device.read", RiskClass.READ_ONLY).also(registry::register)
            val store = MemoryStore()
            val runner = DurableSkillRunner(engine(registry, store, scope), Runs())
            val definition = SkillDefinition("test.long", 1, "test", steps = listOf(
                SkillStep("first", "device.read"), SkillStep("second", "device.read")))
            val ctx = ExecutionContext("r".repeat(200), "trace", "agent", "device")
            val result = runner.start(definition, JsonObject(emptyMap()), ctx, Origin.REMOTE)
            assertEquals(SkillState.SUCCEEDED, result.state)
            assertEquals(2, result.steps.mapNotNull { it.requestId }.distinct().size)
            result.steps.forEach { step ->
                assertTrue(step.requestId!!.length <= 200)
                assertEquals(ctx.requestId, store.get(step.requestId)!!.parentRequestId)
            }
            assertEquals(SkillState.SUCCEEDED, runner.start(definition, JsonObject(emptyMap()), ctx, Origin.REMOTE).state)
            assertEquals(2, cap.executions.get())
        } finally { scope.cancel() }
    }

    @Test fun timeoutWithAnExecutingChildRetainsUncertaintyAndStopsFurtherSteps() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val registry = CapabilityRegistry()
            registry.register(object : Capability {
                override val descriptor = CapabilityDescriptor("test.write", title = "Write", description = "test",
                    inputSchema = objectSchema(), backend = BackendKind.NATIVE, risk = RiskClass.LOW_IMPACT)
                override fun availability() = Availability.IMPLEMENTED
                override suspend fun execute(args: JsonObject, ctx: ExecutionContext): CapabilityResult {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    return CapabilityResult(JsonNull, BackendKind.NATIVE)
                }
            })
            val after = CountingCapability("device.after", RiskClass.READ_ONLY).also(registry::register)
            val clock = FakeClock()
            val store = MemoryStore()
            val engine = engine(registry, store, scope, clock)
            val child = async { engine.submit(InvocationRequest(requestId = "run:first", agentId = "agent", capabilityId = "test.write")) }
            withTimeout(3000) { entered.await() }
            val runs = Runs()
            val definition = SkillDefinition("test.timeout", 1, "test", steps = listOf(
                SkillStep("first", "test.write"), SkillStep("after", "device.after")))
            runs.insert(SkillExecution("run", definition, JsonObject(emptyMap()), "agent", "device", "trace", Origin.REMOTE.name,
                clock.now - 1000, clock.now, state = SkillState.AWAITING_APPROVAL, pendingRequestId = "run:first"))
            val runner = DurableSkillRunner(engine, runs, clock)
            assertEquals(SkillState.UNKNOWN_OUTCOME, runner.resume("run").state)
            release.complete(Unit)
            withTimeout(3000) { child.await() }
            assertEquals(SkillState.UNKNOWN_OUTCOME, runner.resume("run").state)
            assertEquals(0, after.executions.get())
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun cancellingALongRunClosesItsOriginalApprovalAndRecordsTheChild() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = CapabilityRegistry()
            val cap = CountingCapability("test.write", RiskClass.HIGH_IMPACT).also(registry::register)
            val store = MemoryStore()
            val runner = DurableSkillRunner(engine(registry, store, scope), Runs())
            val runId = "r".repeat(200)
            val waiting = runner.start(SkillDefinition("test.long_cancel", 1, "test", steps = listOf(SkillStep("write", "test.write"))),
                JsonObject(emptyMap()), ExecutionContext(runId, "trace", "agent", "device"), Origin.REMOTE)
            assertEquals(SkillState.AWAITING_APPROVAL, waiting.state)
            val childId = requireNotNull(waiting.pendingRequestId)
            val cancelled = runner.cancel(runId)
            assertEquals(SkillState.CANCELLED, cancelled.state)
            assertEquals(childId, cancelled.steps.single().requestId)
            assertEquals(RequestState.CANCELLED, store.get(childId)!!.state)
            assertEquals(0, cap.executions.get())
        } finally { scope.cancel() }
    }

    @Test fun recoveryReplacesAnOversizedPendingIdentityBeforeWaitingForApproval() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = CapabilityRegistry()
            val cap = CountingCapability("test.write", RiskClass.HIGH_IMPACT).also(registry::register)
            val store = MemoryStore()
            val runs = Runs()
            val engine = engine(registry, store, scope)
            val runner = DurableSkillRunner(engine, runs)
            val runId = "r".repeat(200)
            val definition = SkillDefinition("test.long_recovery", 1, "test", steps = listOf(SkillStep("write", "test.write")))
            val now = System.currentTimeMillis()
            // Older code saved this cursor before engine.submit rejected the oversized child ID.
            runs.insert(SkillExecution(runId, definition, JsonObject(emptyMap()), "agent", "device", "trace", Origin.REMOTE.name,
                now, now + 60_000, pendingRequestId = "$runId:write"))
            val waiting = runner.resume(runId)
            val pending = requireNotNull(store.get(requireNotNull(waiting.pendingRequestId)))
            assertEquals(RequestState.AWAITING_APPROVAL, pending.state)
            engine.resolveApproval(pending.requestId, true, "owner", pending.argsHash)
            assertEquals(SkillState.SUCCEEDED, runner.resume(runId).state)
            assertEquals(1, cap.executions.get())
        } finally { scope.cancel() }
    }

    @Test fun failedVerificationIncludesDynamicRiskAndAssessedSideEffects() = runBlocking {
        for (assessedRisk in listOf(RiskClass.READ_ONLY, RiskClass.LOW_IMPACT)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val registry = CapabilityRegistry()
                registry.register(object : Capability {
                    override val descriptor = CapabilityDescriptor("test.dynamic", title = "Dynamic", description = "test",
                        inputSchema = objectSchema(), backend = BackendKind.NATIVE, risk = RiskClass.READ_ONLY)
                    override fun availability() = Availability.IMPLEMENTED
                    override fun assess(args: JsonObject) = RiskAssessment(assessedRisk, "test",
                        sideEffects = if (assessedRisk == RiskClass.READ_ONLY) listOf("May change external state") else emptyList())
                    override suspend fun execute(args: JsonObject, ctx: ExecutionContext) =
                        CapabilityResult(JsonNull, BackendKind.NATIVE, verification = VerificationStatus.FAILED)
                })
                assertEquals(RequestState.UNKNOWN_OUTCOME,
                    engine(registry, MemoryStore(), scope).submit(InvocationRequest(agentId = "agent", capabilityId = "test.dynamic")).state)
            } finally { scope.cancel() }
        }
    }

    @Test fun cancellationDuringAssessmentReturnsTheCancelledRecordWithoutOwnerNotification() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        try {
            val registry = CapabilityRegistry()
            registry.register(object : Capability {
                override val descriptor = CapabilityDescriptor("test.review", title = "Review", description = "test",
                    inputSchema = objectSchema(), backend = BackendKind.NATIVE, risk = RiskClass.HIGH_IMPACT)
                override fun availability() = Availability.IMPLEMENTED
                override fun assess(args: JsonObject): RiskAssessment {
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                    return super.assess(args)
                }
                override suspend fun execute(args: JsonObject, ctx: ExecutionContext): CapabilityResult = error("Must not execute")
            })
            val approvals = RecordingApprovals()
            val engine = engine(registry, MemoryStore(), scope, approvals = approvals)
            val submitted = async(Dispatchers.Default) { engine.submit(InvocationRequest(requestId = "cancelled", agentId = "agent", capabilityId = "test.review")) }
            withTimeout(3000) { entered.await() }
            assertEquals(RequestState.CANCELLED, engine.cancel("cancelled", "owner")!!.state)
            release.countDown()
            assertEquals(RequestState.CANCELLED, withTimeout(3000) { submitted.await() }.state)
            assertTrue(approvals.requested.isEmpty())
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun deadlineReachedDuringDispatchRecheckPreventsExecution() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = CapabilityRegistry()
            val clock = FakeClock()
            val deadline = clock.now + 100
            var checks = 0
            registry.register(object : Capability {
                override val descriptor = CapabilityDescriptor("test.deadline", title = "Deadline", description = "test",
                    inputSchema = objectSchema(), backend = BackendKind.NATIVE, risk = RiskClass.READ_ONLY)
                override fun availability(): Availability {
                    if (++checks == 2) clock.now = deadline
                    return Availability.IMPLEMENTED
                }
                override suspend fun execute(args: JsonObject, ctx: ExecutionContext): CapabilityResult = error("Deadline passed before dispatch")
            })
            val record = engine(registry, MemoryStore(), scope, clock).submit(
                InvocationRequest(agentId = "agent", capabilityId = "test.deadline", expiresAtMs = deadline))
            assertEquals(RequestState.EXPIRED, record.state)
        } finally { scope.cancel() }
    }
}
