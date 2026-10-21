package dev.siconnect.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Serializable
enum class ProposalState { PROPOSED, VIEWED, ACCEPTED, EXECUTING, COMPLETED, FAILED, UNKNOWN_OUTCOME, DISMISSED, EXPIRED }

/** Agent text is display data, never authority. Arguments and target version are immutable. */
@Serializable
data class ActionProposal(
    val proposalId:String = UUID.randomUUID().toString(), val agentId:String, val deviceId:String,
    val createdAtMs:Long, val expiresAtMs:Long, val title:String, val summary:String, val reason:String,
    val sourceType:String, val sourceReference:String?, val capabilityId:String, val capabilityVersion:Int,
    val arguments:JsonObject, val assessment:RiskAssessment, val actionHash:String, val idempotencyKey:String,
    val state:ProposalState = ProposalState.PROPOSED, val revision:Int = 0, val resolvedAtMs:Long? = null,
    val linkedRequestId:String? = null, val executionExpiresAtMs:Long? = null,
    val requestState:RequestState? = null, val errorCode:String? = null,
    val verification:VerificationStatus? = null,
    val dispatchAttempts:Int = 0, val lastDispatchAtMs:Long? = null,
)

/** List projection deliberately excludes arguments, reason, source content and shell assessment. */
@Serializable
data class ProposalSummary(val proposalId:String,val agentId:String,val createdAtMs:Long,val expiresAtMs:Long,
    val title:String,val summary:String,val capabilityId:String,val risk:RiskClass,val state:ProposalState,
    val linkedRequestId:String?=null,val requestState:RequestState?=null,val errorCode:String?=null,val verification:VerificationStatus?=null)

fun ActionProposal.summary()=ProposalSummary(proposalId,agentId,createdAtMs,expiresAtMs,title,summary.take(240),capabilityId,assessment.risk,state,linkedRequestId,requestState,errorCode,verification)

data class ProposalQuery(val agentId:String?=null,val limit:Int=20,val cursorMs:Long?=null,val cursorId:String?=null,
    val oldestFirst:Boolean=false,val states:Set<ProposalState>?=null)

data class ProposalReadiness(val outcome:PolicyOutcome,val reason:String,val rule:String,val availability:Availability)
enum class ProposalDispatch { RECORDED, RETRY_PENDING, FINISHED }

interface ProposalStore {
    fun <T> atomic(block:()->T):T
    fun insert(proposal:ActionProposal):Boolean
    fun get(id:String):ActionProposal?
    fun find(agentId:String,key:String):ActionProposal?
    fun update(proposal:ActionProposal,expectedRevision:Int):Boolean
    fun summaries(query:ProposalQuery,nowMs:Long):List<ProposalSummary>
    fun active(afterId:String?=null,limit:Int=100):List<ActionProposal>
    fun expiring(nowMs:Long,limit:Int=100):List<ActionProposal>
    fun activeCount(agentId:String,nowMs:Long):Int
    fun pendingCount(nowMs:Long):Int
    fun nextExpiration(nowMs:Long):Long?
    fun reconciliationCandidates(limit:Int=100):List<ActionProposal>
    fun byRequest(id:String):ActionProposal?
}

/** Device request SQLite remains execution authority; the inbox stores only a link to that request. */
class ProposalCoordinator(
    private val deviceId:String, private val store:ProposalStore, private val registry:CapabilityRegistry,
    private val agents:AgentDirectory, private val engine:ExecutionEngine, private val events:EventSink,
    private val clock:Clock = Clock {System.currentTimeMillis()},
    private val controls:()->DeviceControls = {DeviceControls()},
) {
    private val changes=MutableSharedFlow<Unit>(extraBufferCapacity=32)
    val updates=changes.asSharedFlow()
    private fun fail(code:String,message:String):Nothing=throw CapabilityException(code,message,noSideEffects=true)
    private fun audit(p:ActionProposal) {
        events.emit(Event(clock.nowMs(),eventType="proposal.${p.state.name.lowercase()}",agentId=p.agentId,
            deviceId=deviceId,requestId=p.linkedRequestId,capabilityId=p.capabilityId))
    }
    private fun replace(p:ActionProposal,next:ActionProposal):ActionProposal {
        check(store.update(next.copy(revision=p.revision+1),p.revision)) {"Proposal changed; reopen it before resolving"}
        val saved=next.copy(revision=p.revision+1);audit(saved);changes.tryEmit(Unit);return saved
    }
    fun create(agentId:String,title:String,summary:String,reason:String,sourceType:String,sourceReference:String?,
               capabilityId:String,arguments:JsonObject,idempotencyKey:String,expiresAtMs:Long):ActionProposal = store.atomic {
        val now=clock.nowMs()
        val grant=agents.grant(agentId) ?: fail("AUTH_DENIED","Agent no longer exists")
        if(grant.revoked || grant.expiresAtMs?.let {it<=now}==true) fail("AUTH_DENIED","Agent is revoked or expired")
        if(title.isBlank() || title.length>120 || summary.length>1000 || reason.length>1000 || sourceType.length>40 || (sourceReference?.length ?: 0)>500 || idempotencyKey.isBlank() || idempotencyKey.length>100)
            fail("INVALID_PROPOSAL","Proposal text exceeds its bounded budget")
        val cap=registry.get(capabilityId) ?: fail("UNKNOWN_CAPABILITY","Choose a registered capability")
        if(cap.descriptor.id.startsWith("proposals.")) fail("INVALID_PROPOSAL","A proposal cannot resolve or create another proposal")
        if(arguments.toString().toByteArray().size>16_384 || Schema.validate(cap.descriptor.inputSchema,arguments).isNotEmpty())
            fail("INVALID_ARGUMENTS","Proposed arguments must match the target schema and fit within 16 KiB")
        val hash=Canonical.actionHash(cap.descriptor.id,cap.descriptor.version,arguments)
        val existing=store.find(agentId,idempotencyKey)
        if(existing!=null) {
            if(existing.actionHash!=hash || existing.title!=title || existing.summary!=summary || existing.reason!=reason || existing.sourceType!=sourceType || existing.sourceReference!=sourceReference)
                fail("IDEMPOTENCY_CONFLICT","This key already identifies a different proposal")
            return@atomic reconcile(existing)
        }
        if(expiresAtMs !in (now+1000)..(now+7*86_400_000L)) fail("INVALID_PROPOSAL","Expiration must be between one second and seven days")
        expireDue()
        if(store.activeCount(agentId,now)>=100) fail("RESOURCE_BUSY","Resolve existing proposals before creating more")
        val assessed=cap.assess(arguments)
        val p=ActionProposal(agentId=agentId,deviceId=deviceId,createdAtMs=now,expiresAtMs=expiresAtMs,title=title,
            summary=summary,reason=reason,sourceType=sourceType,sourceReference=sourceReference,
            capabilityId=cap.descriptor.id,capabilityVersion=cap.descriptor.version,arguments=arguments,
            assessment=assessed.copy(risk=maxOf(cap.descriptor.risk,assessed.risk)),actionHash=hash,idempotencyKey=idempotencyKey)
        check(store.insert(p));audit(p);changes.tryEmit(Unit);p
    }
    fun getForAgent(agentId:String,id:String):ActionProposal {
        return store.atomic {
            val p=store.get(id)?.takeIf {it.agentId==agentId} ?: fail("NOT_FOUND","Proposal not found for this identity")
            reconcile(p)
        }
    }
    fun list(agentId:String?=null,limit:Int=50,beforeMs:Long?=null,beforeId:String?=null,
             oldestFirst:Boolean=false,states:Set<ProposalState>?=null):List<ProposalSummary> = store.atomic {
        expireDue();store.summaries(ProposalQuery(agentId,limit.coerceIn(1,50),beforeMs,beforeId,oldestFirst,states),clock.nowMs())
    }
    fun pendingCount()=store.pendingCount(clock.nowMs())
    fun nextExpiration()=store.nextExpiration(clock.nowMs())
    fun readinessFromOwner(id:String):ProposalReadiness = store.atomic {readiness(requireNotNull(store.get(id)))}
    private fun readiness(p:ActionProposal):ProposalReadiness {
        fun blocked(reason:String,rule:String,availability:Availability=Availability.IMPLEMENTED)=ProposalReadiness(PolicyOutcome.DENY,reason,rule,availability)
        if(p.state !in setOf(ProposalState.PROPOSED,ProposalState.VIEWED) || p.expiresAtMs<=clock.nowMs()) return blocked("This proposal is resolved or expired","proposal_state")
        val cap=registry.get(p.capabilityId) ?: return blocked("The proposed capability is no longer installed","capability_missing",Availability.UNSUPPORTED)
        if(cap.descriptor.version!=p.capabilityVersion) return blocked("The capability version changed; request a new proposal","capability_changed")
        val assessment=cap.assess(p.arguments)
        val available=cap.availability(p.arguments)
        val decision=PolicyEngine().evaluate(agents.grant(p.agentId),cap.descriptor,p.arguments,assessment,available,controls(),Origin.REMOTE,clock.nowMs())
        return ProposalReadiness(decision.outcome,if(available.isExecutable) decision.reason else cap.availabilityDetail() ?: decision.reason,decision.rule,available)
    }
    fun reviewFromOwner(id:String):ActionProposal = store.atomic {
        val p=reconcile(requireNotNull(store.get(id)))
        if(p.state==ProposalState.PROPOSED) replace(p,p.copy(state=ProposalState.VIEWED)) else p
    }
    fun dismissFromOwner(id:String):ActionProposal = dismiss(id,null)
    fun withdraw(agentId:String,id:String):ActionProposal = dismiss(id,agentId)
    private fun dismiss(id:String,agentId:String?):ActionProposal = store.atomic {
        val p=store.get(id)?.takeIf {agentId==null || it.agentId==agentId} ?: fail("NOT_FOUND","Proposal not found")
        val current=reconcile(p)
        if(current.state in setOf(ProposalState.PROPOSED,ProposalState.VIEWED)) replace(current,current.copy(state=ProposalState.DISMISSED,resolvedAtMs=clock.nowMs()))
        else if(current.state in setOf(ProposalState.DISMISSED,ProposalState.EXPIRED)) current
        else fail("CONFLICT","An accepted action cannot be withdrawn; inspect its linked request")
    }
    /** Only the trusted local UI calls this after fresh owner authentication. No grant is modified. */
    fun acceptFromOwner(id:String,expectedRevision:Int):ActionProposal = store.atomic {
        val p=reconcile(requireNotNull(store.get(id)))
        if(p.linkedRequestId!=null) return@atomic p // duplicate owner tap cannot allocate a new execution
        require(p.revision==expectedRevision && p.state in setOf(ProposalState.PROPOSED,ProposalState.VIEWED)) {"Proposal changed or expired; review it again"}
        val current=readiness(p)
        require(current.outcome in setOf(PolicyOutcome.ALLOW,PolicyOutcome.REQUIRE_APPROVAL)) {current.reason}
        val cap=requireNotNull(registry.get(p.capabilityId))
        require(cap.descriptor.version==p.capabilityVersion && Canonical.actionHash(p.capabilityId,p.capabilityVersion,p.arguments)==p.actionHash) {"Target capability changed; request a new proposal"}
        replace(p,p.copy(state=ProposalState.ACCEPTED,linkedRequestId=UUID.randomUUID().toString(),
            executionExpiresAtMs=minOf(p.expiresAtMs,clock.nowMs()+15*60_000)))
    }
    /** Crash recovery uses the same immutable request identity; uncertain mutations are never resubmitted. */
    suspend fun dispatchAccepted(id:String):ProposalDispatch {
        val p=store.get(id) ?: return ProposalDispatch.FINISHED
        val requestId=p.linkedRequestId ?: return ProposalDispatch.FINISHED
        engine.request(requestId)?.let {onRequest(it);return ProposalDispatch.RECORDED}
        if(p.state!=ProposalState.ACCEPTED) return ProposalDispatch.FINISHED
        val expiry=requireNotNull(p.executionExpiresAtMs)
        val cap=registry.get(p.capabilityId)
        if(expiry<=clock.nowMs() || cap?.descriptor?.version!=p.capabilityVersion) {
            store.atomic {store.get(id)?.takeIf {it.state==ProposalState.ACCEPTED}?.let {replace(it,it.copy(state=if(expiry<=clock.nowMs()) ProposalState.EXPIRED else ProposalState.FAILED,resolvedAtMs=clock.nowMs(),errorCode="PROPOSAL_EXECUTION_EXPIRED_OR_CHANGED"))}};return ProposalDispatch.FINISHED
        }
        store.atomic {store.get(id)?.takeIf {it.state==ProposalState.ACCEPTED}?.let {replace(it,it.copy(dispatchAttempts=it.dispatchAttempts+1,lastDispatchAtMs=clock.nowMs()))}}
        try {
            val r=engine.submit(InvocationRequest(requestId=requestId,agentId=p.agentId,capabilityId=p.capabilityId,
                arguments=p.arguments,idempotencyKey="proposal:$requestId",expiresAtMs=expiry),Origin.REMOTE)
            onRequest(r);return ProposalDispatch.RECORDED
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {
            // Only a successful authoritative lookup returning null permits another submission of this SAME ID.
            engine.request(requestId)?.let {onRequest(it);return ProposalDispatch.RECORDED}
            store.atomic {store.get(id)?.takeIf {it.state==ProposalState.ACCEPTED}?.let {replace(it,it.copy(errorCode="DISPATCH_NOT_RECORDED"))}}
            return ProposalDispatch.RETRY_PENDING
        }
    }
    fun onRequest(r:RequestRecord) {store.atomic {store.byRequest(r.requestId)?.let {reconcile(it)}}}
    fun expireDue() {store.atomic {store.expiring(clock.nowMs(),100).forEach {p->reconcile(p)}}}
    /** Reuses the runtime's existing sparse maintenance tick; no new poller or execution authority. */
    suspend fun maintain() {
        expireDue()
        store.reconciliationCandidates(100).forEach {p->
            try {if(p.state==ProposalState.ACCEPTED) dispatchAccepted(p.proposalId) else store.atomic {store.get(p.proposalId)?.let(::reconcile)}}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {events.emit(Event(clock.nowMs(),eventType="proposal.reconciliation.deferred",agentId=p.agentId,requestId=p.linkedRequestId,errorCode="PERSISTENCE_UNAVAILABLE"))}
        }
    }
    suspend fun recover() {
        var after:String?=null
        while(true) {
            val page=store.active(after,100);if(page.isEmpty()) break
            page.forEach {p->
                try {if(p.state==ProposalState.ACCEPTED) dispatchAccepted(p.proposalId) else store.atomic {store.get(p.proposalId)?.let(::reconcile)}}
                catch(e:CancellationException) {throw e}
                catch(e:Exception) {events.emit(Event(clock.nowMs(),eventType="proposal.recovery.deferred",requestId=p.linkedRequestId,errorCode="PERSISTENCE_UNAVAILABLE"))}
            }
            after=page.last().proposalId
        }
    }
    private fun reconcile(p:ActionProposal):ActionProposal {
        val r=p.linkedRequestId?.let(engine::request)
        if(r!=null) {
            if(r.agentId!=p.agentId || r.capabilityId!=p.capabilityId || r.argsHash!=p.actionHash) {
                if(p.errorCode=="LINKED_REQUEST_CONFLICT") return p
                return replace(p,p.copy(state=ProposalState.FAILED,errorCode="LINKED_REQUEST_CONFLICT",resolvedAtMs=clock.nowMs()))
            }
            val state=when(r.state) {
                RequestState.SUCCEEDED->ProposalState.COMPLETED
                RequestState.UNKNOWN_OUTCOME->ProposalState.UNKNOWN_OUTCOME
                RequestState.EXPIRED->ProposalState.EXPIRED
                RequestState.DENIED,RequestState.FAILED,RequestState.CANCELLED->ProposalState.FAILED
                RequestState.EXECUTING,RequestState.VERIFYING->ProposalState.EXECUTING
                else->ProposalState.ACCEPTED // includes AWAITING_APPROVAL; exposed separately as requestState
            }
            if(state!=p.state || r.state!=p.requestState || r.errorCode!=p.errorCode || r.result?.verification!=p.verification)
                return replace(p,p.copy(state=state,requestState=r.state,errorCode=r.errorCode,verification=r.result?.verification,resolvedAtMs=if(r.state.isTerminal) clock.nowMs() else null))
        } else if((p.state in setOf(ProposalState.PROPOSED,ProposalState.VIEWED) && p.expiresAtMs<=clock.nowMs()) ||
            (p.state==ProposalState.ACCEPTED && (p.executionExpiresAtMs ?: p.expiresAtMs)<=clock.nowMs()))
            return replace(p,p.copy(state=ProposalState.EXPIRED,resolvedAtMs=clock.nowMs()))
        return p
    }
}
