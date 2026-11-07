package dev.siconnect.app.clocks

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.accessibility.SemanticUiRules
import dev.siconnect.app.backend.ShellRequest
import dev.siconnect.app.notifications.NotificationListenerAccess
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID

internal class ClockTimers(private val g:RuntimeGraph) {
    private val journal by lazy {ClockJournal(g.context).also {it.recoverOrphans()}}
    private val notifications=ClockNotifications(g.context)
    fun supportedUser():Boolean=g.context.getSystemService(android.os.UserManager::class.java).isSystemUser
    private fun requireSupportedUser() {
        if(!supportedUser()) throw CapabilityException("CLOCK_PROFILE_UNSUPPORTED",
            "Managed Clock timers currently support Android's system user only; no timer action was sent to another profile",noSideEffects=true)
    }
    fun controlAvailability():Availability=if(!supportedUser()) Availability.UNSUPPORTED else NotificationListenerAccess.availability(g.context)
    fun packageName(requested:String?):String {
        if(requested!=null) {
            require(requested.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) && requested.length<=200 &&
                requested!=g.context.packageName && requested!="com.android.shell" && !SemanticUiRules.protectedPackage(requested)) {"Use an installed Clock handler, excluding owner/permission surfaces"}
        }
        val intent=Intent(AlarmClock.ACTION_SET_TIMER).apply {requested?.let(::setPackage)}
        val selected=g.context.packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?: throw CapabilityException("CLOCK_APP_UNAVAILABLE","No enabled Clock app handles SET_TIMER for this package",noSideEffects=true)
        if(selected.packageName=="android" || selected.packageName==g.context.packageName || SemanticUiRules.protectedPackage(selected.packageName))
            throw CapabilityException("CLOCK_SELECTION_REQUIRED","Choose the exact installed Clock package; no system chooser or protected activity was opened",noSideEffects=true)
        if(!selected.enabled || !selected.exported) throw CapabilityException("CLOCK_APP_UNAVAILABLE","Clock timer activity is unavailable",noSideEffects=true)
        return selected.packageName
    }
    fun startAvailability(args:JsonObject?=null):Availability {
        if(!supportedUser()) return Availability.UNSUPPORTED
        val access=NotificationListenerAccess.availability(g.context)
        if(!access.isExecutable) return access
        if(!g.privileged.available()) return Availability.PRIVILEGE_REQUIRED
        return runCatching {packageName(args?.get("packageName")?.jsonPrimitive?.content);Availability.IMPLEMENTED}.getOrDefault(Availability.UNSUPPORTED)
    }
    private fun validate(ctx:ExecutionContext,args:JsonObject,descriptor:CapabilityDescriptor,availability:Availability,selected:BackendKind?=null) {
        val record=g.requests.get(ctx.requestId) ?: throw CapabilityException("REQUEST_NOT_FOUND","Original timer request is unavailable",noSideEffects=true)
        if(record.state!=RequestState.EXECUTING || record.agentId!=ctx.agentId || record.capabilityId!=descriptor.id || record.capabilityVersion!=descriptor.version ||
            (record.selectedBackend!=null && record.selectedBackend!=descriptor.backend) || record.argsHash!=Canonical.actionHash(descriptor.id,descriptor.version,args) ||
            System.currentTimeMillis()>=record.expiresAtMs || ctx.deadlineAtMs?.let {System.currentTimeMillis()>=it}==true)
            throw CapabilityException("CLOCK_REQUEST_CHANGED","Original timer request changed, expired or was cancelled before dispatch",noSideEffects=true)
        if(selected!=null && g.privileged.kind!=selected) throw CapabilityException("PRIVILEGE_REQUIRED","Selected timer backend changed; no timer command was sent",noSideEffects=true)
        val policy=PolicyEngine().evaluate(g.agents.grant(ctx.agentId),descriptor,args,record.assessment ?: RiskAssessment(descriptor.risk,descriptor.title),
            availability,g.settings.controls.value,Origin.valueOf(record.origin),System.currentTimeMillis())
        if(policy.outcome in setOf(PolicyOutcome.DENY,PolicyOutcome.UNAVAILABLE) || (policy.outcome==PolicyOutcome.REQUIRE_APPROVAL && record.approvedBy==null))
            throw CapabilityException("POLICY_DENIED",policy.reason,noSideEffects=true)
    }
    suspend fun start(args:JsonObject,ctx:ExecutionContext,descriptor:CapabilityDescriptor):CapabilityResult=withContext(Dispatchers.IO) {
        requireSupportedUser()
        val selected=descriptor.backend
        val pkg=packageName(args["packageName"]?.jsonPrimitive?.content)
        val seconds=args.getValue("durationSeconds").jsonPrimitive.int
        val label=args["label"]?.jsonPrimitive?.content.orEmpty()
        val id=UUID.randomUUID().toString();val published=ClockContract.publishedLabel(label,id)
        val argv=ClockContract.timerIntentArgv(pkg,seconds,published)
        val info=g.context.packageManager.getPackageInfo(pkg,0)
        val timer=ManagedTimer(id,ctx.agentId,ctx.requestId,pkg,requireNotNull(info.applicationInfo).uid,info.firstInstallTime,seconds,label,published,System.currentTimeMillis(),"PREPARED")
        g.ui.withScreen(ctx,pkg,args["leaseId"]?.jsonPrimitive?.content) {
            validate(ctx,args,descriptor,startAvailability(args),selected)
            notifications.requireNoExistingClockWork(pkg)
            if(journal.byStartRequest(ctx.requestId)!=null) throw CapabilityException("CLOCK_START_ALREADY_RECORDED","Original timer start already has durable identity; read timer status instead of replaying",noSideEffects=true)
            try {journal.prepare(timer)} catch(e:android.database.sqlite.SQLiteException) {throw CapabilityException("CLOCK_JOURNAL_UNAVAILABLE","Timer identity could not be saved; no timer command was sent",noSideEffects=true)}
            var attempted=false
            try {
                currentCoroutineContext().ensureActive();validate(ctx,args,descriptor,startAvailability(args),selected)
                notifications.requireNoExistingClockWork(pkg)
                journal.phase(ctx.requestId,"DISPATCHING");attempted=true
                val result=try {g.privileged.execute(ShellRequest(ctx.requestId,argv=argv,timeoutMs=5000,maxOutputBytes=4096,outputEncoding="text"),selected)}
                catch(error:CapabilityException) {if(error.noSideEffects) attempted=false;throw error}
                val accepted=result.exitCode==0 && !result.timedOut && !result.cancelled && result.error==null &&
                    !result.stdout.contains("Error:",ignoreCase=true) && !result.stderr.contains("Error:",ignoreCase=true)
                journal.phase(ctx.requestId,if(accepted) "ACCEPTED" else "OUTCOME_UNKNOWN")
                if(!accepted) return@withScreen CapabilityResult(timer.metadata(),selected,result.identity,VerificationStatus.FAILED,
                    "Clock intent was not confirmed; inspect this original managed timer before repeating",errorCode="CLOCK_START_UNCERTAIN")
                val observation=withTimeoutOrNull(2500) {
                    while(true) {notifications.observe(timer)?.let {return@withTimeoutOrNull it};delay(100)}
                    @Suppress("UNREACHABLE_CODE") error("No owned timer observed")
                }
                val output=buildJsonObject {
                    requireNotNull(journal.timer(id)).metadata().forEach {(k,v)->put(k,v)}
                    put("dispatchAccepted",true);put("skipUiRequested",true);put("physicalScreenUnchangedVerified",false)
                    if(observation==null) {put("notificationObserved",false);put("timerStartVerified",false);put("cancellationSupported",false);put("cancellationUnavailableCode","CLOCK_TIMER_NOT_OBSERVED")}
                    else observation.observation().forEach {(k,v)->put(k,v)}
                    put("doNotReplay",true)
                }
                CapabilityResult(output,selected,result.identity,VerificationStatus.NOT_VERIFIED,"SET_TIMER dispatch accepted; owned notification/countdown observations are reported separately")
            } catch(error:Exception) {
                runCatching {journal.phase(ctx.requestId,if(attempted) "OUTCOME_UNKNOWN" else "NOT_DISPATCHED")}
                if(!attempted && error !is CancellationException && error !is CapabilityException)
                    throw CapabilityException("CLOCK_DISPATCH_NOT_STARTED","Timer preparation failed; no timer command was sent",noSideEffects=true)
                if(attempted && error is CapabilityException && error.noSideEffects)
                    throw CapabilityException(error.code,"Timer dispatch may already have happened; readback failed: ${error.message}")
                throw error
            }
        }
    }
    fun status(args:JsonObject,ctx:ExecutionContext):JsonObject {
        requireSupportedUser()
        val requested=args["timerId"]?.jsonPrimitive?.content
        val limit=args["limit"]?.jsonPrimitive?.int ?: 20
        val rows=if(requested!=null) listOf(journal.owned(ctx.agentId,requested)) else journal.page(ctx.agentId,limit,args["beforeTimerId"]?.jsonPrimitive?.content)
        return buildJsonObject {
            put("timers",JsonArray(rows.map {timer->buildJsonObject {
                timer.metadata().forEach {(k,v)->put(k,v)};put("operations",JsonArray(journal.operations(timer.timerId)))
                try {
                    val observation=notifications.observe(timer)
                    if(observation==null) {put("notificationObserved",false);put("timerStartVerified",false);put("cancellationSupported",false);put("cancellationUnavailableCode","CLOCK_TIMER_NOT_OBSERVED")}
                    else observation.observation().forEach {(k,v)->put(k,v)}
                } catch(error:CapabilityException) {put("notificationObserved",false);put("timerStartVerified",false);put("cancellationSupported",false);put("observationUnavailableCode",error.code)}
                put("absenceProvesStopped",false)
            }}))
            put("nextBeforeTimerId",if(requested==null && rows.size==limit.coerceIn(1,50)) JsonPrimitive(rows.last().timerId) else JsonNull)
            put("onlyManagedTimersIncluded",true);put("allDeviceTimersEnumerated",false)
        }
    }
    suspend fun cancel(args:JsonObject,ctx:ExecutionContext,descriptor:CapabilityDescriptor):CapabilityResult=withContext(Dispatchers.IO) {
        requireSupportedUser()
        val timer=journal.owned(ctx.agentId,args.getValue("timerId").jsonPrimitive.content)
        g.ui.withScreen(ctx,timer.packageName,args["leaseId"]?.jsonPrimitive?.content) {
            currentCoroutineContext().ensureActive();validate(ctx,args,descriptor,NotificationListenerAccess.availability(g.context))
            val observed=notifications.observe(timer) ?: throw CapabilityException("CLOCK_TIMER_NOT_OBSERVED","No exact owned Clock notification is visible; no cancellation was sent",noSideEffects=true)
            notifications.resolveCancel(timer,observed)
            try {journal.prepareCancel(timer,ctx.requestId,System.currentTimeMillis())}
            catch(e:android.database.sqlite.SQLiteException) {throw CapabilityException("CLOCK_JOURNAL_UNAVAILABLE","Cancellation identity could not be saved; no action was sent",noSideEffects=true)}
            var attempted=false
            try {
                currentCoroutineContext().ensureActive();validate(ctx,args,descriptor,NotificationListenerAccess.availability(g.context))
                val action=notifications.resolveCancel(timer,observed)
                journal.phase(ctx.requestId,"DISPATCHING");attempted=true
                try {requireNotNull(action.selected.action.actionIntent).send()}
                catch(_:PendingIntent.CanceledException) {attempted=false;throw CapabilityException("ACTION_CANCELLED","Clock withdrew this timer action; no new cancellation was sent",noSideEffects=true)}
                journal.phase(ctx.requestId,"ACCEPTED")
                val removed=withTimeoutOrNull(2000) {
                    while(true) {if(notifications.observe(timer)==null) return@withTimeoutOrNull true;delay(100)}
                    @Suppress("UNREACHABLE_CODE") false
                } ?: false
                CapabilityResult(buildJsonObject {
                    timer.metadata().forEach {(k,v)->put(k,v)};put("cancelRequestId",ctx.requestId);put("dispatchAccepted",true)
                    put("ownedNotificationRemovedObserved",removed);put("timerStoppedIndependentlyVerified",false);put("doNotReplay",true)
                },BackendKind.NATIVE,"uid=${android.os.Process.myUid()}; Clock action creator uid=${timer.uid}",VerificationStatus.NOT_VERIFIED,
                    "Exact Clock-published action dispatched; disappearance of its owned notification is a bounded observation, not independent timer-store verification")
            } catch(error:Exception) {
                runCatching {journal.phase(ctx.requestId,if(attempted) "OUTCOME_UNKNOWN" else "NOT_DISPATCHED")}
                if(!attempted && error !is CancellationException && error !is CapabilityException)
                    throw CapabilityException("CLOCK_DISPATCH_NOT_STARTED","Cancellation preparation failed; no action was sent",noSideEffects=true)
                if(attempted && error is CapabilityException && error.noSideEffects)
                    throw CapabilityException(error.code,"Timer cancellation may already have happened; readback failed: ${error.message}")
                throw error
            }
        }
    }
}
