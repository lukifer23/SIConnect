package dev.siconnect.app.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** Owned by one WebSocket listener, including during overlap with its replacement. */
internal class GatewayReceipts {
    private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var closed = false

    suspend fun awaitReceipt(key: String, send: () -> Boolean) {
        val ack = synchronized(this) {
            check(!closed) { "Gateway connection lost" }
            check(key !in pending) { "Already waiting for gateway acknowledgement" }
            CompletableDeferred<Unit>().also { pending[key] = it }
        }
        try {
            check(send()) { "Gateway connection lost" }
            withTimeout(8_000) { ack.await() }
        } finally { synchronized(this) { pending.remove(key) } }
    }

    @Synchronized fun acknowledge(key: String) { pending[key]?.complete(Unit) }
    @Synchronized fun reject(key: String, reason: String) { pending[key]?.completeExceptionally(IllegalStateException(reason)) }
    @Synchronized fun close() {
        closed = true
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Gateway connection lost")) }
    }
}
