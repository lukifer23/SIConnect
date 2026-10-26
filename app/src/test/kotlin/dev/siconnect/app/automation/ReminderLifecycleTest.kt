package dev.siconnect.app.automation

import dev.siconnect.app.capabilities.Reminder
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class ReminderLifecycleTest {
    private class Store:ReminderStore {
        val records=ConcurrentHashMap<String,Reminder>()
        override fun get(id:String)=records[id]
        override fun all()=records.values.toList()
        override fun insert(reminder:Reminder)=records.putIfAbsent(reminder.id,reminder)==null
        @Synchronized override fun update(previous:Reminder,next:Reminder):Boolean {
            if(records[previous.id]!=previous) return false
            records[previous.id]=next;return true
        }
    }
    private class Requests:RequestStore {
        val records=ConcurrentHashMap<String,RequestRecord>()
        @Synchronized override fun insert(record:RequestRecord):Boolean {
            if(records.containsKey(record.requestId) || record.idempotencyKey?.let {findByIdempotencyKey(record.agentId,it)}!=null) return false
            records[record.requestId]=record;return true
        }
        @Synchronized override fun update(record:RequestRecord,expected:RequestState):Boolean {
            if(records[record.requestId]?.state!=expected) return false
            records[record.requestId]=record;return true
        }
        override fun get(requestId:String)=records[requestId]
        override fun findByIdempotencyKey(agentId:String,key:String)=records.values.firstOrNull {it.agentId==agentId && it.idempotencyKey==key}
        override fun listByStates(states:Set<RequestState>)=records.values.filter {it.state in states}
    }
    private class Dispatch:ReminderDispatch {
        val jobs=mutableMapOf<String,Reminder>();val scheduled=mutableListOf<Long>();val reconciled=mutableListOf<String>()
        var beforeSchedule:suspend (Reminder)->Unit={};var beforeStop:suspend (Reminder)->Unit={}
        override fun canScheduleExact()=true
        override suspend fun stop(reminder:Reminder) {beforeStop(reminder);jobs.remove(reminder.id)}
        override suspend fun schedule(reminder:Reminder) {beforeSchedule(reminder);jobs[reminder.id]=reminder;scheduled+=reminder.revision}
        override fun reconcileDelivery(id:String) {reconciled+=id}
    }
    private class Fixture(scope:CoroutineScope,risk:RiskClass=RiskClass.LOW_IMPACT,block:suspend ()->Unit={}) {
        var now=1_000_000L;var posts=0
        val store=Store();val requests=Requests();val dispatch=Dispatch();val registry=CapabilityRegistry()
        init {
            registry.register(object:Capability {
                override val descriptor=CapabilityDescriptor("notifications.post",title="Test notification",description="Host fixture",
                    inputSchema=objectSchema {string("title","Title",true);string("text","Text",true)},backend=BackendKind.NATIVE,risk=risk)
                override fun availability()=Availability.IMPLEMENTED
                override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {
                    posts++;block();return CapabilityResult(JsonObject(emptyMap()),BackendKind.NATIVE)
                }
            })
        }
        val engine=ExecutionEngine("device",registry,PolicyEngine(),requests,{},object:ApprovalGate {
            override fun approvalRequested(record:RequestRecord) {}
            override fun approvalClosed(record:RequestRecord) {}
        },{AgentGrant(it,it,scopes=listOf("*"),autoApproveUpTo=RiskClass.LOW_IMPACT)},{DeviceControls()},scope,Clock {now})
        val lifecycle=ReminderLifecycle(store,engine,dispatch,Clock {now})
        fun reminder()=Reminder("fixture",now+5000,"Fixture","Disposable host test","agent")
        suspend fun create()=lifecycle.put(reminder())
        fun due() {now+=6000}
    }

    @Test fun cancellingPendingApprovalClosesOriginalRequestAndCannotPostAfterApproval() = runTest {
        val f=Fixture(backgroundScope,RiskClass.HIGH_IMPACT);f.create();f.due()
        assertEquals(RequestState.AWAITING_APPROVAL,f.lifecycle.deliver("fixture"))
        val pending=requireNotNull(f.engine.request("reminder:fixture"))
        assertEquals("CANCELLED",f.lifecycle.cancel("fixture").state)
        assertEquals(RequestState.CANCELLED,f.engine.resolveApproval(pending.requestId,true,"owner",pending.argsHash).state)
        assertEquals(0,f.posts);assertTrue(f.dispatch.jobs.isEmpty())
    }

    @Test fun cancellingExecutingMutationRetainsUncertaintyAndNeverRepeatsDelivery() = runTest {
        val release=CompletableDeferred<Unit>()
        val f=Fixture(backgroundScope) {withContext(NonCancellable) {release.await()}}
        f.create();f.due()
        val delivery=async {f.lifecycle.deliver("fixture")};runCurrent()
        assertEquals(1,f.posts)
        assertEquals("CANCELLING",f.lifecycle.cancel("fixture").state)
        release.complete(Unit);runCurrent()
        assertEquals(RequestState.UNKNOWN_OUTCOME,delivery.await())
        val unknown=f.store.get("fixture")!!
        assertEquals("UNKNOWN_OUTCOME",unknown.state)
        assertEquals(unknown,f.lifecycle.cancel("fixture"))
        assertEquals(RequestState.UNKNOWN_OUTCOME,f.lifecycle.deliver("fixture"));assertEquals(1,f.posts)
    }

    @Test fun completedDeliveryHistoryIsUnchangedByLateCancellation() = runTest {
        val f=Fixture(backgroundScope);f.create();f.due()
        val delivery=async {f.lifecycle.deliver("fixture")};runCurrent()
        assertEquals(RequestState.SUCCEEDED,delivery.await())
        val completed=f.store.get("fixture")!!
        assertEquals(completed,f.lifecycle.cancel("fixture"));assertEquals(1,f.posts)
    }

    @Test fun cancellationBarrierPreventsDeliveryWhilePlatformCancellationIsStillPending() = runTest {
        val f=Fixture(backgroundScope);f.create();f.due()
        val release=CompletableDeferred<Unit>();f.dispatch.beforeStop={release.await()}
        val cancelled=async {f.lifecycle.cancel("fixture")};runCurrent()
        val delivery=async {f.lifecycle.deliver("fixture")};runCurrent()
        assertEquals(0,f.posts)
        release.complete(Unit);runCurrent()
        assertEquals("CANCELLED",cancelled.await().state);assertEquals(RequestState.CANCELLED,delivery.await())
    }

    @Test fun concurrentUpdatesLeavePlatformDispatchAtTheNewestSavedRevision() = runTest {
        val f=Fixture(backgroundScope);val original=f.create();val release=CompletableDeferred<Unit>()
        f.dispatch.beforeSchedule={if(it.revision==1L) release.await()}
        val first=async {f.lifecycle.put(original.copy(fireAtMs=f.now+10_000))};runCurrent()
        val revised=f.store.get("fixture")!!
        val second=async {f.lifecycle.put(revised.copy(fireAtMs=f.now+20_000))};runCurrent()
        assertFalse(second.isCompleted)
        release.complete(Unit);runCurrent();first.await()
        assertEquals(second.await(),f.dispatch.jobs["fixture"])
        assertEquals(f.store.get("fixture"),f.dispatch.jobs["fixture"])
        assertEquals(listOf(0L,1L,2L),f.dispatch.scheduled)
    }

    @Test fun updateCannotInstallAnOlderJobAfterConcurrentCancellation() = runTest {
        val f=Fixture(backgroundScope);val original=f.create();val release=CompletableDeferred<Unit>()
        f.dispatch.beforeSchedule={if(it.revision==1L) release.await()}
        val update=async {f.lifecycle.put(original.copy(fireAtMs=f.now+10_000))};runCurrent()
        val cancel=async {f.lifecycle.cancel("fixture")};runCurrent()
        release.complete(Unit);runCurrent();update.await()
        assertEquals("CANCELLED",cancel.await().state);assertTrue(f.dispatch.jobs.isEmpty())
    }

    @Test fun restoreCannotOverwriteAConcurrentNewerSchedule() = runTest {
        val f=Fixture(backgroundScope);val original=f.create();val release=CompletableDeferred<Unit>()
        f.dispatch.beforeSchedule={if(it.revision==0L) release.await()}
        val restore=async {f.lifecycle.restore()};runCurrent()
        val update=async {f.lifecycle.put(original.copy(fireAtMs=f.now+20_000))};runCurrent()
        release.complete(Unit);runCurrent();restore.await()
        assertEquals(update.await(),f.dispatch.jobs["fixture"])
    }

    @Test fun persistedCancellationResumesAgainstTheSamePendingRequest() = runTest {
        val f=Fixture(backgroundScope,RiskClass.HIGH_IMPACT);f.create();f.due();f.lifecycle.deliver("fixture")
        f.store.records.computeIfPresent("fixture") {_,r->r.copy(state="CANCELLING",error="CANCELLATION_REQUESTED",revision=r.revision+1)}
        f.lifecycle.restore()
        assertEquals("CANCELLED",f.store.get("fixture")!!.state)
        assertEquals(RequestState.CANCELLED,f.engine.request("reminder:fixture")!!.state);assertEquals(0,f.posts)
    }

    @Test fun aConflictingRequestIdentityIsNeitherCancelledNorReportedAsDelivery() = runTest {
        val f=Fixture(backgroundScope);f.create()
        val collision=f.engine.submit(InvocationRequest(requestId="reminder:fixture",agentId="other",capabilityId="notifications.post",
            arguments=buildJsonObject {put("title","Other");put("text","Other operation")}))
        try {f.lifecycle.cancel("fixture");fail("Must reject a different operation")}
        catch(e:CapabilityException) {assertEquals("DELIVERY_REQUEST_CONFLICT",e.code)}
        assertEquals(collision,f.engine.request(collision.requestId));assertEquals("SCHEDULED",f.store.get("fixture")!!.state)
    }

    @Test fun duplicateCreationNeverRearmsACancelledReminder() = runTest {
        val f=Fixture(backgroundScope);val original=f.create();val cancelled=f.lifecycle.cancel("fixture")
        assertEquals(cancelled,f.lifecycle.put(original,deduplicate=true));assertTrue(f.dispatch.jobs.isEmpty())
    }

    @Test fun platformFailureLeavesRecoverableBlockedState() = runTest {
        val f=Fixture(backgroundScope);f.dispatch.beforeStop={error("Platform unavailable")}
        try {f.create();fail("Dispatch must fail")}
        catch(_:IllegalStateException) {}
        assertEquals("BLOCKED",f.store.get("fixture")!!.state)
        f.dispatch.beforeStop={};f.lifecycle.restore()
        assertEquals("SCHEDULED",f.store.get("fixture")!!.state);assertNotNull(f.dispatch.jobs["fixture"])
    }
}
