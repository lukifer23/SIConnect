package dev.siconnect.app

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Bounded continuation workers. Durable stores remain the backlog when admission is full. */
internal class RuntimeDispatch(
    private val scope: CoroutineScope,
    workers: Int = 4,
    capacity: Int = 64,
    private val onFailure: (String, Exception) -> Unit,
    private val action: suspend (String) -> Unit,
) {
    private class Entry(var running: Boolean = false, var dirty: Boolean = false)
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private val pendingLimit = capacity.also { require(it > 0) }
    private val entryLimit = capacity + workers
    // Reserve room to requeue every active key at the tail, even when new admission is full.
    private val queue = Channel<String>(entryLimit)
    private var queued = 0

    init {
        require(workers in 1..16)
        repeat(workers) {
            scope.launch {
                for (key in queue) {
                    synchronized(lock) { queued--;entries.getValue(key).apply {running=true;dirty=false} }
                    try { action(key) }
                    catch (e: CancellationException) { currentCoroutineContext().ensureActive(); onFailure(key, e) }
                    catch (e: Exception) { onFailure(key, e) }
                    // A signal arriving during execution requires one more lookup. Coalescing
                    // must not lose a child completion racing a workflow's approval handoff.
                    synchronized(lock) {
                        val entry=entries.getValue(key)
                        if (entry.dirty) {
                            entry.running=false;queued++
                            check(queue.trySend(key).isSuccess) {"Continuation requeue capacity violated"}
                        } else entries.remove(key)
                    }
                }
            }
        }
    }

    fun submit(key: String): Boolean = synchronized(lock) {
        if (!scope.isActive) return false
        entries[key]?.let { if(it.running) it.dirty = true; return true }
        if(queued>=pendingLimit || entries.size>=entryLimit) return false
        entries[key] = Entry()
        if (queue.trySend(key).isSuccess) {queued++;true} else { entries.remove(key); false }
    }
}
