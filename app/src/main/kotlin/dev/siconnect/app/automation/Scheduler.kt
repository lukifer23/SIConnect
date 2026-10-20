package dev.siconnect.app.automation

import android.content.*
import android.app.AlarmManager
import android.app.PendingIntent
import androidx.work.*
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.graph
import dev.siconnect.app.data.*
import dev.siconnect.core.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

@Serializable
data class Automation(val id: String, val skillId: String, val nextAtMs: Long,
    val intervalMs: Long? = null, val enabled: Boolean = true, val exact: Boolean = false,
    val zoneId: String = java.time.ZoneId.systemDefault().id,
    val lastRunId: String? = null, val lastState: String? = null, val error: String? = null)

class Scheduler(private val g: RuntimeGraph) {
    private val table = JsonTable(g.db, "automations", "automation_id")
    fun all(): List<Automation> = table.list().map { AppJson.decodeFromString(Automation.serializer(), it) }
    fun put(a: Automation) {
        require(a.intervalMs == null || a.intervalMs >= 900_000) { "Recurrence minimum is 15 minutes" }
        require(g.skills.get(a.skillId) != null) { "Install and enable the skill first" }
        java.time.ZoneId.of(a.zoneId)
        if (a.exact) require(g.context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) { "Exact alarm special access required" }
        table.upsert(ContentValues().apply {
            put("automation_id", a.id); put("enabled", if(a.enabled) 1 else 0); put("next_run_at", a.nextAtMs)
            put("owner", "local-automation"); put("json", AppJson.encodeToString(Automation.serializer(), a))
        })
        schedule(a)
    }
    fun setEnabled(id: String, enabled: Boolean) { all().firstOrNull { it.id==id }?.let { put(it.copy(enabled=enabled)) } }
    fun restore() { all().filter { it.enabled }.forEach(::schedule) }
    private fun alarm(a: Automation): PendingIntent = PendingIntent.getBroadcast(g.context, a.id.hashCode(),
        Intent(g.context, AlarmReceiver::class.java).setData(android.net.Uri.parse("siconnect:automation/${a.id}"))
            .putExtra("id", a.id).putExtra("slot", a.nextAtMs), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(a: Automation) {
        val work=WorkManager.getInstance(g.context)
        val am=g.context.getSystemService(AlarmManager::class.java)
        if (!a.enabled) { work.cancelUniqueWork("automation:${a.id}"); am.cancel(alarm(a)); return }
        if (a.exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.nextAtMs, alarm(a))
        else {
            val request=OneTimeWorkRequestBuilder<AutomationWorker>().setInputData(workDataOf("id" to a.id, "slot" to a.nextAtMs))
                .setInitialDelay((a.nextAtMs-System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
            work.enqueueUniqueWork("automation:${a.id}", ExistingWorkPolicy.REPLACE, request)
        }
    }
    suspend fun fire(id: String, slot: Long) {
        g.ready.await()
        val a=all().firstOrNull { it.id==id && it.enabled && it.nextAtMs==slot } ?: return
        val runId="automation:$id:$slot"
        val runs=JsonTable(g.db, "automation_runs", "run_id")
        // Slot record and deterministic child run identity jointly guard duplicate deliveries.
        val inserted=runs.insertUnique(ContentValues().apply {
            put("run_id",runId); put("automation_id",id); put("slot_at",slot); put("started_at",System.currentTimeMillis()); put("state","RUNNING")
        })
        if (!inserted) return
        var error: String? = null
        val result=runCatching {
            val def=requireNotNull(g.skills.get(a.skillId)) { "Skill missing or disabled" }
            g.runner.start(def, kotlinx.serialization.json.JsonObject(emptyMap()), ExecutionContext(runId,runId,"local-automation",g.settings.deviceId), Origin.LOCAL_AUTOMATION)
        }.getOrElse { error=it.javaClass.simpleName; null }
        val state=result?.state?.name ?: "FAILED"
        runs.update(ContentValues().apply { put("state",state); put("finished_at",System.currentTimeMillis()); put("json", result?.let { AppJson.encodeToString(SkillExecution.serializer(),it) }) },"run_id=?",arrayOf(runId))
        // Missed intervals coalesce into one run, then advance to the next future slot.
        val interval=a.intervalMs
        val next=if(interval!=null) slot + ((System.currentTimeMillis()-slot).coerceAtLeast(0)/interval+1)*interval else slot
        put(a.copy(nextAtMs=next, enabled=interval!=null, lastRunId=runId,lastState=state,error=error))
    }
}
class AutomationWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c,p) {
    override suspend fun doWork(): Result {
        return try { applicationContext.graph.scheduler.fire(inputData.getString("id") ?: return Result.failure(),inputData.getLong("slot",0)); Result.success() }
        catch (_: Exception) { Result.failure() }
    }
}
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id=intent.getStringExtra("id") ?: return
        // Alarm delivery dispatches durable work; no long-running coroutine survives the receiver alone.
        WorkManager.getInstance(c).enqueue(OneTimeWorkRequestBuilder<AutomationWorker>().setInputData(workDataOf("id" to id,"slot" to intent.getLongExtra("slot",0))).build())
    }
}
