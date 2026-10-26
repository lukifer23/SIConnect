package dev.siconnect.app.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** A network transition wakes backoff only; it never queues or resubmits an invocation. */
internal class GatewayReconnectWake {
    private val revision=AtomicLong()
    private val signals=Channel<Unit>(Channel.CONFLATED)
    fun generation():Long=revision.get()
    fun usableNetworkChanged() {revision.incrementAndGet();signals.trySend(Unit)}
    suspend fun awaitBackoff(delayMs:Long,since:Long) {
        require(delayMs>0)
        withTimeoutOrNull(delayMs) {while(revision.get()==since) signals.receive()}
    }
}
