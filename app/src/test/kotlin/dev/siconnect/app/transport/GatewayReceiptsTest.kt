package dev.siconnect.app.transport

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GatewayReceiptsTest {
    @Test fun replacementCanPublishSameInvitationDuringOldCleanup() = runTest {
        val old = GatewayReceipts()
        val replacement = GatewayReceipts()
        val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { old.awaitReceipt("invitation") { true } } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { replacement.awaitReceipt("invitation") { true } }
        old.close()
        old.acknowledge("invitation")
        assertTrue(first.await().isFailure)
        assertFalse(second.isCompleted)
        replacement.acknowledge("invitation")
        second.await()
        assertTrue(runCatching { old.awaitReceipt("late") { error("Closed socket sent a message") } }.exceptionOrNull() is IllegalStateException)
    }
}
