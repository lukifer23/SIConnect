package dev.siconnect.app.termux

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** A committed callback is authoritative even if its in-memory wake-up loses a deadline race. */
internal suspend fun <T:Any> awaitTermuxResult(timeoutMs:Long,signal:Deferred<Unit>,readCommitted:()->T?):T? {
    // withTimeoutOrNull recognizes only its own timeout; owner cancellation and
    // the enclosing engine deadline must propagate instead of becoming a new error.
    withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) {signal.await()}
    currentCoroutineContext().ensureActive()
    val committed=readCommitted()
    currentCoroutineContext().ensureActive()
    return committed
}
