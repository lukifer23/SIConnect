package dev.siconnect.app.automation

import android.app.Notification
import android.app.NotificationManager
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
    private val notifications=g.context.getSystemService(NotificationManager::class.java)
    private fun bootCount()=android.provider.Settings.Global.getInt(g.context.contentResolver,android.provider.Settings.Global.BOOT_COUNT,-1)
    private fun elapsedDeadline(r:Reminder)=r.elapsedDeadlineMs?.takeIf { r.bootCount!=null && r.bootCount>=0 && r.bootCount==bootCount() }
    private fun remaining(r:Reminder)=elapsedDeadline(r)?.let {it-android.os.SystemClock.elapsedRealtime()} ?: (r.fireAtMs-System.currentTimeMillis())
    fun countdownAvailability()=if(canScheduleExact() && notifications.areNotificationsEnabled() &&
        listOf("countdown","timer_alerts").all { (notifications.getNotificationChannel(it)?.importance ?: 0)>NotificationManager.IMPORTANCE_NONE })
        Availability.IMPLEMENTED else Availability.PERMISSION_REQUIRED

    suspend fun startCountdown(ctx:ExecutionContext,seconds:Int,label:String?):Reminder {
        require(seconds in 1..86400)
        if(!countdownAvailability().isExecutable) throw CapabilityException("PERMISSION_REQUIRED","Enable precise alarms and timer notifications on the phone",noSideEffects=true)
        val duration=seconds*1000L
        val id="countdown:"+Settings.sha256Hex(ctx.agentId+":"+ctx.requestId)
        val reminder=Reminder(id,System.currentTimeMillis()+duration,label?.trim()?.takeIf {it.isNotEmpty()} ?: "Timer",
            "Time is up",ctx.agentId,exact=true,countdownSeconds=seconds,
            elapsedDeadlineMs=android.os.SystemClock.elapsedRealtime()+duration,bootCount=bootCount())
        return lifecycle.put(reminder,deduplicate=true)
    }

    fun ownedCountdown(agent:String,id:String):Reminder = get(id)?.takeIf {it.countdownSeconds!=null && it.agentId==agent}
        ?: throw CapabilityException("CLOCK_TIMER_NOT_FOUND","No such countdown for this agent",noSideEffects=true)

    fun countdownPage(agent:String,limit:Int,beforeMs:Long?,beforeId:String?):List<Reminder> {
        val created="countdown_started_at"
        val selection="created_by=? AND $created IS NOT NULL"
        val clause=if(beforeMs==null) "" else " AND ($created<? OR ($created=? AND reminder_id<?))"
        val args=if(beforeMs==null) arrayOf(agent) else arrayOf(agent,beforeMs.toString(),beforeMs.toString(),requireNotNull(beforeId))
        return table.list(selection+clause,args,"$created DESC, reminder_id DESC",limit).map {AppJson.decodeFromString(Reminder.serializer(),it)}
    }

    fun countdownMetadata(r:Reminder):JsonObject = buildJsonObject {
        put("timerId",r.id)
        put("packageName",g.context.packageName)
        put("label",r.title)
        put("durationSeconds",requireNotNull(r.countdownSeconds))
        put("createdAtMs",r.fireAtMs-r.countdownSeconds*1000L)
        put("deadlineAtMs",r.fireAtMs)
        put("state",r.state)
        put("remainingSeconds",if(r.state=="SCHEDULED") (remaining(r).coerceAtLeast(0)+999)/1000 else 0)
        put("notificationObserved",notifications.activeNotifications.any {it.tag==r.id})
        put("source","SICONNECT_COUNTDOWN")
    }

    private fun values(r: Reminder)=ContentValues().apply {
        put("reminder_id",r.id);put("fire_at",r.fireAtMs);put("state",r.state);put("created_by",r.agentId)
        put("json",AppJson.encodeToString(Reminder.serializer(),r));put("revision",r.revision)
        r.countdownSeconds?.let {put("countdown_started_at",r.fireAtMs-it*1000L)}
        putAll(ScheduleMetadata.reminder(r))
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
            if(reminder.countdownSeconds!=null) notifications.cancel(reminder.id,0)
        }
        override suspend fun schedule(reminder:Reminder) {
            if(reminder.exact) {
                check(canScheduleExact()) {"Exact timing permission unavailable"}
                val elapsed=elapsedDeadline(reminder)
                if(elapsed!=null) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,elapsed,pending(reminder))
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,reminder.fireAtMs,pending(reminder))
                val left=remaining(reminder)
                if(reminder.countdownSeconds!=null && left>0) {
                    val notification=Notification.Builder(g.context,"countdown").setSmallIcon(dev.siconnect.app.R.drawable.ic_stat)
                        .setContentTitle(reminder.title).setContentText("Countdown").setWhen(System.currentTimeMillis()+left)
                        .setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true)
                        .setOnlyAlertOnce(true).setOngoing(true).setTimeoutAfter(left).build()
                    notifications.notify(reminder.id,0,notification)
                }
            } else {
                val job=OneTimeWorkRequestBuilder<ReminderWorker>().setInputData(workDataOf("id" to reminder.id))
                    .setInitialDelay((reminder.fireAtMs-System.currentTimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS).build()
                WorkManager.getInstance(g.context).enqueueUniqueWork("reminder:${reminder.id}",ExistingWorkPolicy.REPLACE,job).result.get(10,TimeUnit.SECONDS)
            }
        }
        override fun reconcileDelivery(id:String) {enqueueDelivery(g.context,id)}
    },remainingMs=::remaining)
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
