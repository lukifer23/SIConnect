package dev.siconnect.app.privileged

import dev.siconnect.app.backend.*
import kotlinx.serialization.json.Json
import android.os.ParcelFileDescriptor
import java.util.concurrent.Semaphore
import kotlinx.serialization.json.*

/** Instantiated by Shizuku in its own shell/root process, never by Android's app ServiceManager. */
class PrivilegedShellService(private val authorizedUid: Int? = null) : IPrivilegedShell.Stub() {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val streams=Semaphore(8)
    private val actualIdentity = ShellRunner.identity()
    private val workspaceHolder=lazy {HeadlessWorkspace(actualIdentity)}
    private val workspaces get()=workspaceHolder.value
    private class WorkspaceOwner(val token:android.os.IBinder,val pid:Int) {
        val id=java.util.UUID.randomUUID().toString()
        val alive=java.util.concurrent.atomic.AtomicBoolean(true)
        lateinit var death:android.os.IBinder.DeathRecipient
        fun usable()=alive.get() && token.isBinderAlive
    }
    private val workspaceOwners=java.util.concurrent.ConcurrentHashMap<Int,WorkspaceOwner>()
    private val workspaceOwnerGuard=Any()
    private val destroying=java.util.concurrent.atomic.AtomicBoolean()
    private val workspaceCleanup=java.util.concurrent.Executors.newSingleThreadExecutor {task->
        Thread(task,"siconnect-private-owner-cleanup").apply {isDaemon=true}
    }
    private fun retireOwner(owner:WorkspaceOwner) {
        if(!owner.alive.compareAndSet(true,false)) return
        workspaceOwners.remove(owner.pid,owner)
        workspaceCleanup.execute {synchronized(this) {
            if(workspaceHolder.isInitialized()) runCatching {workspaces.closeAll(owner.id)}.onFailure {
                android.util.Log.e("SIConnectWorkspace","Dead caller cleanup pending; maintenance will retry")
            }
        }}
    }
    private fun workspaceOwner(token:android.os.IBinder):WorkspaceOwner=synchronized(workspaceOwnerGuard) {
        check(!destroying.get()) {"Privileged backend is stopping"}
        val pid=android.os.Binder.getCallingPid()
        workspaceOwners[pid]?.let {previous->
            if(previous.usable()) return@synchronized previous
            retireOwner(previous)
        }
        check(workspaceOwners.size<8) {"Workspace caller capacity reached"}
        val owner=WorkspaceOwner(token,pid)
        owner.death=android.os.IBinder.DeathRecipient {retireOwner(owner)}
        workspaceOwners[pid]=owner
        try {
            token.linkToDeath(owner.death,0)
            if(!owner.usable()) throw android.os.DeadObjectException()
        } catch(e:Exception) {retireOwner(owner);throw e}
        owner
    }
    private class WorkspaceAdmission(val deadline:Long) {
        // QUEUED -> RUNNING or CANCELLED. Cancellation never waits on the workspace lock.
        val state=java.util.concurrent.atomic.AtomicInteger(0)
    }
    private val workspaceAdmissions=java.util.concurrent.ConcurrentHashMap<String,WorkspaceAdmission>()
    private fun authorize() { authorizedUid?.let { check(android.os.Binder.getCallingUid() == it) { "Caller does not own this service" } } }
    override fun identity(): String { authorize(); return actualIdentity }
    override fun exec(requestJson: String): String {
        authorize()
        require(requestJson.length <= 128_000) { "Command envelope too large" }
        return execute(requestJson,legacy=true)
    }
    private fun execute(requestJson:String,legacy:Boolean=false):String {
        val request=json.decodeFromString(ShellRequest.serializer(),requestJson)
        // Old clients still receive real results, explicitly truncated within a compact
        // Binder-safe reply. The stream contract preserves the full 128 KiB capture limit.
        val captured=if(legacy) request.copy(maxOutputBytes=minOf(request.maxOutputBytes,32768)) else request
        val budget=if(legacy) 192*1024 else ShellOutput.MAX_RESULT_BYTES
        val encoded=json.encodeToString(ShellResult.serializer(),ShellRunner.run(captured,actualIdentity,budget))
        check(encoded.toByteArray(Charsets.UTF_8).size<=ShellOutput.MAX_RESULT_BYTES) {"Shell result budget violated"}
        return encoded
    }
    override fun execStream(requestJson:String):ParcelFileDescriptor {
        authorize()
        require(requestJson.length<=128_000 && requestJson.toByteArray(Charsets.UTF_8).size<=131_072) {"Command envelope too large"}
        // Admission occurs before creating a pipe/thread, including callers that abandon a reply.
        check(streams.tryAcquire()) {"Shell stream capacity reached"}
        val pipe=try {ParcelFileDescriptor.createReliablePipe()} catch(e:Exception) {streams.release();throw e}
        try {
            Thread({
                try {
                    val result=execute(requestJson).toByteArray(Charsets.UTF_8)
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {it.write(result)}
                } catch(_:Exception) {
                    runCatching {pipe[1].closeWithError("SHELL_RESULT_LOST")}
                    runCatching {pipe[1].close()}
                } // Reliable reader observes failure/EOF; never fabricate success.
                finally {streams.release()}
            },"siconnect-shell-result").apply {isDaemon=true;start()}
            return pipe[0]
        } catch(e:Exception) {pipe.forEach {runCatching {it.close()}};streams.release();throw e}
    }
    override fun cancel(execId:String):Boolean {authorize();return ShellRunner.cancel(execId)}
    override fun cancelWorkspace(execId:String):Boolean {
        authorize()
        return workspaceAdmissions[execId]?.state?.compareAndSet(0,2)==true
    }
    override fun workspaceStream(requestJson:String,ownerToken:android.os.IBinder):ParcelFileDescriptor {
        authorize()
        require(requestJson.toByteArray(Charsets.UTF_8).size<=16_384) {"Workspace request exceeds its limit"}
        val request=json.parseToJsonElement(requestJson).jsonObject
        val execId=request.getValue("execId").jsonPrimitive.content
        require(execId.matches(Regex("workspace-[a-f0-9-]{36}"))) {"Invalid workspace dispatch identity"}
        val owner=workspaceOwner(ownerToken)
        val admission=WorkspaceAdmission(minOf(request.getValue("deadlineAtMs").jsonPrimitive.long,System.currentTimeMillis()+30_000))
        check(streams.tryAcquire()) {"Privileged stream capacity reached"}
        if(workspaceAdmissions.putIfAbsent(execId,admission)!=null) {streams.release();error("Duplicate workspace dispatch identity")}
        val pipe=try {ParcelFileDescriptor.createReliablePipe()} catch(e:Exception) {workspaceAdmissions.remove(execId,admission);streams.release();throw e}
        try {
            Thread({
                try {
                    val result=try {executeWorkspace(request,owner,admission)} catch(error:Exception) {
                        val cause=(error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
                        if(dev.siconnect.app.BuildConfig.DEBUG && cause !is WorkspaceFailure)
                            android.util.Log.e("SIConnectWorkspace",cause.javaClass.simpleName+"\n"+cause.stackTrace.joinToString("\n"))
                        buildJsonObject {
                            put("errorCode",(cause as? WorkspaceFailure)?.code ?: "WORKSPACE_BACKEND_FAILED")
                            put("message",(cause as? WorkspaceFailure)?.message ?: "Private workspace backend failed (${cause.javaClass.simpleName}); inspect the original request before retrying")
                            put("noSideEffects",(cause as? WorkspaceFailure)?.noSideEffects ?: false)
                        }
                    }
                    val bytes=buildJsonObject {result.forEach {(k,v)->put(k,v)};put("executionIdentity",actualIdentity);put("workspaceCallerPid",owner.pid)}.toString().toByteArray(Charsets.UTF_8)
                    check(bytes.size<=ShellOutput.MAX_RESULT_BYTES) {"Workspace reply exceeds its limit"}
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {it.write(bytes)}
                } catch(_:Exception) {runCatching {pipe[1].closeWithError("WORKSPACE_RESULT_LOST")};runCatching {pipe[1].close()}}
                finally {workspaceAdmissions.remove(execId,admission);streams.release()}
            },"siconnect-private-workspace-result").apply {isDaemon=true;start()}
            return pipe[0]
        } catch(e:Exception) {pipe.forEach {runCatching {it.close()}};workspaceAdmissions.remove(execId,admission);streams.release();throw e}
    }
    @Synchronized private fun executeWorkspace(request:JsonObject,owner:WorkspaceOwner,admission:WorkspaceAdmission):JsonObject {
        if(!owner.usable()) throw WorkspaceFailure("WORKSPACE_OWNER_GONE","Calling app process ended before dispatch",true)
        if(System.currentTimeMillis()>=admission.deadline) throw WorkspaceFailure("WORKSPACE_DEADLINE","Workspace dispatch expired in the queue",true)
        if(!admission.state.compareAndSet(0,1)) throw WorkspaceFailure("WORKSPACE_CANCELLED","Workspace dispatch cancelled before it started",true)
        try {return workspaces.execute(request,owner.id)} finally {
            // A running launch can finish after caller death. Close its actual resources
            // before another queued call enters, even if the death callback is still queued.
            if(!owner.usable()) runCatching {workspaces.closeAll(owner.id)}
        }
    }
    fun closeWorkspaces() {
        destroying.set(true)
        synchronized(workspaceOwnerGuard) {workspaceOwners.values.toList().forEach(::retireOwner)}
        synchronized(this) {if(workspaceHolder.isInitialized()) runCatching {workspaces.closeAll()}.onFailure {
            android.util.Log.e("SIConnectWorkspace","Backend teardown cleanup pending")
        }}
    }
    override fun destroy() { authorize();closeWorkspaces();ShellRunner.cancelAll(); kotlin.system.exitProcess(0) }
}
