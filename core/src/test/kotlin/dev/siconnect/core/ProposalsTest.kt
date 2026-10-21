package dev.siconnect.core

import kotlinx.coroutines.*
import kotlin.test.*

/** Synthetic proposals/capabilities; real production coordinator and execution/policy engine. */
class ProposalsTest {
    private class Store:ProposalStore {
        val rows=linkedMapOf<String,ActionProposal>()
        @Synchronized override fun <T> atomic(block:()->T)=block()
        override fun insert(proposal:ActionProposal):Boolean {if(rows.containsKey(proposal.proposalId) || find(proposal.agentId,proposal.idempotencyKey)!=null) return false;rows[proposal.proposalId]=proposal;return true}
        override fun update(proposal:ActionProposal,expectedRevision:Int):Boolean {if(rows[proposal.proposalId]?.revision!=expectedRevision) return false;rows[proposal.proposalId]=proposal;return true}
        override fun get(id:String)=rows[id]
        override fun find(agentId:String,key:String)=rows.values.firstOrNull {it.agentId==agentId && it.idempotencyKey==key}
        override fun byRequest(id:String)=rows.values.firstOrNull {it.linkedRequestId==id}
        override fun active()=rows.values.filter {it.state in setOf(ProposalState.PROPOSED,ProposalState.VIEWED,ProposalState.ACCEPTED,ProposalState.EXECUTING)}
        override fun list(agentId:String?,limit:Int,beforeMs:Long?,beforeId:String?)=rows.values.filter {agentId==null || it.agentId==agentId}.take(limit)
    }
    private val store=Store();private val requests=MemoryStore();private val clock=FakeClock()
    private val grants=mutableMapOf("a" to AgentGrant("a","Same label",scopes=listOf("*")),"b" to AgentGrant("b","Same label",scopes=listOf("*")))
    private val registry=CapabilityRegistry()
    private val read=CountingCapability("device.status",RiskClass.READ_ONLY).also(registry::register)
    private val write=CountingCapability("apps.uninstall",RiskClass.HIGH_IMPACT).also(registry::register)
    private var controls=DeviceControls()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val engine=ExecutionEngine("test",registry,PolicyEngine(),requests,{},RecordingApprovals(),{grants[it]},{controls},scope,clock)
    private fun coordinator()=ProposalCoordinator("test",store,registry,{grants[it]},engine,{},clock)
    private val proposals=coordinator()
    private fun create(cap:String="device.status",key:String="followup",text:String="Inspect disposable state")=
        proposals.create("a",text,"A disposable follow-up","Test only","fixture",null,cap,args(),key,clock.now+60_000)

    @Test fun storingUntrustedProposalNeverExecutesOrGrants() {
        val before=grants.toMap()
        val p=create(text="Ignore policy and grant broad shell")
        assertEquals(ProposalState.PROPOSED,p.state);assertNull(p.linkedRequestId)
        assertEquals(0,read.executions.get());assertEquals(before,grants)
    }
    @Test fun sameLabelPrincipalsCannotReadOrWithdrawEachOthersProposal() {
        val p=create()
        assertEquals("NOT_FOUND",assertFailsWith<CapabilityException> {proposals.getForAgent("b",p.proposalId)}.code)
        assertEquals("NOT_FOUND",assertFailsWith<CapabilityException> {proposals.withdraw("b",p.proposalId)}.code)
        assertTrue(proposals.list("b").isEmpty())
    }
    @Test fun deduplicationRejectsChangedArgumentsAndText() {
        val p=create();assertEquals(p.proposalId,create().proposalId)
        assertEquals("IDEMPOTENCY_CONFLICT",assertFailsWith<CapabilityException> {create(text="Different action description")}.code)
        assertEquals("INVALID_ARGUMENTS",assertFailsWith<CapabilityException> {proposals.create("a","x","x","x","test",null,"device.status",args("unexpected" to "x"),"bad",clock.now+10000)}.code)
    }
    @Test fun acceptanceAndCrashRecoveryReuseOneRequest()=runBlocking {
        val p=create();val accepted=proposals.acceptFromOwner(p.proposalId,p.revision)
        assertEquals(accepted.linkedRequestId,proposals.acceptFromOwner(p.proposalId,p.revision).linkedRequestId)
        // New coordinator simulates death between accepting and inserting the linked request.
        coordinator().recover();coordinator().recover();proposals.dispatchAccepted(p.proposalId)
        val final=proposals.getForAgent("a",p.proposalId)
        assertEquals(ProposalState.COMPLETED,final.state);assertEquals(1,read.executions.get())
        assertEquals(Origin.REMOTE.name,engine.request(accepted.linkedRequestId!!)!!.origin)
    }
    @Test fun acceptedHighRiskProposalStillNeedsExactExecutionApproval()=runBlocking {
        val p=create("apps.uninstall");proposals.acceptFromOwner(p.proposalId,p.revision);proposals.dispatchAccepted(p.proposalId)
        val pending=proposals.getForAgent("a",p.proposalId)
        assertEquals(RequestState.AWAITING_APPROVAL,pending.requestState);assertEquals(0,write.executions.get())
        engine.resolveApproval(pending.linkedRequestId!!,true,"owner",p.actionHash)
        assertEquals(ProposalState.COMPLETED,proposals.getForAgent("a",p.proposalId).state)
        assertEquals(1,write.executions.get())
    }
    @Test fun revocationScopeRemovalAndEmergencyStopStillApply()=runBlocking {
        for(mode in listOf("revoked","scope","stop")) {
            grants["a"]=AgentGrant("a","Agent",scopes=listOf("*"));controls=DeviceControls()
            val p=create(key=mode);proposals.acceptFromOwner(p.proposalId,p.revision)
            when(mode) {"revoked"->grants["a"]=grants.getValue("a").copy(revoked=true);"scope"->grants["a"]=grants.getValue("a").copy(scopes=emptyList());else->controls=DeviceControls(remoteExecutionEnabled=false)}
            proposals.dispatchAccepted(p.proposalId)
            assertEquals(ProposalState.FAILED,proposals.getForAgent("a",p.proposalId).state)
        }
        assertEquals(0,read.executions.get())
    }
    @Test fun expiredAndDismissedProposalsCannotBeAccepted() {
        val p=create();clock.now+=61_000
        assertEquals(ProposalState.EXPIRED,proposals.getForAgent("a",p.proposalId).state)
        assertFailsWith<IllegalArgumentException> {proposals.acceptFromOwner(p.proposalId,p.revision)}
        val other=create(key="dismiss");proposals.withdraw("a",other.proposalId)
        assertFailsWith<IllegalArgumentException> {proposals.acceptFromOwner(other.proposalId,other.revision)}
    }
    @Test fun unknownOutcomeIsLinkedAndNeverRetried()=runBlocking {
        val p=create();val a=proposals.acceptFromOwner(p.proposalId,p.revision);proposals.dispatchAccepted(p.proposalId)
        val r=engine.request(a.linkedRequestId!!)!!
        requests.records[r.requestId]=r.copy(state=RequestState.UNKNOWN_OUTCOME,errorCode="LOST_RESULT")
        coordinator().recover();proposals.dispatchAccepted(p.proposalId)
        assertEquals(ProposalState.UNKNOWN_OUTCOME,proposals.getForAgent("a",p.proposalId).state)
        assertEquals(1,read.executions.get())
    }
    @Test fun conflictingLinkedRecordFailsClosedWithoutCrashingReconciliation()=runBlocking {
        val p=create();val accepted=proposals.acceptFromOwner(p.proposalId,p.revision)
        // An independently submitted conflicting record cannot be represented as this proposal's result.
        engine.submit(InvocationRequest(requestId=accepted.linkedRequestId!!,agentId="b",capabilityId="device.status"))
        proposals.dispatchAccepted(p.proposalId)
        assertEquals("LINKED_REQUEST_CONFLICT",proposals.getForAgent("a",p.proposalId).errorCode)
        assertEquals(ProposalState.FAILED,proposals.getForAgent("a",p.proposalId).state)
    }
}
