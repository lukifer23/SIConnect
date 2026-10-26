package dev.siconnect.app.backend

import kotlinx.coroutines.*

/** Own the underlying operation for its entire blocking body, including response reads. */
internal suspend fun <T> cancellableIo(cancel: () -> Unit, body: () -> T): T = coroutineScope {
    val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { runCatching(cancel) }
    }
    try {
        withContext(Dispatchers.IO) { ensureActive(); body() }
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        throw error
    } finally { cancellation.cancel() }
}
