package dev.siconnect.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SkillCancellationTest {
    private class Runs:SkillRunStore {
        private val records=ConcurrentHashMap<String,SkillExecution>()
        override fun insert(run:SkillExecution)=records.putIfAbsent(run.runId,run)==null
        override fun get(runId:String)=records[runId]
        @Synchronized override fun update(run:SkillExecution,expectedRevision:Long):Boolean {
            if(records[run.runId]?.revision!=expectedRevision) return false
            records[run.runId]=run;return true
        }
        override fun active()=records.values.filter {it.state in setOf(SkillState.READY,SkillState.RUNNING,SkillState.AWAITING_APPROVAL)}
        override fun cancellations()=records.values.filter {it.state==SkillState.UNKNOWN_OUTCOME && it.error?.startsWith("CANCELLATION_")==true}
    }
    private fun engine(registry:CapabilityRegistry,store:MemoryStore,scope:CoroutineScope)=ExecutionEngine("d",registry,PolicyEngine(),store,{},RecordingApprovals(),
        {AgentGrant(it,it,scopes=listOf("*"),autoApproveUpTo=RiskClass.LOW_IMPACT)},{DeviceControls()},scope)
    private suspend fun interrupted(risk:RiskClass) = coroutineScope {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val entered=CompletableDeferred<Unit>();val externalEffects=AtomicInteger()
        val registry=CapabilityRegistry();val requests=MemoryStore();val runs=Runs()
        registry.register(object:Capability {
            override val descriptor=CapabilityDescriptor("test.first",title="First",description="Test-only controlled in-flight operation",inputSchema=objectSchema(),backend=BackendKind.NATIVE,risk=risk)
            override fun availability()=Availability.IMPLEMENTED
            override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {
                if(risk>RiskClass.READ_ONLY) externalEffects.incrementAndGet()
                entered.complete(Unit);awaitCancellation()
            }
        })
        val after=CountingCapability("device.after",RiskClass.READ_ONLY).also(registry::register)
        val engine=engine(registry,requests,scope);val runner=DurableSkillRunner(engine,runs)
        val definition=SkillDefinition("test.cancel",1,"test",steps=listOf(SkillStep("first","test.first"),SkillStep("after","device.after")))
        try {
            val job=async(Dispatchers.Default) {runner.start(definition,JsonObject(emptyMap()),ExecutionContext("run","trace","agent","d"),Origin.REMOTE)}
            withTimeout(3000) {entered.await()}
            val result=runner.cancel("run")
            val expected=if(risk>RiskClass.READ_ONLY) SkillState.UNKNOWN_OUTCOME else SkillState.CANCELLED
            assertEquals(expected,result.state)
            assertEquals(expected,withTimeout(5000) {job.await()}.state)
            assertEquals(expected,runner.resume("run").state)
            assertEquals(0,after.executions.get())
            assertEquals(if(risk>RiskClass.READ_ONLY) 1 else 0,externalEffects.get())
            assertEquals(if(risk>RiskClass.READ_ONLY) RequestState.UNKNOWN_OUTCOME else RequestState.CANCELLED,requests.get("run:first")!!.state)
        } finally {scope.cancel()}
    }
    @Test fun mutatingChildCancellationKeepsUnknownOutcomeAndStopsFollowingSteps()=runBlocking {interrupted(RiskClass.LOW_IMPACT)}
    @Test fun readOnlyChildCanFinishAsKnownCancelledWithoutRunningFollowingSteps()=runBlocking {interrupted(RiskClass.READ_ONLY)}
    @Test fun pendingApprovalCancelsBeforeExecution()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val registry=CapabilityRegistry();val cap=CountingCapability("apps.remove",RiskClass.HIGH_IMPACT).also(registry::register)
        val requests=MemoryStore();val runs=Runs();val engine=engine(registry,requests,scope);val runner=DurableSkillRunner(engine,runs)
        try {
            val waiting=runner.start(SkillDefinition("test.wait",1,"test",steps=listOf(SkillStep("first","apps.remove"))),JsonObject(emptyMap()),ExecutionContext("run","trace","agent","d"),Origin.REMOTE)
            assertEquals(SkillState.AWAITING_APPROVAL,waiting.state)
            assertEquals(SkillState.CANCELLED,runner.cancel("run").state)
            assertEquals(RequestState.CANCELLED,requests.get("run:first")!!.state)
            assertEquals(0,cap.executions.get())
        } finally {scope.cancel()}
    }
    @Test fun interruptedCancellationRecoveryClosesTheOriginalPendingApproval()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val registry=CapabilityRegistry();val cap=CountingCapability("apps.remove",RiskClass.HIGH_IMPACT).also(registry::register)
        val requests=MemoryStore();val runs=Runs();val engine=engine(registry,requests,scope);val runner=DurableSkillRunner(engine,runs)
        try {
            val waiting=runner.start(SkillDefinition("test.recovercancel",1,"test",steps=listOf(SkillStep("first","apps.remove"))),JsonObject(emptyMap()),ExecutionContext("run","trace","agent","d"),Origin.REMOTE)
            assertTrue(runs.update(waiting.copy(state=SkillState.UNKNOWN_OUTCOME,error="CANCELLATION_REQUESTED",revision=waiting.revision+1),waiting.revision))
            // Persisted interruption fixture: process ended before engine.cancel reached the gate.
            engine.recover();DurableSkillRunner(engine,runs).recover()
            assertEquals(SkillState.CANCELLED,runs.get("run")!!.state)
            assertEquals(RequestState.CANCELLED,requests.get("run:first")!!.state)
            assertEquals(0,cap.executions.get())
        } finally {scope.cancel()}
    }
    @Test fun missingChildDuringSubmitHandoffNeverClaimsCleanCancellationOrReplays()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val registry=CapabilityRegistry();val cap=CountingCapability("apps.remove",RiskClass.LOW_IMPACT).also(registry::register)
        val requests=MemoryStore();val runs=Runs();val runner=DurableSkillRunner(engine(registry,requests,scope),runs)
        val definition=SkillDefinition("test.gap",1,"test",steps=listOf(SkillStep("first","apps.remove")))
        try {
            val now=System.currentTimeMillis()
            runs.insert(SkillExecution("run",definition,JsonObject(emptyMap()),"agent","d","trace",Origin.REMOTE.name,now,now+60000,
                state=SkillState.RUNNING,pendingRequestId="run:first"))
            val cancelled=runner.cancel("run")
            assertEquals(SkillState.UNKNOWN_OUTCOME,cancelled.state)
            assertEquals("CANCELLATION_HANDOFF_UNCERTAIN",cancelled.error)
            assertEquals(SkillState.UNKNOWN_OUTCOME,runner.resume("run").state)
            assertEquals(0,cap.executions.get())
        } finally {scope.cancel()}
    }
}
