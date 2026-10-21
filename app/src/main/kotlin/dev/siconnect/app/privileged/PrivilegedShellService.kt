package dev.siconnect.app.privileged

import dev.siconnect.app.backend.*
import kotlinx.serialization.json.Json
import android.os.ParcelFileDescriptor
import java.util.concurrent.Semaphore

/** Instantiated by Shizuku in its own shell/root process, never by Android's app ServiceManager. */
class PrivilegedShellService(private val authorizedUid: Int? = null) : IPrivilegedShell.Stub() {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val streams=Semaphore(8)
    private val actualIdentity = ShellRunner.identity()
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
    override fun cancel(execId: String): Boolean { authorize(); return ShellRunner.cancel(execId) }
    override fun destroy() { authorize(); ShellRunner.cancelAll(); kotlin.system.exitProcess(0) }
}
