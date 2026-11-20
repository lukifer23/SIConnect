package dev.siconnect.app.clocks

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.accessibility.SemanticUiRules
import android.content.ActivityNotFoundException
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
        return runCatching {packageName(args?.get("packageName")?.jsonPrimitive?.content);Availability.IMPLEMENTED}.getOrDefault(Availability.UNSUPPORTED)
    }
    private fun validate(ctx:ExecutionContext,args:JsonObject,descriptor:CapabilityDescriptor,availability:Availability) {
        val record=g.requests.get(ctx.requestId) ?: throw CapabilityException("REQUEST_NOT_FOUND","Original timer request is unavailable",noSideEffects=true)
        if(record.state!=RequestState.EXECUTING || record.agentId!=ctx.agentId || record.capabilityId!=descriptor.id || record.capabilityVersion!=descriptor.version ||
            (record.selectedBackend!=null && record.selectedBackend!=descriptor.backend) || record.argsHash!=Canonical.actionHash(descriptor.id,descriptor.version,args) ||
            System.currentTimeMillis()>=record.expiresAtMs || ctx.deadlineAtMs?.let {System.currentTimeMillis()>=it}==true)
            throw CapabilityException("CLOCK_REQUEST_CHANGED","Original timer request changed, expired or was cancelled before dispatch",noSideEffects=true)
        val policy=PolicyEngine().evaluate(g.agents.grant(ctx.agentId),descriptor,args,record.assessment ?: RiskAssessment(descriptor.risk,descriptor.title),
            availability,g.settings.controls.value,Origin.valueOf(record.origin),System.currentTimeMillis())
        if(policy.outcome in setOf(PolicyOutcome.DENY,PolicyOutcome.UNAVAILABLE) || (policy.outcome==PolicyOutcome.REQUIRE_APPROVAL && record.approvedBy==null))
            throw CapabilityException("POLICY_DENIED",policy.reason,noSideEffects=true)
    }
    suspend fun start(args:JsonObject,ctx:ExecutionContext,descriptor:CapabilityDescriptor):CapabilityResult=withContext(Dispatchers.IO) {
        requireSupportedUser()
        val pkg=packageName(args["packageName"]?.jsonPrimitive?.content)
        val seconds=args.getValue("durationSeconds").jsonPrimitive.int
        val label=args["label"]?.jsonPrimitive?.content.orEmpty()
        val id=UUID.randomUUID().toString();val published=ClockContract.publishedLabel(label,id)
        ClockContract.validateTimer(pkg,seconds,published)
        val intent=Intent(AlarmClock.ACTION_SET_TIMER).setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AlarmClock.EXTRA_LENGTH,seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE,published)
            .putExtra(AlarmClock.EXTRA_SKIP_UI,true)
        val info=g.context.packageManager.getPackageInfo(pkg,0)
        val timer=ManagedTimer(id,ctx.agentId,ctx.requestId,pkg,requireNotNull(info.applicationInfo).uid,info.firstInstallTime,seconds,label,published,System.currentTimeMillis(),"PREPARED")
        g.ui.withScreen(ctx,pkg,args["leaseId"]?.jsonPrimitive?.content) {
            validate(ctx,args,descriptor,startAvailability(args))
            notifications.requireNoExistingClockWork(pkg)
            if(journal.byStartRequest(ctx.requestId)!=null) throw CapabilityException("CLOCK_START_ALREADY_RECORDED","Original timer start already has durable identity; read timer status instead of replaying",noSideEffects=true)
            try {journal.prepare(timer)} catch(e:android.database.sqlite.SQLiteException) {throw CapabilityException("CLOCK_JOURNAL_UNAVAILABLE","Timer identity could not be saved; no timer command was sent",noSideEffects=true)}
            var attempted=false
            try {
                currentCoroutineContext().ensureActive();validate(ctx,args,descriptor,startAvailability(args))
                notifications.requireNoExistingClockWork(pkg)
                if (!g.ownerScreenVisible) throw CapabilityException("CLOCK_FOREGROUND_REQUIRED",
                    "Keep SIConnect open to start the external Clock app, or use reminders.start_countdown for a background timer", noSideEffects=true)
                journal.phase(ctx.requestId,"DISPATCHING");attempted=true
                try {
                    g.context.startActivity(intent)
                } catch (error: ActivityNotFoundException) {
                    attempted=false
                    throw CapabilityException("CLOCK_APP_UNAVAILABLE","Clock no longer handles the timer request",noSideEffects=true)
                } catch (error: SecurityException) {
                    attempted=false
                    throw CapabilityException("CLOCK_PERMISSION_REQUIRED","Android refused the Clock timer intent",noSideEffects=true)
                }
                val observation=withTimeoutOrNull(2500) {
                    while(true) {notifications.observe(timer)?.let {return@withTimeoutOrNull it};delay(100)}
                    @Suppress("UNREACHABLE_CODE") error("No owned timer observed")
                }
                // Android can silently block background activity starts. A normal return alone is inconclusive.
                journal.phase(ctx.requestId,if(observation!=null) "ACCEPTED" else "OUTCOME_UNKNOWN")
                val output=buildJsonObject {
                    requireNotNull(journal.timer(id)).metadata().forEach {(k,v)->put(k,v)}
                    put("dispatchAccepted",observation!=null);put("skipUiRequested",true);put("physicalScreenUnchangedVerified",false)
                    if(observation==null) {put("notificationObserved",false);put("timerStartVerified",false);put("cancellationSupported",false);put("cancellationUnavailableCode","CLOCK_TIMER_NOT_OBSERVED")}
                    else observation.observation().forEach {(k,v)->put(k,v)}
                    put("doNotReplay",true)
                }
                CapabilityResult(output,BackendKind.NATIVE,"uid=${android.os.Process.myUid()}",
                    if(observation==null) VerificationStatus.FAILED else VerificationStatus.NOT_VERIFIED,
                    if(observation!=null) "Clock published the matching timer label; countdown evidence is reported separately"
                    else "Clock did not publish the matching timer notification in time. Check the original timer ID before starting another.",
                    errorCode=if(observation==null) "CLOCK_START_UNCERTAIN" else null)
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
        val beforeId=args["beforeTimerId"]?.jsonPrimitive?.content
        fun countdown(id:String)=g.reminders.ownedCountdown(ctx.agentId,id)
        val beforeMs=if(requested!=null || beforeId==null) null else if(beforeId.startsWith("countdown:")) {
            val r=countdown(beforeId)
            r.fireAtMs-requireNotNull(r.countdownSeconds)*1000L
        } else journal.owned(ctx.agentId,beforeId).createdAtMs
        val external=when {
            requested==null -> journal.pageBefore(ctx.agentId,limit,beforeMs,beforeId)
            requested.startsWith("countdown:") -> emptyList()
            else -> listOf(journal.owned(ctx.agentId,requested))
        }
        val local=when {
            requested==null -> g.reminders.countdownPage(ctx.agentId,limit,beforeMs,beforeId)
            requested.startsWith("countdown:") -> listOf(countdown(requested))
            else -> emptyList()
        }
        val rows=external.map {timer->buildJsonObject {
                timer.metadata().forEach {(k,v)->put(k,v)};put("operations",JsonArray(journal.operations(timer.timerId)))
                try {
                    val observation=notifications.observe(timer)
                    if(observation==null) {put("notificationObserved",false);put("timerStartVerified",false);put("cancellationSupported",false);put("cancellationUnavailableCode","CLOCK_TIMER_NOT_OBSERVED")}
                    else observation.observation().forEach {(k,v)->put(k,v)}
                } catch(error:CapabilityException) {put("notificationObserved",false);put("timerStartVerified",false);put("cancellationSupported",false);put("observationUnavailableCode",error.code)}
                put("absenceProvesStopped",false)
            } } + local.map(g.reminders::countdownMetadata)
        val page=rows.sortedWith(compareByDescending<JsonObject> {it.getValue("createdAtMs").jsonPrimitive.long}
            .thenByDescending {it.getValue("timerId").jsonPrimitive.content}).take(limit)
        return buildJsonObject {
            put("timers",JsonArray(page))
            put("nextBeforeTimerId",if(requested==null && page.size==limit) page.last().getValue("timerId") else JsonNull)
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
                var observationError:String?=null
                val removed=try { withTimeoutOrNull(2000) {
                    while(true) {if(notifications.observe(timer)==null) return@withTimeoutOrNull true;delay(100)}
                    @Suppress("UNREACHABLE_CODE") false
                } ?: false } catch(error:CapabilityException) {
                    observationError=error.code
                    false
                }
                CapabilityResult(buildJsonObject {
                    timer.metadata().forEach {(k,v)->put(k,v)};put("cancelRequestId",ctx.requestId);put("dispatchAccepted",true)
                    put("ownedNotificationRemovedObserved",removed);observationError?.let {put("observationUnavailableCode",it)};put("timerStoppedIndependentlyVerified",false);put("doNotReplay",true)
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
