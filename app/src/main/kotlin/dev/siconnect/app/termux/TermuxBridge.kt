package dev.siconnect.app.termux

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Native IPC to Termux's installed app UID, never privileged shell or shared-screen typing. */
class TermuxBridge private constructor(private val context:Context) {
    private val journal by lazy {TermuxJournal(context).also {it.recoverOrphans()}}
    private val waiters=ConcurrentHashMap<String,CompletableDeferred<Unit>>()
    private val permits=Semaphore(4)
    private val invalidation=MutableStateFlow(0L)
    val updates:StateFlow<Long> = invalidation.asStateFlow()
    private fun changed() {synchronized(invalidation) {invalidation.value=invalidation.value+1}}

    /** Package and permission observations only; this function never starts Termux. Call on IO. */
    fun status(agentId:String?=null):TermuxStatus {
        val pm=context.packageManager
        val pkg=try {pm.getPackageInfo(TermuxContract.PACKAGE,0)} catch(_:PackageManager.NameNotFoundException) {null}
        val app=pkg?.applicationInfo
        val service=try {pm.getServiceInfo(ComponentName(TermuxContract.PACKAGE,TermuxContract.SERVICE),0)}
            catch(_:PackageManager.NameNotFoundException) {null}
        return TermuxStatus(pkg!=null,app?.enabled==true,
            service?.enabled==true && service.exported && service.permission==TermuxContract.PERMISSION,
            pkg?.versionName,pkg?.longVersionCode,app?.uid,
            context.checkSelfPermission(TermuxContract.PERMISSION)==PackageManager.PERMISSION_GRANTED,
            if(app==null || pkg==null) "UNKNOWN" else journal.lastPolicyState(app.uid,pkg.longVersionCode,pkg.firstInstallTime),
            if(app==null || pkg==null) null else journal.lastCallbackAtMs(app.uid,pkg.longVersionCode,pkg.firstInstallTime,agentId),
            if(app==null || pkg==null) null else journal.lastSuccessAtMs(app.uid,pkg.longVersionCode,pkg.firstInstallTime,agentId),
            if(app==null || pkg==null) null else journal.lastError(app.uid,pkg.longVersionCode,pkg.firstInstallTime,agentId),journal.pendingCount(agentId),
            TermuxContract.supportsResultApi(pkg?.versionName),pkg?.firstInstallTime)
    }
    internal fun availability():Availability=availability(status())
    internal fun result(agentId:String,requestId:String):JsonObject {
        val entry=journal.entry(requestId)?.takeIf {it.agentId==agentId}
            ?: throw CapabilityException("TERMUX_RESULT_NOT_FOUND","No retained Termux execution belongs to this agent and request ID",noSideEffects=true)
        return entry.output()
    }
    internal fun recent(agentId:String,limit:Int=20,unresolvedOnly:Boolean=false,beforeExecutionId:String?=null):JsonObject=
        journal.recent(agentId,limit,unresolvedOnly,beforeExecutionId)
    /** Owner-opted retention only; unresolved/UNKNOWN core outcomes must return false. Call on IO. */
    fun compactPayloads(cutoff:Long,limit:Int,canCompact:(String)->Boolean):Int =
        journal.compactPayloads(cutoff,limit,canCompact).also {if(it>0) changed()}
    internal suspend fun execute(args:JsonObject,ctx:ExecutionContext,validateDispatch:()->Unit):JsonObject=withContext(Dispatchers.IO) {
        val command=try {TermuxCommand.parse(args)} catch(e:IllegalArgumentException) {
            throw CapabilityException("INVALID_ARGUMENT",e.message ?: "Invalid Termux command",noSideEffects=true)
        }
        permits.withPermit {
            currentCoroutineContext().ensureActive()
            validateDispatch()
            val state=status()
            if(!availability(state).isExecutable) throw CapabilityException("TERMUX_NOT_AVAILABLE",
                "Install and enable Termux >=0.109 and grant SIConnect Run commands in Termux permission",noSideEffects=true)
            val now=System.currentTimeMillis()
            val deadline=minOf(ctx.deadlineAtMs ?: now+command.timeoutMs,now+command.timeoutMs)
            if(deadline<=now) throw CapabilityException("EXPIRED","Termux dispatch deadline expired",noSideEffects=true)
            val nonce=ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") {"%02x".format(it)}
            val nonceHash=Canonical.sha256Hex(nonce)
            if(journal.entry(ctx.requestId)!=null) throw CapabilityException("TERMUX_REQUEST_ALREADY_DISPATCHED",
                "This durable request already has a Termux dispatch record; inspect its result instead of replaying",noSideEffects=true)
            val pkg=context.packageManager.getPackageInfo(TermuxContract.PACKAGE,0)
            if(pkg.applicationInfo?.uid!=state.uid || pkg.longVersionCode!=state.versionCode || pkg.firstInstallTime!=state.firstInstallAtMs) throw CapabilityException("TERMUX_INSTALL_CHANGED","Termux installation changed before dispatch; inspect status again",noSideEffects=true)
            try {journal.prepare(ctx.requestId,ctx.agentId,nonceHash,requireNotNull(state.uid),pkg.longVersionCode,pkg.firstInstallTime,now,deadline,command.maxOutputBytes)}
            catch(e:android.database.sqlite.SQLiteException) {throw CapabilityException("TERMUX_JOURNAL_UNAVAILABLE","Could not durably prepare Termux dispatch; no command was sent",noSideEffects=true)}
            val callbackIntent=Intent(context,TermuxResultReceiver::class.java).apply {
                action=TermuxContract.RESULT_ACTION;setPackage(context.packageName)
                data=Uri.Builder().scheme("siconnect-termux").authority("result").appendPath(ctx.requestId).appendPath(nonce).build()
            }
            // MUTABLE is required because Termux adds its result Bundle with send(fillInIntent).
            // Routing/identity are fixed; only result extras are accepted by the nonexported receiver.
            val callback=try {PendingIntent.getBroadcast(context,0,callbackIntent,PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)}
            catch(e:Exception) {
                runCatching {journal.notDispatched(ctx.requestId)}
                throw CapabilityException("TERMUX_CALLBACK_UNAVAILABLE","Could not create the result callback; no command was sent",noSideEffects=true)
            }
            val completion=CompletableDeferred<Unit>();waiters[ctx.requestId]=completion
            var dispatchAttempted=false
            try {
                currentCoroutineContext().ensureActive()
                val intent=Intent(TermuxContract.ACTION).apply {
                    component=ComponentName(TermuxContract.PACKAGE,TermuxContract.SERVICE)
                    setPackage(TermuxContract.PACKAGE)
                    putExtra("com.termux.RUN_COMMAND_PATH",command.executable)
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS",command.arguments.toTypedArray())
                    command.cwd?.let {putExtra("com.termux.RUN_COMMAND_WORKDIR",it)}
                    command.stdin?.let {putExtra("com.termux.RUN_COMMAND_STDIN",it)}
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND",true)
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND_CUSTOM_LOG_LEVEL",0)
                    putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL","SIConnect ${ctx.requestId}")
                    putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",callback)
                }
                currentCoroutineContext().ensureActive()
                if(System.currentTimeMillis()>=deadline) throw CapabilityException("EXPIRED","Termux deadline expired before service dispatch; no command was sent",noSideEffects=true)
                validateDispatch()
                journal.markDispatch(ctx.requestId,System.currentTimeMillis())
                dispatchAttempted=true
                try {
                    if(context.startForegroundService(intent)==null) {
                        journal.notDispatched(ctx.requestId);dispatchAttempted=false;callback.cancel()
                        throw CapabilityException("TERMUX_SERVICE_UNAVAILABLE","Android did not resolve Termux's RUN_COMMAND service",noSideEffects=true)
                    }
                } catch(e:SecurityException) {
                    journal.notDispatched(ctx.requestId);dispatchAttempted=false;callback.cancel()
                    throw CapabilityException("TERMUX_PERMISSION_REQUIRED","Android refused the Termux service start: grant Run commands in Termux and allow background execution",noSideEffects=true)
                } catch(e:IllegalStateException) {
                    journal.notDispatched(ctx.requestId);dispatchAttempted=false;callback.cancel()
                    throw CapabilityException("TERMUX_BACKGROUND_START_RESTRICTED","Android refused to start Termux from the current background state",noSideEffects=true)
                }
                changed()
                val remaining=(deadline-System.currentTimeMillis()).coerceAtLeast(1)
                try {withTimeout(remaining) {completion.await()}}
                catch(e:TimeoutCancellationException) {
                    // The service API has no command cancellation contract. Keep the
                    // one-shot callback alive so actual late completion can be retained.
                    journal.waiterEnded(ctx.requestId,"TIMED_OUT");changed()
                    throw CapabilityException("TERMUX_OUTCOME_PENDING","Termux result was not received before the wait deadline; the command may still run. Read termux.result with the same execution ID; never replay automatically")
                }
                requireNotNull(journal.entry(ctx.requestId)).output()
            } catch(e:CancellationException) {
                if(dispatchAttempted) journal.waiterEnded(ctx.requestId,"CANCELLED") else {journal.notDispatched(ctx.requestId);callback.cancel()}
                changed();throw e
            } catch(e:Exception) {
                if(dispatchAttempted) journal.waiterEnded(ctx.requestId,"FAILED") else {runCatching {journal.notDispatched(ctx.requestId)};callback.cancel()}
                changed()
                if(!dispatchAttempted && e !is CapabilityException) throw CapabilityException("TERMUX_DISPATCH_NOT_STARTED","Termux dispatch preparation failed; no command was sent",noSideEffects=true)
                throw e
            } finally {waiters.remove(ctx.requestId,completion)}
        }
    }

    /** Only the explicit one-shot PendingIntent can enter this nonexported receiver. */
    internal fun receive(intent:Intent) {
        if(intent.action!=TermuxContract.RESULT_ACTION || intent.component!=ComponentName(context,TermuxResultReceiver::class.java) || intent.`package`!=context.packageName) return
        val uri=intent.data ?: return
        if(uri.scheme!="siconnect-termux" || uri.authority!="result" || uri.pathSegments.size!=2 || uri.query!=null || uri.fragment!=null) return
        val id=uri.pathSegments[0];val nonce=uri.pathSegments[1]
        if(nonce.length!=64 || !nonce.all {it in '0'..'9' || it in 'a'..'f'}) return
        val entry=journal.entry(id) ?: return
        if(entry.phase!="DISPATCHING" || !MessageDigest.isEqual(entry.nonceHash.toByteArray(),Canonical.sha256Hex(nonce).toByteArray())) return
        val bundle=intent.getBundleExtra("result") ?: return
        val parsed=parseResult(bundle,entry.maxOutputBytes) ?: return
        // Commit first. A process-death callback never resurrects/re-executes a core request.
        if(journal.receive(id,entry.nonceHash,parsed,System.currentTimeMillis())) {
            changed();waiters[id]?.complete(Unit)
        }
    }
    private fun parseResult(bundle:Bundle,maxBytes:Int):JsonObject? {
        fun string(key:String):String?=bundle.get(key) as? String
        val err=bundle.get("err") as? Int ?: return null
        val exit=bundle.get("exitCode") as? Int
        if(!TermuxContract.completeCallbackStatus(err,exit)) return null
        val stdout=string("stdout").orEmpty();val stderr=string("stderr").orEmpty()
        val out=boundedTermuxText(stdout,maxBytes);val error=boundedTermuxText(stderr,maxBytes)
        val outOriginal=string("stdout_original_length")?.toLongOrNull()?.coerceAtLeast(stdout.length.toLong()) ?: stdout.length.toLong()
        val errOriginal=string("stderr_original_length")?.toLongOrNull()?.coerceAtLeast(stderr.length.toLong()) ?: stderr.length.toLong()
        val message=string("errmsg")?.let {boundedTermuxText(it,4096).text}
        return buildJsonObject {
            put("stdout",out.text);put("stderr",error.text);put("stdoutCapturedBytes",out.bytes);put("stderrCapturedBytes",error.bytes)
            put("stdoutOriginalChars",outOriginal);put("stderrOriginalChars",errOriginal)
            put("stdoutTruncated",out.truncated || outOriginal>stdout.length);put("stderrTruncated",error.truncated || errOriginal>stderr.length)
            put("exitCode",exit?.let(::JsonPrimitive) ?: JsonNull);put("executionErrorCode",err)
            put("executionErrorMessage",message?.let(::JsonPrimitive) ?: JsonNull);put("commandSucceeded",TermuxContract.commandSucceeded(err,exit))
            put("externalOutcomeVerified",false)
        }
    }
    companion object {
        const val RUN_COMMAND_PERMISSION="com.termux.permission.RUN_COMMAND"
        @Volatile private var instance:TermuxBridge?=null
        fun get(context:Context):TermuxBridge=instance ?: synchronized(this) {
            instance ?: TermuxBridge(context.applicationContext).also {instance=it}
        }
        internal fun availability(s:TermuxStatus):Availability=when {
            !s.installed->Availability.UNSUPPORTED
            !s.appEnabled->Availability.TEMPORARILY_UNAVAILABLE
            !s.serviceAvailable || s.resultApiSupported==false->Availability.UNSUPPORTED
            !s.runCommandPermissionGranted->Availability.PERMISSION_REQUIRED
            s.allowExternalAppsState!="CONFIRMED_ALLOWED"->Availability.DEGRADED
            else->Availability.IMPLEMENTED
        }
    }
}

/** The PendingIntent is the sole external authority; no exported broadcast or service entry point. */
class TermuxResultReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {TermuxBridge.get(context).receive(intent)}
            catch(_:Exception) {android.util.Log.w("SIConnect","Termux callback could not be retained; outcome remains uncertain")}
            finally {pending.finish()}
        }
    }
}
