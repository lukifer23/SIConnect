package dev.siconnect.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeRecoveryTest {
    @Test fun failedStagesLeaveOtherStagesAndFutureTicksAvailable() = runBlocking {
        val failures=mutableListOf<String>()
        val completed=mutableListOf<String>()
        val recovery=RuntimeRecovery {stage, _ -> failures+=stage}
        repeat(2) {tick ->
            recovery.run("scheduler") {if(tick==0) error("Transient storage failure") else completed+="scheduler"}
            recovery.run("approvals") {completed+="approvals"}
            recovery.run("workflows") {
                recovery.run("first workflow") {error("Unavailable record")}
                recovery.run("second workflow") {completed+="workflow"}
            }
        }
        assertEquals(listOf("scheduler","first workflow","first workflow"),failures)
        assertEquals(listOf("approvals","workflow","scheduler","approvals","workflow"),completed)
    }

    @Test fun cancellationStopsRecoveryInsteadOfBeingLoggedAsATransientFailure() = runBlocking {
        var reported=false
        val recovery=RuntimeRecovery {_, _ -> reported=true}
        val cancellation=CancellationException("Runtime stopped")
        try {
            recovery.run("approvals") {throw cancellation}
            fail("Cancellation must propagate")
        } catch(e:CancellationException) {assertSame(cancellation,e)}
        assertFalse(reported)
    }
}
