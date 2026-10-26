package dev.siconnect.app

import kotlinx.coroutines.CancellationException

/** A failed reconciliation stage must not permanently remove unrelated recovery work. */
internal class RuntimeRecovery(private val onFailure: (String, Exception) -> Unit) {
    suspend fun run(stage: String, block: suspend () -> Unit) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { onFailure(stage, e) }
    }
}
