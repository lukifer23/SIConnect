package dev.siconnect.app.backend

import android.system.Os
import android.system.OsConstants
import kotlinx.serialization.Serializable
import java.io.*
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

/** Every command owns a new Unix session. Cancellation kills its process group and drains bounded pipes. */
object ShellRunner {
    private class Owned {
        val cancelled = AtomicBoolean(false)
        @Volatile var process: Process? = null
        @Volatile var group: Int? = null
        fun stop() {
            group?.let { runCatching { Os.kill(-it, OsConstants.SIGTERM) } }
            process?.destroy()
            // Kill descendants even when the shell has already exited or ignored TERM.
            Thread.sleep(100)
            group?.let { runCatching { Os.kill(-it, OsConstants.SIGKILL) } }
            process?.let { if (it.isAlive) it.destroyForcibly() }
        }
    }
    private val running = ConcurrentHashMap<String, Owned>()
    fun identity(): String {
        val p = ProcessBuilder("/system/bin/id").redirectErrorStream(true).start()
        try {
            check(p.waitFor(5, TimeUnit.SECONDS)) { "Identity probe timed out" }
            check(p.exitValue() == 0)
            return p.inputStream.bufferedReader().readText().trim()
        } finally { if (p.isAlive) p.destroyForcibly() }
    }
    fun cancel(id: String): Boolean {
        val h = running[id] ?: return false
        h.cancelled.set(true); h.stop(); return true
    }
    fun cancelAll() { running.keys.toList().forEach(::cancel) }
    fun run(req: ShellRequest, identity: String): ShellResult {
        require(req.execId.length in 1..200 && req.timeoutMs in 1..120_000 && req.maxOutputBytes in 1..131072)
        require((req.argv != null) xor (req.command != null)) { "Choose argv or command" }
        require(req.env.size <= 32 && req.env.all { (k,v) -> k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && v.length <= 4096 })
        require((req.stdin?.length ?: 0) <= 65536)
        require(running.size < 8) { "Shell capacity reached" }
        val owned = Owned()
        check(running.putIfAbsent(req.execId, owned) == null) { "Execution already active" }
        val start = System.nanoTime()
        var out: Collector? = null; var err: Collector? = null
        try {
            val argv = req.argv ?: listOf("/system/bin/sh", "-c", req.command!!)
            require(argv.isNotEmpty() && argv.size <= 128)
            // A private first stderr line carries the session ID before user code is executed.
            val pb = ProcessBuilder(listOf("/system/bin/setsid", "/system/bin/sh", "-c", "echo \"\$\$\" >&2; exec \"\$@\"", "siconnect") + argv)
            req.cwd?.let { pb.directory(File(it)) }; pb.environment().putAll(req.env)
            val process = pb.start(); owned.process = process
            val rawErr = BufferedInputStream(process.errorStream)
            val header = StringBuilder()
            while (header.length < 16) { val c = rawErr.read(); if (c < 0 || c == 10) break; header.append(c.toChar()) }
            owned.group = header.toString().toIntOrNull() ?: error("Session ownership handshake failed")
            out = Collector(process.inputStream, req.maxOutputBytes).also { it.start() }
            err = Collector(rawErr, req.maxOutputBytes).also { it.start() }
            val writer = Thread {
                runCatching { process.outputStream.use { req.stdin?.let { s -> it.write(s.toByteArray()) } } }
            }.apply { isDaemon = true; start() }
            if (owned.cancelled.get()) owned.stop()
            val finished = process.waitFor(req.timeoutMs, TimeUnit.MILLISECONDS)
            // This also removes children that outlived their command parent.
            owned.stop()
            writer.join(1000); out.join(1000); err.join(1000)
            process.inputStream.close(); rawErr.close()
            return ShellResult(req.execId, if (finished && !owned.cancelled.get()) process.exitValue() else null,
                out.text(), err.text(), out.truncated, err.truncated, !finished && !owned.cancelled.get(), owned.cancelled.get(),
                (System.nanoTime() - start) / 1_000_000, identity,
                stdoutBase64 = out.base64(), stderrBase64 = err.base64())
        } finally {
            owned.stop(); running.remove(req.execId, owned)
        }
    }
    private class Collector(private val input: InputStream, private val limit: Int) : Thread("siconnect-shell-io") {
        private val bytes = ByteArrayOutputStream()
        @Volatile var truncated = false
        init { isDaemon = true }
        override fun run() {
            val b = ByteArray(8192)
            runCatching { input.use { while (true) {
                val n = it.read(b); if (n < 0) break
                synchronized(bytes) { val room = limit - bytes.size(); if (room > 0) bytes.write(b, 0, minOf(n, room)); if (n > room) truncated = true }
            } } }
        }
        fun text(): String = synchronized(bytes) { bytes.toString("UTF-8") }
        fun base64(): String = synchronized(bytes) { android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP) }
    }
}
