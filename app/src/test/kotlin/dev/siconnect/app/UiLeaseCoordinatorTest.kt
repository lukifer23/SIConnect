package dev.siconnect.app

import dev.siconnect.app.coordination.*
import dev.siconnect.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Test-only store. Production uses the existing SQLite database and versioned migration. */
class UiLeaseCoordinatorTest {
    private class Store : UiLeaseStore {
        var value: UiLease? = null
        override fun <T> atomic(block: () -> T): T = synchronized(this) { block() }
        override fun get() = value
        override fun put(lease: UiLease) { value = lease }
        override fun clear() { value = null }
    }
    private fun ctx(agent: String, request: String = "request-$agent") = ExecutionContext(request, "trace-$request", agent, "test-device")
    private fun failure(code: String, body: () -> Unit) {
        try { body(); fail("Expected $code") } catch (e: CapabilityException) { assertEquals(code, e.code) }
    }
    @Test fun sameLabelDifferentIdentitiesCannotInheritOrRenewOwnership() {
        val store = Store(); val ui = UiLeaseCoordinator(store, EventSink {}, "test-device")
        val a = ctx("agent-a"); val b = ctx("agent-b")
        val lease = ui.acquire(a, "dev.siconnect.test")
        failure("RESOURCE_BUSY") { ui.acquire(b, "dev.siconnect.test") }
        failure("LEASE_NOT_OWNED") { ui.renew(b, lease.leaseId) }
        failure("LEASE_NOT_OWNED") { ui.release(b, lease.leaseId) }
        assertEquals(lease.leaseId, ui.status()?.leaseId)
        assertTrue(ui.release(a, lease.leaseId))
        assertFalse(ui.release(a, lease.leaseId))
        assertNotEquals(lease.leaseId, ui.acquire(b, "dev.siconnect.test").leaseId)
    }
    @Test fun expiryRenewalAndStartupRecoveryNeverReplayActions() {
        var now = 1_000L; val store = Store(); val events = mutableListOf<Event>()
        val ui = UiLeaseCoordinator(store, EventSink { events += it }, "test-device", { now }, "process-one")
        val a = ctx("agent-a"); val first = ui.acquire(a, "dev.siconnect.test", 5_000)
        now += 4_000; assertEquals(10_000L, ui.renew(a, first.leaseId, 5_000).expiresAtMs)
        now = 10_001; assertNull(ui.status())
        ui.acquire(a, "dev.siconnect.test")
        val restarted = UiLeaseCoordinator(store, EventSink { events += it }, "test-device", { now }, "process-two")
        restarted.recover(); assertNull(restarted.status())
        assertTrue(events.any { it.eventType == "ui.lease.abandoned" })
        restarted.recover(); assertEquals(1, events.count { it.eventType == "ui.lease.abandoned" })
    }
    @Test fun cancellationReleasesImplicitOwnershipAndConcurrentActionIsRejected() = runBlocking {
        val ui = UiLeaseCoordinator(Store(), EventSink {}, "test-device")
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val task = launch { ui.withScreen(ctx("a"), "dev.siconnect.test") { entered.complete(Unit); finish.await() } }
        entered.await()
        try { ui.withScreen(ctx("b"), "dev.siconnect.test") { fail("Must never act") }; fail("Expected busy") }
        catch (e: CapabilityException) { assertEquals("RESOURCE_BUSY", e.code) }
        task.cancelAndJoin(); assertNull(ui.status())
        assertEquals("done", ui.withScreen(ctx("b"), "dev.siconnect.test") { "done" })
        assertNull(ui.status())
    }
    @Test fun explicitLeaseSurvivesActionButCannotChangeTargetOrOverlapItself() = runBlocking {
        var now = 0L; val ui = UiLeaseCoordinator(Store(), EventSink {}, "test-device", { now })
        val a = ctx("a"); val lease = ui.acquire(a, "dev.siconnect.test", 5_000)
        assertEquals(42, ui.withScreen(a, "dev.siconnect.test", lease.leaseId) { 42 })
        assertEquals(lease.leaseId, ui.status()?.leaseId)
        try { ui.withScreen(a, "dev.siconnect.other", lease.leaseId) { fail("Wrong target") }; fail("Expected mismatch") }
        catch (e: CapabilityException) { assertEquals("LEASE_TARGET_MISMATCH", e.code) }
        ui.withScreen(a, "dev.siconnect.test", lease.leaseId) {
            now = 6_000
            failure("RESOURCE_BUSY") { ui.acquire(ctx("b"), "dev.siconnect.test") }
            failure("RESOURCE_BUSY") { ui.release(a, lease.leaseId) }
        }
        assertNull(ui.status())
    }
    @Test fun recoveryOfInterruptedActionReportsUnknownAndDoesNotReexecute() {
        val store = Store(); val events = mutableListOf<Event>()
        store.value = UiLease("old-lease", "a", "acquire", "trace", "dev.siconnect.test", 0, 30_000,
            "dead-process", "action-request", "action-trace")
        val ui = UiLeaseCoordinator(store, EventSink { events += it }, "test-device", { 100 }, "new-process")
        ui.recover(); assertNull(ui.status())
        assertEquals("UNKNOWN_OUTCOME", events.single().errorCode)
    }
}
