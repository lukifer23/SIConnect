package dev.siconnect.app.backend

import android.system.Os
import android.system.OsConstants
import kotlinx.serialization.Serializable
import java.io.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class ShellRequest(val execId: String, val argv: List<String>? = null, val command: String? = null,
    val cwd: String? = null, val env: Map<String, String> = emptyMap(), val stdin: String? = null,
    val timeoutMs: Long = 30_000, val maxOutputBytes: Int = 128 * 1024)

@Serializable
data class ShellResult(val execId: String, val exitCode: Int?, val stdout: String, val stderr: String,
    val stdoutTruncated: Boolean, val stderrTruncated: Boolean, val timedOut: Boolean, val cancelled: Boolean,
    val durationMs: Long, val identity: String, val error: String? = null,
    val stdoutBase64: String = "", val stderrBase64: String = "")

/** Each execution holds its session leader alive until its entire process group is cleaned up. */
object ShellRunner {
    private class Owned {
        val cancelled=AtomicBoolean(false)
        private var process:Process?=null
        private var group:Int?=null
        private var stopped=false
        @Synchronized fun attach(p:Process):Boolean {
            if(stopped) {p.destroyForcibly();return false}
            process=p;return true
        }
        @Synchronized fun claim(pid:Int):Boolean {
            if(stopped || process?.isAlive!=true) return false
            group=pid;return true
        }
        @Synchronized fun releaseGate(nonce:String,stdin:String?):Thread? {
            if(stopped || cancelled.get()) return null
            val p=requireNotNull(process)
            return Thread({runCatching { p.outputStream.use { stream ->
                stream.write((nonce+"\n").toByteArray());stream.flush()
                stdin?.let {stream.write(it.toByteArray())}
            } }},"siconnect-shell-input").apply {isDaemon=true;start()}
        }
        @Synchronized fun stop(graceful:Boolean) {
            if(stopped) return
            stopped=true
            val p=process ?: return
            // The supervisor never voluntarily exits after releasing user code. If it died
            // unexpectedly, do not signal a numeric group that could have been recycled.
            if(p.isAlive) {
                val pid=group
                if(pid!=null) {
                    if(graceful) {
                        runCatching {Os.kill(-pid,OsConstants.SIGTERM)}
                        Thread.sleep(100)
                    }
                    if(p.isAlive) runCatching {Os.kill(-pid,OsConstants.SIGKILL)}
                }
                if(p.isAlive) p.destroyForcibly()
            }
            runCatching {p.outputStream.close()}
        }
    }
    private val capacity=Semaphore(8)
    private val running=ConcurrentHashMap<String,Owned>()
    private val cancelledBeforeStart=ConcurrentHashMap<String,Long>()
    fun identity():String {
        val p=ProcessBuilder("/system/bin/id").redirectErrorStream(true).start()
        try {
            check(p.waitFor(5,TimeUnit.SECONDS)) {"Identity probe timed out"};check(p.exitValue()==0)
            return p.inputStream.bufferedReader().readText().trim()
        } finally {if(p.isAlive) p.destroyForcibly()}
    }
    fun cancel(id:String):Boolean {
        val now=System.nanoTime()
        cancelledBeforeStart.entries.removeIf {now-it.value>TimeUnit.MINUTES.toNanos(5)}
        // Binder cancel can arrive before its exec transaction reaches this service thread.
        if(cancelledBeforeStart.size>=1024 && !cancelledBeforeStart.containsKey(id) && !running.containsKey(id)) return false
        cancelledBeforeStart[id]=now
        running[id]?.let {it.cancelled.set(true);it.stop(true)}
        return true
    }
    fun cancelAll() {running.keys.toList().forEach(::cancel)}
    fun run(req:ShellRequest,identity:String):ShellResult {
        require(req.execId.length in 1..200 && req.timeoutMs in 1..120_000 && req.maxOutputBytes in 1..131072)
        require((req.argv!=null) xor (req.command!=null)) {"Choose argv or command"}
        require(req.env.size<=32 && req.env.all {(k,v)-> k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && v.length<=4096 && '\u0000' !in v})
        require((req.stdin?.length ?: 0)<=65536)
        val argv=req.argv ?: listOf("/system/bin/sh","-c",req.command!!)
        require(argv.isNotEmpty() && argv.size<=128 && argv.all {it.length<=16000 && '\u0000' !in it})
        check(capacity.tryAcquire()) {"Shell capacity reached"}
        val owned=Owned()
        if(running.putIfAbsent(req.execId,owned)!=null) {capacity.release();error("Execution already active")}
        val start=System.nanoTime();val deadline=start+TimeUnit.MILLISECONDS.toNanos(req.timeoutMs)
        var process:Process?=null;var out:Collector?=null;var err:Collector?=null;var writer:Thread?=null
        var exitCode:Int?=null;var timedOut=false;var failure:String?=null
        cancelledBeforeStart.remove(req.execId)?.let {owned.cancelled.set(true);owned.stop(false)}
        fun remaining()=(deadline-System.nanoTime()).coerceAtLeast(0)
        try {
            val nonce=UUID.randomUUID().toString().replace("-","")
            // User code cannot run before the bounded ownership handshake is acknowledged.
            // Holding the supervisor alive prevents ordinary leader-exit/PID-reuse races.
            val script="""
                printf '%s\n' "${'$'}${'$'}" >&2
                IFS= read -r gate || exit 125
                [ "${'$'}gate" = "${'$'}1" ] || exit 125
                shift
                trap ':' TERM HUP
                "${'$'}@"
                result=${'$'}?
                printf '\nSIConnect_$nonce:%s\n' "${'$'}result" >&2
                kill -STOP "${'$'}${'$'}"
            """.trimIndent()
            val child=if(req.env.isEmpty()) argv else listOf("/system/bin/env")+req.env.map {(k,v)->"$k=$v"}+argv
            check(!owned.cancelled.get()) {"Cancelled before process creation"}
            val pb=ProcessBuilder(listOf("/system/bin/setsid","/system/bin/sh","-c",script,"siconnect",nonce)+child)
            req.cwd?.let {pb.directory(File(it))}
            val p=pb.start();process=p
            check(owned.attach(p)) {"Cancelled during process creation"}
            val rawErr=BufferedInputStream(p.errorStream)
            val handshake=CompletableFuture<Int>()
            Thread({try {
                val header=StringBuilder()
                while(header.length<16) {val b=rawErr.read();if(b<0) error("Missing session handshake");if(b==10) break;header.append(b.toChar())}
                handshake.complete(header.toString().toIntOrNull()?.takeIf {it>1} ?: error("Invalid session handshake"))
            } catch(e:Exception) {handshake.completeExceptionally(e)}},"siconnect-shell-handshake").apply {isDaemon=true;start()}
            val group=handshake.get(remaining(),TimeUnit.NANOSECONDS)
            check(owned.claim(group)) {"Session ended during startup"}
            val completed=CompletableFuture<Int>()
            out=Collector(p.inputStream,req.maxOutputBytes).also {it.start()}
            err=Collector(rawErr,req.maxOutputBytes,"\nSIConnect_$nonce:",completed).also {it.start()}
            writer=owned.releaseGate(nonce,req.stdin)
            exitCode=completed.get(remaining(),TimeUnit.NANOSECONDS)
        } catch(_:TimeoutException) {timedOut=!owned.cancelled.get()}
          catch(e:Exception) {if(!owned.cancelled.get()) failure="SHELL_SUPERVISOR_LOST: ${e.javaClass.simpleName}"}
        finally {
            owned.stop(timedOut || owned.cancelled.get())
            writer?.join(1000);out?.join(1000);err?.join(1000)
            process?.let {runCatching {it.inputStream.close()};runCatching {it.errorStream.close()}}
            running.remove(req.execId,owned);cancelledBeforeStart.remove(req.execId);capacity.release()
        }
        return ShellResult(req.execId,if(timedOut || owned.cancelled.get()) null else exitCode,
            out?.text() ?: "",err?.text() ?: "",out?.truncated ?: false,err?.truncated ?: false,timedOut,owned.cancelled.get(),
            (System.nanoTime()-start)/1_000_000,identity,failure,out?.base64() ?: "",err?.base64() ?: "")
    }
    private class Collector(private val input:InputStream,private val limit:Int,private val marker:String?=null,
                            private val completed:CompletableFuture<Int>?=null):Thread("siconnect-shell-io") {
        private val bytes=ByteArrayOutputStream()
        @Volatile var truncated=false
        init {isDaemon=true}
        private fun append(b:ByteArray,n:Int=b.size) = synchronized(bytes) {
            val room=limit-bytes.size();if(room>0) bytes.write(b,0,minOf(n,room));if(n>room) truncated=true
        }
        override fun run() {
            try {input.use {stream->
                if(marker==null) {
                    val buffer=ByteArray(8192)
                    while(true) {val n=stream.read(buffer);if(n<0) break;append(buffer,n)}
                } else {
                    val prefix=marker.toByteArray();val pending=ByteArrayOutputStream()
                    while(true) {
                        val b=stream.read();if(b<0) break
                        val size=pending.size()
                        if(size<prefix.size && b==prefix[size].toInt()) pending.write(b)
                        else if(size>=prefix.size && b in 48..57 && size<prefix.size+3) pending.write(b)
                        else if(size>prefix.size && b==10) {
                            val code=pending.toString("UTF-8").removePrefix(marker).toIntOrNull()
                            if(code!=null && code in 0..255) {completed!!.complete(code);pending.reset()}
                            else {append(pending.toByteArray());pending.reset();append(byteArrayOf(b.toByte()))}
                        } else {
                            append(pending.toByteArray());pending.reset()
                            if(b==prefix[0].toInt()) pending.write(b) else append(byteArrayOf(b.toByte()))
                        }
                    }
                    if(pending.size()>0) append(pending.toByteArray())
                }
            }} catch(_:IOException) {} finally {if(completed?.isDone==false) completed.completeExceptionally(EOFException("Supervisor pipe ended before exit status"))}
        }
        fun text():String=synchronized(bytes) {bytes.toString("UTF-8")}
        fun base64():String=synchronized(bytes) {android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP)}
    }
}
