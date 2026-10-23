package dev.siconnect.app.bootstrap

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BootstrapOperationsTest {
    @Test fun discoveryIsBusyImmediatelyAndOwnerDoubleTapCannotLaunchAgain()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val release=CompletableDeferred<Unit>()
        val changes=mutableListOf<Boolean>()
        val operations=BootstrapOperations(scope,changes::add)
        var launches=0
        assertTrue(operations.launch(BootstrapOperations.Kind.OWNER) {launches++;release.await()})
        assertTrue(operations.busy)
        assertFalse(operations.launch(BootstrapOperations.Kind.OWNER) {launches++})
        assertFalse(operations.launch(BootstrapOperations.Kind.REFRESH) {launches++})
        assertEquals(1,launches)
        release.complete(Unit)
        assertFalse(operations.busy)
        assertEquals(listOf(true,false),changes)
        scope.cancel()
    }

    @Test fun overlappingRecoveryEventsRunOnceAfterCurrentResourcesClose()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val release=CompletableDeferred<Unit>()
        val operations=BootstrapOperations(scope) {}
        var closed=false
        var recoveries=0
        operations.launch(BootstrapOperations.Kind.OWNER) {try {release.await()} finally {closed=true}}
        repeat(10) {operations.launch(BootstrapOperations.Kind.RECOVERY) {assertTrue(closed);recoveries++}}
        release.complete(Unit)
        assertEquals(1,recoveries)
        assertFalse(operations.busy)
        scope.cancel()
    }

    @Test fun cancellationDropsQueuedRecoveryAndWaitsForCleanup()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val cleaned=CompletableDeferred<Unit>()
        val operations=BootstrapOperations(scope) {}
        var recoveries=0
        operations.launch(BootstrapOperations.Kind.OWNER) {
            try {awaitCancellation()} finally {withContext(NonCancellable) {cleaned.await()}}
        }
        operations.launch(BootstrapOperations.Kind.RECOVERY) {recoveries++}
        operations.cancel()
        assertTrue(operations.busy)
        cleaned.complete(Unit)
        assertFalse(operations.busy)
        assertEquals(0,recoveries)
        assertTrue(operations.launch(BootstrapOperations.Kind.OWNER) {recoveries++})
        assertEquals(1,recoveries)
        scope.cancel()
    }

    @Test fun cancelledScopeCannotLeaveSetupBusy() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        scope.cancel()
        val operations=BootstrapOperations(scope) {}
        operations.launch(BootstrapOperations.Kind.OWNER) {fail("Cancelled scope executed setup")}
        assertFalse(operations.busy)
    }
}
