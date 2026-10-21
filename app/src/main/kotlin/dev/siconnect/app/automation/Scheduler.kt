package dev.siconnect.app.automation

import android.content.*
import android.app.AlarmManager
import android.app.PendingIntent
import androidx.work.*
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.graph
import dev.siconnect.app.data.*
import dev.siconnect.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit

@Serializable
data class Automation(val id: String, val skillId: String, val nextAtMs: Long,
    val intervalMs: Long? = null, val enabled: Boolean = true, val exact: Boolean = false,
    val zoneId: String = java.time.ZoneId.systemDefault().id,
    val lastRunId: String? = null, val lastState: String? = null, val error: String? = null,
    /** Null retains the original fixed-interval/one-shot interpretation. */
    val recurrence: Recurrence? = null,
    val missedPolicy: MissedRunPolicy = MissedRunPolicy.COALESCE,
    val maxLatenessMs: Long = 6*60*60_000L,
    val inputs: JsonObject = JsonObject(emptyMap()),
    val revision: Long = 0,
    val scheduleState: String = "SCHEDULED") {
    fun nextAfter(now:Long):Long? = (recurrence ?: Recurrence(if(intervalMs==null) RecurrenceKind.ONCE else RecurrenceKind.FIXED_INTERVAL,intervalMs=intervalMs)).nextAfter(nextAtMs,now,zoneId)
}

enum class AutomationRunState {
    READY, RUNNING, AWAITING_APPROVAL, SUCCEEDED, PARTIALLY_SUCCEEDED, FAILED, BLOCKED, CANCELLED, UNKNOWN_OUTCOME, MISSED;
    val terminal:Boolean get()=this !in setOf(READY,RUNNING,AWAITING_APPROVAL)
}
data class AutomationRun(val runId:String,val automationId:String,val slotAt:Long,val state:String,val finishedAt:Long?,val error:String?)

/** SQLite owns run slots and advancement. AlarmManager/WorkManager dispatch is reconciled separately. */
class Scheduler(private val g: RuntimeGraph) {
    // Fixed stripes bound lock storage even after a large number of removed automations.
    private val locks=Array(32) {Mutex()}
    private fun lock(id:String)=locks[(id.hashCode() and Int.MAX_VALUE)%locks.size]
    private fun decode(text:String)=AppJson.decodeFromString(Automation.serializer(),text)
    fun all(): List<Automation> = g.db.readableDatabase.query("automations",arrayOf("json"),null,null,null,null,"next_run_at").mapAll {decode(it.getString(0))}
    fun get(id:String):Automation? = g.db.readableDatabase.query("automations",arrayOf("json"),"automation_id=?",arrayOf(id),null,null,null,"1").mapAll {decode(it.getString(0))}.firstOrNull()
    fun runs(id:String,limit:Int=20):List<AutomationRun> = g.db.readableDatabase.query("automation_runs",arrayOf("run_id","automation_id","slot_at","state","finished_at","error_code"),"automation_id=?",arrayOf(id),null,null,"slot_at DESC",limit.coerceIn(1,100).toString()).mapAll {
        AutomationRun(it.getString(0),it.getString(1),it.getLong(2),it.getString(3),if(it.isNull(4)) null else it.getLong(4),if(it.isNull(5)) null else it.getString(5))
    }
    private fun values(a:Automation)=ContentValues().apply {
        put("automation_id",a.id);put("enabled",if(a.enabled) 1 else 0);put("next_run_at",a.nextAtMs)
        put("owner","local-automation");put("revision",a.revision);put("json",AppJson.encodeToString(Automation.serializer(),a))
    }
    fun put(a: Automation):Automation {
        require(a.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}"))) {"Invalid automation id"}
        require(a.intervalMs == null || a.intervalMs >= 900_000) { "Recurrence minimum is 15 minutes" }
        require(a.recurrence==null || a.intervalMs==null) {"Choose typed recurrence or legacy interval, not both"}
        a.recurrence?.validate();java.time.ZoneId.of(a.zoneId)
        require(a.nextAtMs>0 && a.maxLatenessMs in 60_000..86_400_000) {"Invalid timing/lateness"}
        if(a.enabled) {
            val def=requireNotNull(g.skills.get(a.skillId)) { "Install and enable the skill first" }
            require(Schema.validate(def.inputs,a.inputs).isEmpty()) {"Invalid skill inputs"}
        }
        val w=g.db.writableDatabase;val saved:Automation;val old:Automation?
        w.beginTransaction()
        try {
            old=get(a.id)
            require(old==null || old.revision==a.revision) {"Automation changed; reload before saving"}
            saved=a.copy(revision=(old?.revision ?: -1)+1,scheduleState=if(a.enabled) "SCHEDULED" else "DISABLED",error=null)
            w.insertWithOnConflict("automations",null,values(saved),android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            g.events.emit(Event(System.currentTimeMillis(),eventType="automation.configured",requestId=a.id,agentId="local-owner",detail=if(a.enabled) "Schedule saved" else "Future dispatch disabled; active run is separate"))
            w.setTransactionSuccessful()
        } finally {w.endTransaction()}
        old?.let(::cancelDispatch)
        syncDispatch(saved)
        return requireNotNull(get(a.id))
    }
    fun setEnabled(id: String, enabled: Boolean) { get(id)?.let { put(it.copy(enabled=enabled)) } }
    suspend fun cancelRun(runId:String) {g.runner.cancel(runId);reconcileRun(runId)}

    /** Called after engine/skill startup recovery; never executes user work inside initialization. */
    suspend fun restore() {
        val active=g.db.readableDatabase.query("automation_runs",arrayOf("run_id","automation_id","slot_at"),"state IN ('READY','RUNNING','AWAITING_APPROVAL')",null,null,null,null).mapAll {Triple(it.getString(0),it.getString(1),it.getLong(2))}
        active.forEach {(run,id,slot)->
            if(g.skillRuns.get(run)!=null) reconcileRun(run)
            else enqueue(id,slot,10_000)
        }
        // A terminal result may have committed immediately before schedule advancement crashed.
        all().forEach {a->
            val run=runId(a.id,a.nextAtMs)
            if(g.skillRuns.get(run)!=null) reconcileRun(run)
            get(a.id)?.let(::syncDispatch)
        }
    }
    suspend fun clockChanged() {
        all().filter {it.enabled}.forEach {a ->
            val run=g.skillRuns.get(runId(a.id,a.nextAtMs))
            if(run==null) {cancelDispatch(a);syncDispatch(a)}
        }
    }
    private fun alarm(a: Automation): PendingIntent = PendingIntent.getBroadcast(g.context,0,
        Intent(g.context, AlarmReceiver::class.java).setData(android.net.Uri.parse("siconnect:automation/${a.id}/${a.nextAtMs}"))
            .putExtra("id", a.id).putExtra("slot", a.nextAtMs), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun workName(id:String,slot:Long)="automation:$id:$slot"
    private fun cancelDispatch(a:Automation) {
        val owned=g.db.readableDatabase.query("automation_runs",arrayOf("state"),"run_id=?",arrayOf(runId(a.id,a.nextAtMs)),null,null,null,"1")
            .mapAll {it.getString(0)}.firstOrNull()
        // Disabling future dispatch must not cancel a worker that owns a durable active skill.
        if(owned==null || runCatching {AutomationRunState.valueOf(owned).terminal}.getOrDefault(true))
            WorkManager.getInstance(g.context).cancelUniqueWork(workName(a.id,a.nextAtMs)).result.get(10,TimeUnit.SECONDS)
        // Cancel pre-v5 WorkManager dispatch without changing any persisted definition.
        WorkManager.getInstance(g.context).cancelUniqueWork("automation:${a.id}")
        g.context.getSystemService(AlarmManager::class.java).cancel(alarm(a))
        val legacy=PendingIntent.getBroadcast(g.context,a.id.hashCode(),Intent(g.context,AlarmReceiver::class.java)
            .setData(android.net.Uri.parse("siconnect:automation/${a.id}")),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        legacy?.let {g.context.getSystemService(AlarmManager::class.java).cancel(it);it.cancel()}
    }
    private fun enqueue(id:String,slot:Long,delay:Long=0) {
        val work=OneTimeWorkRequestBuilder<AutomationWorker>().setInputData(workDataOf("id" to id,"slot" to slot))
            .setInitialDelay(delay.coerceAtLeast(0),TimeUnit.MILLISECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(g.context).enqueueUniqueWork(workName(id,slot),ExistingWorkPolicy.KEEP,work).result.get(10,TimeUnit.SECONDS)
    }
    private fun syncDispatch(a:Automation) {
        if(!a.enabled) return
        try {
            val run=g.skillRuns.get(runId(a.id,a.nextAtMs))
            if(run?.state==SkillState.AWAITING_APPROVAL) return // approval/result callback owns continuation
            if(a.exact && run==null) {
                val am=g.context.getSystemService(AlarmManager::class.java)
                check(am.canScheduleExactAlarms()) {"EXACT_PERMISSION_REQUIRED"}
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,a.nextAtMs,alarm(a))
            } else enqueue(a.id,a.nextAtMs,if(run==null) a.nextAtMs-System.currentTimeMillis() else 10_000)
            updateDispatch(a,if(run==null) "SCHEDULED" else run.state.name,null)
        } catch(e:Exception) {updateDispatch(a,"BLOCKED",if(e is IllegalStateException && e.message=="EXACT_PERMISSION_REQUIRED") e.message else e.javaClass.simpleName)}
    }
    private fun updateDispatch(a:Automation,state:String,error:String?) {
        g.db.writableDatabase.update("automations",values(a.copy(scheduleState=state,error=error)),"automation_id=? AND revision=? AND next_run_at=?",arrayOf(a.id,a.revision.toString(),a.nextAtMs.toString()))
    }
    suspend fun fire(id: String, slot: Long):AutomationRunState {
        g.ready.await()
        return lock(id).withLock {
            val runId=runId(id,slot)
            val w=g.db.writableDatabase
            var snapshot:Automation?=null;var definition:SkillDefinition?=null;var priorState:String?=null
            w.beginTransaction()
            try {
                w.query("automation_runs",arrayOf("automation_json","skill_json","state"),"run_id=?",arrayOf(runId),null,null,null).use {c->
                    if(c.moveToFirst()) {
                        snapshot=if(c.isNull(0)) get(id) else decode(c.getString(0))
                        definition=if(c.isNull(1)) null else SkillJson.parse(c.getString(1));priorState=c.getString(2)
                    }
                }
                if(priorState==null) {
                    val a=get(id)
                    if(a==null || !a.enabled || a.nextAtMs!=slot) return@withLock AutomationRunState.CANCELLED
                    if(slot>System.currentTimeMillis()+1000) return@withLock AutomationRunState.READY
                    snapshot=a;definition=g.skills.get(a.skillId)
                    w.insertOrThrow("automation_runs",null,ContentValues().apply {
                        put("run_id",runId);put("automation_id",id);put("slot_at",slot);put("started_at",System.currentTimeMillis());put("updated_at",System.currentTimeMillis());put("state","READY")
                        put("automation_json",AppJson.encodeToString(Automation.serializer(),a));put("skill_json",definition?.let {SkillJson.json.encodeToString(SkillDefinition.serializer(),it)})
                    })
                }
                w.setTransactionSuccessful()
            } finally {w.endTransaction()}
            val a=snapshot ?: return@withLock AutomationRunState.BLOCKED
            val stored=g.skillRuns.get(runId)
            if(stored==null && priorState?.let {runCatching {AutomationRunState.valueOf(it).terminal}.getOrDefault(false)}==true) {
                finish(a,runId,AutomationRunState.valueOf(requireNotNull(priorState)),null,"LEGACY_RUN_RESULT");return@withLock AutomationRunState.valueOf(requireNotNull(priorState))
            }
            if(stored==null) {
                val late=System.currentTimeMillis()-slot
                val missed=when(a.missedPolicy) {
                    MissedRunPolicy.SKIP,MissedRunPolicy.MARK_MISSED -> late>60_000
                    MissedRunPolicy.RUN_ONCE_IF_STILL_RELEVANT,MissedRunPolicy.COALESCE -> late>a.maxLatenessMs
                }
                if(missed) {finish(a,runId,AutomationRunState.MISSED,null,"MISSED_${a.missedPolicy}");return@withLock AutomationRunState.MISSED}
                if(definition==null) {finish(a,runId,AutomationRunState.BLOCKED,null,"SKILL_UNAVAILABLE");return@withLock AutomationRunState.BLOCKED}
            }
            try {
                val result=if(stored==null) g.runner.start(requireNotNull(definition),a.inputs,ExecutionContext(runId,runId,"local-automation",g.settings.deviceId),Origin.LOCAL_AUTOMATION)
                    else g.runner.resume(runId)
                val state=AutomationRunState.valueOf(result.state.name)
                finish(a,runId,state,result,result.error)
                state
            } catch(e:CancellationException) {throw e} catch(e:Exception) {
                // Never label a possibly started workflow as safely failed. Reconcile its persisted cursor.
                val run=g.skillRuns.get(runId)
                if(run!=null) {
                    val state=AutomationRunState.valueOf(run.state.name);finish(a,runId,state,run,e.javaClass.simpleName);state
                } else {finish(a,runId,AutomationRunState.BLOCKED,null,e.javaClass.simpleName);AutomationRunState.BLOCKED}
            }
        }
    }
    suspend fun reconcileActive() {
        val rows=g.db.readableDatabase.query("automation_runs",arrayOf("run_id","state"),"state IN ('READY','RUNNING','AWAITING_APPROVAL')",null,null,null,null)
            .mapAll {it.getString(0) to it.getString(1)}
        rows.forEach {(id,state)->if(g.skillRuns.get(id)?.state?.name?.let {it!=state}==true) reconcileRun(id)}
    }
    /** Called by persisted skill-state notifications and startup recovery. */
    suspend fun reconcileRun(runId:String) {
        val info=g.db.readableDatabase.query("automation_runs",arrayOf("automation_id","automation_json"),"run_id=?",arrayOf(runId),null,null,null,"1").mapAll {it.getString(0) to if(it.isNull(1)) null else decode(it.getString(1))}.firstOrNull() ?: return
        lock(info.first).withLock {
            val r=g.skillRuns.get(runId) ?: return@withLock
            val a=info.second ?: get(info.first) ?: return@withLock
            finish(a,runId,AutomationRunState.valueOf(r.state.name),r,r.error)
        }
    }
    private fun finish(snapshot:Automation,runId:String,state:AutomationRunState,result:SkillExecution?,error:String?) {
        val now=System.currentTimeMillis();val w=g.db.writableDatabase;var next:Automation?=null
        w.beginTransaction()
        try {
            val previous=w.query("automation_runs",arrayOf("state","finished_at","json","error_code"),"run_id=?",arrayOf(runId),null,null,null,"1").use {c->
                if(!c.moveToFirst()) null else listOf(c.getString(0),if(c.isNull(1)) null else c.getLong(1).toString(),if(c.isNull(2)) null else c.getString(2),if(c.isNull(3)) null else c.getString(3))
            }
            val encoded=result?.let {AppJson.encodeToString(SkillExecution.serializer(),it)}
            val changed=previous==null || previous[0]!=state.name || (encoded!=null && previous[2]!=encoded) || previous[3]!=error
            w.update("automation_runs",ContentValues().apply {
                put("state",state.name);put("updated_at",now);put("error_code",error)
                if(state.terminal) put("finished_at",previous?.get(1)?.toLongOrNull() ?: now) else putNull("finished_at")
                result?.let {put("json",AppJson.encodeToString(SkillExecution.serializer(),it))}
            },"run_id=?",arrayOf(runId))
            val current=get(snapshot.id)
            if(current!=null && current.nextAtMs==snapshot.nextAtMs) {
                val future=if(state==AutomationRunState.UNKNOWN_OUTCOME) null else if(state.terminal) current.nextAfter(now) else current.nextAtMs
                val updated=current.copy(nextAtMs=future ?: current.nextAtMs,enabled=current.enabled && (!state.terminal || future!=null),
                    lastRunId=runId,lastState=state.name,error=error,scheduleState=if(state.terminal && future!=null && current.enabled) "SCHEDULED" else state.name)
                w.update("automations",values(updated),"automation_id=? AND revision=?",arrayOf(current.id,current.revision.toString()))
                if(state.terminal && updated.enabled) next=updated
            }
            if(changed) g.events.emit(Event(now,eventType="automation.run.state",requestId=runId,traceId=runId,agentId="local-automation",executionStatus=state.name,errorCode=error))
            w.setTransactionSuccessful()
        } finally {w.endTransaction()}
        next?.let(::syncDispatch)
    }
    companion object {fun runId(id:String,slot:Long)="automation:$id:$slot"}
}
class AutomationWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c,p) {
    override suspend fun doWork(): Result = try {
        val state=applicationContext.graph.scheduler.fire(inputData.getString("id") ?: return Result.failure(),inputData.getLong("slot",0))
        when(state) {
            AutomationRunState.READY,AutomationRunState.RUNNING -> Result.retry()
            AutomationRunState.FAILED,AutomationRunState.PARTIALLY_SUCCEEDED,AutomationRunState.BLOCKED,AutomationRunState.UNKNOWN_OUTCOME,AutomationRunState.MISSED -> Result.failure()
            else -> Result.success() // AWAITING_APPROVAL is a durable handoff, never displayed as completed.
        }
    } catch(e:CancellationException) {throw e} catch(_:Exception) {Result.retry()}
}
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id=intent.getStringExtra("id") ?: return
        val slot=intent.getLongExtra("slot",0)
        WorkManager.getInstance(c).enqueueUniqueWork("automation:$id:$slot",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutomationWorker>().setInputData(workDataOf("id" to id,"slot" to slot)).build())
    }
}
