package dev.siconnect.app.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import android.net.Uri
import androidx.work.*
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.graph
import dev.siconnect.app.data.*
import dev.siconnect.app.capabilities.Reminder
import dev.siconnect.core.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit

/** One durable reminder identity, with explicitly selected exact or deferrable delivery. */
class ReminderScheduler(private val g: RuntimeGraph) {
    private val table=JsonTable(g.db,"reminders","reminder_id")
    private val am=g.context.getSystemService(AlarmManager::class.java)
    fun all()=table.list().map { AppJson.decodeFromString(Reminder.serializer(),it) }
    fun get(id: String)=table.get(id)?.let { AppJson.decodeFromString(Reminder.serializer(),it) }
    fun canScheduleExact()=am.canScheduleExactAlarms()
    private fun values(r: Reminder)=ContentValues().apply {
        put("reminder_id",r.id);put("fire_at",r.fireAtMs);put("state",r.state);put("created_by",r.agentId)
        put("json",AppJson.encodeToString(Reminder.serializer(),r))
        put("revision",r.revision)
    }
    private fun transition(previous: Reminder,next: Reminder): Reminder? {
        val saved=next.copy(revision=previous.revision+1)
        return saved.takeIf {table.update(values(saved),"reminder_id=? AND state=? AND revision=?",arrayOf(previous.id,previous.state,previous.revision.toString()))==1}
    }
    private fun pending(r: Reminder)=PendingIntent.getBroadcast(g.context,r.id.hashCode(),
        Intent(g.context,ReminderAlarmReceiver::class.java).setData(Uri.parse("siconnect:reminder/${r.id}")).putExtra("id",r.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    @Synchronized fun createDeduplicated(r:Reminder):Reminder {
        val old=get(r.id)
        if(old!=null) {
            if(old.agentId!=r.agentId || old.fireAtMs!=r.fireAtMs || old.title!=r.title || old.text!=r.text || old.exact!=r.exact)
                throw dev.siconnect.core.CapabilityException("IDEMPOTENCY_CONFLICT","Reminder key belongs to different arguments; inspect the existing reminder and update explicitly",noSideEffects=true)
            return old // Includes delivered/unknown/cancelled: creation never re-arms them.
        }
        return put(r)
    }
    fun put(r: Reminder): Reminder {
        require(r.fireAtMs>System.currentTimeMillis()) { "Reminder must be in the future" }
        require(!r.exact || canScheduleExact()) { "Enable precise reminders in Android settings" }
        val previous=get(r.id)
        require(previous==null || previous.state in setOf("SCHEDULED","BLOCKED")) { "Reminder has already fired or is being delivered" }
        require(previous==null || previous.revision==r.revision) { "Reminder changed. Open it again." }
        val desired=r.copy(state="SCHEDULED",error=null)
        val saved=if(previous==null) {check(table.insertUnique(values(desired))) { "Reminder already exists" };desired}
            else requireNotNull(transition(previous,desired)) { "Reminder changed or is being delivered" }
        stop(r)
        return try { schedule(saved);saved } catch(e: Exception) {
            transition(saved,saved.copy(state="BLOCKED",error=e.javaClass.simpleName))
            throw e
        }
    }
    fun cancel(id: String): Reminder {
        val r=requireNotNull(get(id))
        require(r.state!="FIRING") { "Reminder is being delivered; its outcome must be checked" }
        val cancelled=requireNotNull(transition(r,r.copy(state="CANCELLED"))) { "Reminder changed or is being delivered" }
        stop(r)
        return cancelled
    }
    private fun stop(r: Reminder) {
        WorkManager.getInstance(g.context).cancelUniqueWork("reminder:${r.id}").result.get(10,TimeUnit.SECONDS)
        am.cancel(pending(r))
    }
    private fun schedule(r: Reminder) {
        if(r.exact) {
            check(canScheduleExact()) { "Exact timing permission unavailable" }
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,r.fireAtMs,pending(r))
        } else {
            val job=OneTimeWorkRequestBuilder<ReminderWorker>().setInputData(workDataOf("id" to r.id))
                .setInitialDelay((r.fireAtMs-System.currentTimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS).build()
            // SQLite is authoritative; replace persisted jobs when restoring across worker-class upgrades.
            WorkManager.getInstance(g.context).enqueueUniqueWork("reminder:${r.id}",ExistingWorkPolicy.REPLACE,job).result.get(10,TimeUnit.SECONDS)
        }
    }
    fun restore() {
        all().forEach { r ->
            when(r.state) {
                "SCHEDULED","BLOCKED" -> {
                    if(r.exact && !canScheduleExact()) transition(r,r.copy(state="BLOCKED",error="EXACT_PERMISSION_REQUIRED"))
                    else if(r.fireAtMs<System.currentTimeMillis()-6*60*60*1000L) transition(r,r.copy(state="MISSED",error="REMINDER_TOO_OLD"))
                    else runCatching { schedule(r);if(r.state=="BLOCKED") transition(r,r.copy(state="SCHEDULED",error=null)) }
                        .onFailure { transition(r,r.copy(state="BLOCKED",error=it.javaClass.simpleName)) }
                }
                "FIRING","AWAITING_APPROVAL" -> {
                    val record=g.requests.get("reminder:${r.id}")
                    if(record?.state?.isTerminal==true) transition(r,r.copy(state=record.state.name,error=record.errorCode))
                    else enqueueDelivery(g.context,r.id)
                }
            }
        }
    }
    fun onRequestUpdated(record:RequestRecord) {
        if(!record.state.isTerminal || !record.requestId.startsWith("reminder:")) return
        val r=get(record.requestId.removePrefix("reminder:")) ?: return
        if(r.state in setOf("FIRING","AWAITING_APPROVAL")) transition(r,r.copy(state=record.state.name,error=record.errorCode))
    }
    suspend fun deliver(id: String): RequestState? {
        g.ready.await()
        val r=get(id) ?: return null
        if(r.state !in setOf("SCHEDULED","FIRING")) return when(r.state) {
            "BLOCKED" -> RequestState.FAILED
            else -> RequestState.entries.firstOrNull { it.name==r.state }
        }
        if(r.fireAtMs>System.currentTimeMillis()+1000) return null
        val claimed=if(r.state=="FIRING") r else transition(r,r.copy(state="FIRING")) ?: return null
        // Reuse the same durable request. A lost delivery result must never create another post.
        val result=g.requests.get("reminder:$id") ?: g.engine.submit(InvocationRequest(requestId="reminder:$id",agentId="local-automation",capabilityId="notifications.post",
            arguments=buildJsonObject { put("title",r.title);put("text",r.text) }),Origin.LOCAL_AUTOMATION)
        if(result.state.isTerminal || result.state==RequestState.AWAITING_APPROVAL)
            transition(claimed,claimed.copy(state=result.state.name,error=result.errorCode))
        return result.state
    }
}
class ReminderWorker(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result {
        val state=applicationContext.graph.reminders.deliver(inputData.getString("id") ?: return Result.failure())
        return when {
            state==RequestState.SUCCEEDED || state==RequestState.CANCELLED -> Result.success()
            state==null || !state.isTerminal -> if(runAttemptCount<8) Result.retry() else Result.failure()
            else -> Result.failure()
        }
    }
}
/** Exact delivery posts the notification immediately through the engine; it is not deferred into WorkManager. */
class ReminderAlarmReceiver: BroadcastReceiver() {
    override fun onReceive(c: Context,intent: Intent) {
        val id=intent.getStringExtra("id") ?: return
        // Persist a watchdog before depending on runtime initialization or the receiver window.
        enqueueDelivery(c,id)
        val pending=goAsync()
        val wake=c.getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"SIConnect:reminder")
        wake.acquire(10_000)
        c.graph.scope.launch {
            try { withTimeout(8000) { c.graph.reminders.deliver(id) } }
            catch(e: Exception) { c.graph.events.emit(Event(System.currentTimeMillis(),eventType="reminder.delivery.error",requestId="reminder:$id",errorCode=e.javaClass.simpleName)) }
            finally { if(wake.isHeld) wake.release();pending.finish() }
        }
    }
}

/** Survives receiver timeout and ordinary process death. It only reconciles the original request. */
private fun enqueueDelivery(c:Context,id:String) {
    val work=OneTimeWorkRequestBuilder<ReminderWorker>().setInputData(workDataOf("id" to id))
        .setInitialDelay(10,TimeUnit.SECONDS)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
    WorkManager.getInstance(c).enqueueUniqueWork("reminder-delivery:$id",ExistingWorkPolicy.KEEP,work)
}
