package dev.siconnect.app.automation

import dev.siconnect.app.capabilities.Reminder
import dev.siconnect.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal interface ReminderStore {
    fun get(id: String): Reminder?
    fun all(): List<Reminder>
    fun insert(reminder: Reminder): Boolean
    fun update(previous: Reminder, next: Reminder): Boolean
}

internal interface ReminderDispatch {
    fun canScheduleExact(): Boolean
    suspend fun stop(reminder: Reminder)
    suspend fun schedule(reminder: Reminder)
    fun reconcileDelivery(id: String)
}

/** Serializes each identity's durable state and OS dispatch; execution waits never hold its lock. */
internal class ReminderLifecycle(
    private val store: ReminderStore,
    private val engine: ExecutionEngine,
    private val dispatch: ReminderDispatch,
    private val clock: Clock = Clock { System.currentTimeMillis() },
) {
    private val locks=Array(32) {Mutex()}
    private fun lock(id:String)=locks[(id.hashCode() and Int.MAX_VALUE)%locks.size]
    private fun save(old:Reminder,next:Reminder):Reminder = next.copy(revision=old.revision+1).also {
        check(store.update(old,it)) {"Reminder changed; inspect the original delivery request"}
    }
    private fun deliveryId(id:String)="reminder:$id"
    private fun arguments(r:Reminder)=buildJsonObject {put("title",r.title);put("text",r.text)}
    private fun delivery(r:Reminder):RequestRecord? = engine.request(deliveryId(r.id))?.also {
        requireDelivery(r,it)
    }
    private fun requireDelivery(r:Reminder,record:RequestRecord) {
        if(record.agentId!="local-automation" || record.capabilityId!="notifications.post" ||
            record.origin!=Origin.LOCAL_AUTOMATION.name || record.arguments!=arguments(r))
            throw CapabilityException("DELIVERY_REQUEST_CONFLICT","Original request identity belongs to another operation; inspect it before retrying",noSideEffects=true)
    }
    private fun project(r:Reminder,record:RequestRecord):Reminder =
        if(record.state.isTerminal || record.state==RequestState.AWAITING_APPROVAL)
            save(r,r.copy(state=record.state.name,error=record.errorCode)) else r

    suspend fun put(r:Reminder,deduplicate:Boolean=false):Reminder = lock(r.id).withLock {
        val old=store.get(r.id)
        if(deduplicate && old!=null) {
            if(old.agentId!=r.agentId || old.fireAtMs!=r.fireAtMs || old.title!=r.title || old.text!=r.text || old.exact!=r.exact)
                throw CapabilityException("IDEMPOTENCY_CONFLICT","Reminder key belongs to different arguments; update explicitly",noSideEffects=true)
            return@withLock old
        }
        require(r.fireAtMs>clock.nowMs()) {"Reminder must be in the future"}
        require(deliveryId(r.id).length<=200) {"Reminder identity exceeds its delivery request budget"}
        require(!r.exact || dispatch.canScheduleExact()) {"Enable precise reminders in Android settings"}
        require(old==null || old.state in setOf("SCHEDULED","BLOCKED")) {"Reminder has already fired or is being delivered"}
        require(old==null || old.revision==r.revision) {"Reminder changed. Open it again."}
        val desired=r.copy(state="SCHEDULED",error=null)
        val saved=if(old==null) {check(store.insert(desired)) {"Reminder already exists"};desired} else save(old,desired)
        try {dispatch.stop(saved);dispatch.schedule(saved);saved}
        catch(e:Exception) {save(saved,saved.copy(state="BLOCKED",error=e.javaClass.simpleName));throw e}
    }

    suspend fun cancel(id:String):Reminder = lock(id).withLock {
        val r=requireNotNull(store.get(id)) {"Reminder no longer exists"}
        // Cancelling again cannot rewrite a completed/unknown delivery as safely cancelled.
        if(r.state in RequestState.TERMINAL.map {it.name} || r.state=="MISSED") return@withLock r
        val existing=delivery(r)
        val barrier=save(r,r.copy(state="CANCELLING",error="CANCELLATION_REQUESTED"))
        // deliver() submits under the same lock without awaiting execution. A missing record
        // here cannot race a live submission after this persisted barrier.
        val record=existing?.let {engine.cancel(it.requestId,"reminder cancelled")}
        val result=if(record==null) save(barrier,barrier.copy(state="CANCELLED",error=null)) else project(barrier,record)
        dispatch.stop(result)
        if(record!=null && !record.state.isTerminal) dispatch.reconcileDelivery(id)
        result
    }

    suspend fun onRequestUpdated(record:RequestRecord) {
        if(!record.state.isTerminal || !record.requestId.startsWith("reminder:")) return
        val id=record.requestId.removePrefix("reminder:")
        lock(id).withLock {
            store.get(id)?.takeIf {it.state in setOf("FIRING","AWAITING_APPROVAL","CANCELLING")}?.let {requireDelivery(it,record);project(it,record)}
        }
    }

    suspend fun deliver(id:String):RequestState? {
        val initial=lock(id).withLock {
            val r=store.get(id) ?: return null
            if(r.state=="CANCELLING") {
                val record=delivery(r)?.let {engine.cancel(it.requestId,"reminder cancelled")}
                if(record==null) {save(r,r.copy(state="CANCELLED",error=null));return RequestState.CANCELLED}
                project(r,record)
                return@withLock record
            }
            if(r.state !in setOf("SCHEDULED","FIRING","AWAITING_APPROVAL")) return when(r.state) {
                "BLOCKED"->RequestState.FAILED
                else->RequestState.entries.firstOrNull {it.name==r.state}
            }
            if(r.fireAtMs>clock.nowMs()+1000) return null
            val claimed=if(r.state=="SCHEDULED") save(r,r.copy(state="FIRING")) else r
            val record=delivery(r) ?: engine.submit(InvocationRequest(requestId=deliveryId(id),
                agentId="local-automation",capabilityId="notifications.post",arguments=arguments(r)),Origin.LOCAL_AUTOMATION,awaitCompletion=false)
            project(claimed,record)
            record
        }
        if(initial.state.isTerminal || initial.state==RequestState.AWAITING_APPROVAL) return initial.state
        val result=engine.await(initial.requestId,30_000,untilTerminal=false) ?: initial
        onRequestUpdated(result)
        return result.state
    }

    suspend fun restore() {
        store.all().forEach {snapshot -> lock(snapshot.id).withLock {
            val r=store.get(snapshot.id) ?: return@withLock
            when(r.state) {
                "SCHEDULED","BLOCKED" -> {
                    if(r.exact && !dispatch.canScheduleExact()) save(r,r.copy(state="BLOCKED",error="EXACT_PERMISSION_REQUIRED"))
                    else if(r.fireAtMs<clock.nowMs()-6*60*60_000L) save(r,r.copy(state="MISSED",error="REMINDER_TOO_OLD"))
                    else try {dispatch.schedule(r);if(r.state=="BLOCKED") save(r,r.copy(state="SCHEDULED",error=null))}
                        catch(e:CancellationException) {throw e}
                        catch(e:Exception) {save(r,r.copy(state="BLOCKED",error=e.javaClass.simpleName))}
                }
                "FIRING","AWAITING_APPROVAL","CANCELLING" -> {
                    val existing=delivery(r)
                    val record=if(r.state=="CANCELLING") existing?.let {engine.cancel(it.requestId,"reminder cancellation recovery")} else existing
                    if(record==null && r.state=="CANCELLING") save(r,r.copy(state="CANCELLED",error=null))
                    else if(record?.state?.isTerminal==true) project(r,record)
                    else dispatch.reconcileDelivery(r.id)
                }
            }
        } }
    }
}
