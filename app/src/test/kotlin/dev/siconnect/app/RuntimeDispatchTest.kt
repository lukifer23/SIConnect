package dev.siconnect.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeDispatchTest {
    @Test fun slowContinuationDoesNotBlockIndependentWorkAndSignalsCoalesce() = runTest {
        val release=CompletableDeferred<Unit>();val completed=mutableListOf<String>();var attempts=0
        val dispatch=RuntimeDispatch(backgroundScope,workers=2,onFailure={_,e->throw e}) {key->
            if(key=="slow") {attempts++;if(attempts==1) release.await()}
            completed+=key
        }
        assertTrue(dispatch.submit("slow"));runCurrent()
        repeat(100) {assertTrue(dispatch.submit("slow"))}
        assertTrue(dispatch.submit("other"));runCurrent()
        assertEquals(listOf("other"),completed)
        release.complete(Unit);runCurrent()
        assertEquals(listOf("other","slow","slow"),completed)
    }

    @Test fun fullQueueRejectsAdmissionWithoutGrowingOrLosingFutureAdmission() = runTest {
        val release=CompletableDeferred<Unit>();val completed=mutableListOf<String>()
        val dispatch=RuntimeDispatch(backgroundScope,workers=1,capacity=1,onFailure={_,e->throw e}) {key->
            if(key=="first") release.await()
            completed+=key
        }
        assertTrue(dispatch.submit("first"));runCurrent()
        assertTrue(dispatch.submit("second"));assertFalse(dispatch.submit("third"))
        release.complete(Unit);runCurrent()
        assertTrue(dispatch.submit("third"));runCurrent()
        assertEquals(listOf("first","second","third"),completed)
    }

    @Test fun oneFailedOrTimedOutContinuationDoesNotRemoveAWorker() = runTest {
        val failures=mutableListOf<String>();val completed=mutableListOf<String>()
        val dispatch=RuntimeDispatch(backgroundScope,workers=1,onFailure={key,_->failures+=key}) {key->
            when(key) {"failed"->error("Transient store failure");"cancelled"->throw CancellationException("Operation interrupted");else->completed+=key}
        }
        listOf("failed","cancelled","next").forEach {dispatch.submit(it)};runCurrent()
        assertEquals(listOf("failed","cancelled"),failures);assertEquals(listOf("next"),completed)
    }

    @Test fun stoppingRuntimeCancelsWorkersAndRejectsNewSignals() = runTest {
        val job=SupervisorJob(backgroundScope.coroutineContext[Job]);val scope=CoroutineScope(backgroundScope.coroutineContext+job)
        var cleaned=false
        val dispatch=RuntimeDispatch(scope,workers=1,onFailure={_,_->fail("Runtime cancellation must propagate")}) {
            try {awaitCancellation()} finally {cleaned=true}
        }
        dispatch.submit("run");runCurrent();job.cancel();runCurrent()
        assertTrue(cleaned);assertFalse(dispatch.submit("later"))
    }

    @Test fun aDirtyActiveKeyYieldsToAlreadyQueuedWork() = runTest {
        val release=CompletableDeferred<Unit>();val completed=mutableListOf<String>();var attempts=0
        val dispatch=RuntimeDispatch(backgroundScope,workers=1,capacity=1,onFailure={_,e->throw e}) {key->
            if(key=="first" && ++attempts==1) release.await()
            completed+=key
        }
        dispatch.submit("first");runCurrent()
        assertTrue(dispatch.submit("second"));assertTrue(dispatch.submit("first"))
        release.complete(Unit);runCurrent()
        assertEquals(listOf("first","second","first"),completed)
    }
}
