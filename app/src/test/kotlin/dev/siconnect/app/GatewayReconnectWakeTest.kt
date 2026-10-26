package dev.siconnect.app

import dev.siconnect.app.transport.GatewayReconnectWake
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayReconnectWakeTest {
    @Test fun validatedTransitionWakesLongBackoffImmediately()=runTest {
        val wake=GatewayReconnectWake();val generation=wake.generation()
        val waiting=async {wake.awaitBackoff(256_000,generation)};runCurrent();assertFalse(waiting.isCompleted)
        advanceTimeBy(10);wake.usableNetworkChanged();runCurrent();assertTrue(waiting.isCompleted)
        assertEquals(10,testScheduler.currentTime)
    }
    @Test fun transitionBeforeWaitIsNotLost()=runTest {
        val wake=GatewayReconnectWake();val generation=wake.generation();wake.usableNetworkChanged()
        wake.awaitBackoff(256_000,generation);assertEquals(0,testScheduler.currentTime)
    }
    @Test fun oldAndDuplicateSignalsCannotBypassTheNextBackoff()=runTest {
        val wake=GatewayReconnectWake();repeat(10_000) {wake.usableNetworkChanged()}
        val waiting=async {wake.awaitBackoff(100,wake.generation())};runCurrent();assertFalse(waiting.isCompleted)
        advanceTimeBy(99);runCurrent();assertFalse(waiting.isCompleted)
        advanceTimeBy(1);runCurrent();assertTrue(waiting.isCompleted)
    }
    @Test fun stoppingRuntimeCancelsTheWaitWithoutAnotherAttempt()=runTest {
        val wake=GatewayReconnectWake();var attempts=0
        val job=launch {wake.awaitBackoff(256_000,wake.generation());attempts++};runCurrent()
        job.cancelAndJoin();wake.usableNetworkChanged();runCurrent();assertEquals(0,attempts)
    }
}
