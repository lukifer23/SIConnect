package dev.siconnect.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryDispatchTest {
    @Test fun startupPreparationNeverExecutesWorkAndPreservesTheOriginalChild() = runTest {
        val registry=CapabilityRegistry();val cap=CountingCapability("device.read",RiskClass.READ_ONLY).also(registry::register)
        val requests=MemoryStore()
        val engine=ExecutionEngine("d",registry,PolicyEngine(),requests,{},RecordingApprovals(),
            {AgentGrant(it,it,scopes=listOf("*"))},{DeviceControls()},backgroundScope)
        val definition=SkillDefinition("test.recover",1,"test",steps=listOf(SkillStep("read","device.read")))
        var saved=SkillExecution("run",definition,JsonObject(emptyMap()),"a","d","trace",Origin.REMOTE.name,
            System.currentTimeMillis(),System.currentTimeMillis()+60_000,state=SkillState.RUNNING,pendingRequestId="run:read")
        val store=object:SkillRunStore {
            override fun insert(run:SkillExecution)=false
            override fun get(runId:String)=saved
            override fun update(run:SkillExecution,expectedRevision:Long):Boolean {
                if(saved.revision!=expectedRevision) return false
                saved=run;return true
            }
            override fun active()=if(saved.state in setOf(SkillState.READY,SkillState.RUNNING,SkillState.AWAITING_APPROVAL)) listOf(saved) else emptyList()
        }
        val runner=DurableSkillRunner(engine,store)
        runner.prepareRecovery()
        assertEquals(SkillState.READY,saved.state);assertEquals("run:read",saved.pendingRequestId)
        assertEquals(0,cap.executions.get());assertNull(requests.get("run:read"))
        assertEquals(SkillState.SUCCEEDED,runner.resume("run").state);assertEquals(1,cap.executions.get())
    }

    @Test fun detachedAdmissionReturnsWhileExecutionIsOwnedAndCanBeCancelled() = runTest {
        val entered=CompletableDeferred<Unit>();var attempts=0
        val registry=CapabilityRegistry()
        registry.register(object:Capability {
            override val descriptor=CapabilityDescriptor("test.write",title="Write",description="Host fixture",inputSchema=objectSchema(),backend=BackendKind.NATIVE,risk=RiskClass.LOW_IMPACT)
            override fun availability()=Availability.IMPLEMENTED
            override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {attempts++;entered.complete(Unit);awaitCancellation()}
        })
        val requests=MemoryStore()
        val engine=ExecutionEngine("d",registry,PolicyEngine(),requests,{},RecordingApprovals(),
            {AgentGrant(it,it,scopes=listOf("*"),autoApproveUpTo=RiskClass.LOW_IMPACT)},{DeviceControls()},backgroundScope)
        val request=InvocationRequest(requestId="write",agentId="a",capabilityId="test.write")
        val admitted=engine.submit(request,awaitCompletion=false)
        assertEquals(RequestState.EXECUTING,admitted.state)
        runCurrent();entered.await();engine.cancel(request.requestId,"owner");runCurrent()
        assertEquals(RequestState.UNKNOWN_OUTCOME,engine.request(request.requestId)!!.state)
        assertEquals(RequestState.UNKNOWN_OUTCOME,engine.submit(request,awaitCompletion=false).state)
        assertEquals(1,attempts)
    }
}
