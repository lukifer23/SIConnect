package dev.siconnect.app.bootstrap

import kotlinx.coroutines.*

/** One resource-owning setup operation at a time; recovery events coalesce instead of disappearing. */
internal class BootstrapOperations(
    private val scope:CoroutineScope,
    private val onBusy:(Boolean)->Unit,
) {
    enum class Kind { OWNER, RECOVERY, REFRESH }
    private var active:Job?=null
    private var pendingRecovery:(suspend ()->Unit)?=null
    val busy:Boolean get()=synchronized(this) {active!=null}

    @Synchronized fun launch(kind:Kind,block:suspend ()->Unit):Boolean {
        if(active!=null) {
            if(kind==Kind.RECOVERY) pendingRecovery=block
            return false
        }
        start(block)
        return true
    }

    /** Cancellation also discards queued recovery; it must not undo the owner's Cancel action. */
    @Synchronized fun cancel() {
        pendingRecovery=null
        active?.cancel()
    }

    private fun start(block:suspend ()->Unit) {
        val next=scope.launch(start=CoroutineStart.LAZY) {block()}
        active=next
        onBusy(true)
        next.invokeOnCompletion {
            synchronized(this) {
                if(active===next) {
                    active=null
                    val recovery=pendingRecovery
                    pendingRecovery=null
                    if(recovery!=null && scope.isActive) start(recovery) else onBusy(false)
                }
            }
        }
        next.start()
    }
}
