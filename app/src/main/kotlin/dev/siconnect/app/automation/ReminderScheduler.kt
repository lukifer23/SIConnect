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
        put("json",AppJson.encodeToString(Reminder.serializer(),r));put("revision",r.revision)
    }
    private fun pending(r: Reminder)=PendingIntent.getBroadcast(g.context,r.id.hashCode(),
        Intent(g.context,ReminderAlarmReceiver::class.java).setData(Uri.parse("siconnect:reminder/${r.id}")).putExtra("id",r.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private val lifecycle=ReminderLifecycle(object:ReminderStore {
        override fun get(id:String)=this@ReminderScheduler.get(id)
        override fun all()=this@ReminderScheduler.all()
        override fun insert(reminder:Reminder)=table.insertUnique(values(reminder))
        override fun update(previous:Reminder,next:Reminder)=table.update(values(next),"reminder_id=? AND state=? AND revision=?",
            arrayOf(previous.id,previous.state,previous.revision.toString()))==1
    },g.engine,object:ReminderDispatch {
        override fun canScheduleExact()=this@ReminderScheduler.canScheduleExact()
        override suspend fun stop(reminder:Reminder) {
            WorkManager.getInstance(g.context).cancelUniqueWork("reminder:${reminder.id}").result.get(10,TimeUnit.SECONDS)
            am.cancel(pending(reminder))
        }
        override suspend fun schedule(reminder:Reminder) {
            if(reminder.exact) {
                check(canScheduleExact()) {"Exact timing permission unavailable"}
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,reminder.fireAtMs,pending(reminder))
            } else {
                val job=OneTimeWorkRequestBuilder<ReminderWorker>().setInputData(workDataOf("id" to reminder.id))
                    .setInitialDelay((reminder.fireAtMs-System.currentTimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS).build()
                WorkManager.getInstance(g.context).enqueueUniqueWork("reminder:${reminder.id}",ExistingWorkPolicy.REPLACE,job).result.get(10,TimeUnit.SECONDS)
            }
        }
        override fun reconcileDelivery(id:String) {enqueueDelivery(g.context,id)}
    })
    suspend fun createDeduplicated(r:Reminder)=lifecycle.put(r,deduplicate=true)
    suspend fun put(r:Reminder)=lifecycle.put(r)
    suspend fun cancel(id:String)=lifecycle.cancel(id)
    suspend fun restore()=lifecycle.restore()
    suspend fun onRequestUpdated(record:RequestRecord)=lifecycle.onRequestUpdated(record)
    suspend fun deliver(id:String):RequestState? {g.ready.await();return lifecycle.deliver(id)}
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
