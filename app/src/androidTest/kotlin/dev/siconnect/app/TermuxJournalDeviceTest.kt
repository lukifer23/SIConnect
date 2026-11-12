package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.termux.TermuxJournal
import dev.siconnect.app.termux.TermuxContract
import dev.siconnect.app.termux.awaitTermuxResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Actual disposable Android SQLite journal; does not start Termux or touch its files. */
class TermuxJournalDeviceTest {
    @Test fun committedSqliteCallbackWinsWhenWaiterNotificationMissesTheDeadline()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="termux-journal-test-${UUID.randomUUID()}.db"
        val journal=TermuxJournal(context,name)
        try {
            journal.prepare("committed","owner-a","nonce",12345,118,1000,2000,2100,1024)
            journal.markDispatch("committed",2001)
            assertTrue(journal.receive("committed","nonce",buildJsonObject {
                put("executionErrorCode",TermuxContract.RESULT_ERR_SUCCESS);put("exitCode",0);put("commandSucceeded",true)
            },2002))
            val signal=CompletableDeferred<Unit>()
            val actual=awaitTermuxResult(10,signal) {journal.entry("committed")?.takeIf {it.result!=null}}
            assertNotNull("Real committed SQLite result must not become a false pending outcome",actual)
            assertEquals("RESULT_RECEIVED",actual?.phase)
            assertEquals("COMPLETED",actual?.waitStatus)
            assertFalse(signal.isCompleted)
        } finally {journal.close();context.deleteDatabase(name)}
    }
    @Test fun lateCallbacksSurviveReopenAreDeduplicatedAndRespectExplicitPayloadRetention() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="termux-journal-test-${UUID.randomUUID()}.db"
        var journal=TermuxJournal(context,name)
        val completed=buildJsonObject {
            put("stdout","retained command output");put("stderr","");put("exitCode",0)
            put("executionErrorCode",TermuxContract.RESULT_ERR_SUCCESS);put("executionErrorMessage",JsonNull);put("commandSucceeded",true)
        }
        try {
            journal.prepare("late","owner-a","nonce-a",12345,118,1000,2000,2100,1024)
            journal.markDispatch("late",2001);journal.waiterEnded("late","TIMED_OUT")
            journal.prepare("unknown","owner-b","nonce-b",12345,118,1000,2000,2100,1024)
            journal.markDispatch("unknown",2001)
            journal.prepare("never-sent","owner-a","nonce-c",12345,118,1000,2000,2100,1024)
            journal.close();journal=TermuxJournal(context,name);journal.recoverOrphans()
            assertEquals("TIMED_OUT",journal.entry("late")?.waitStatus)
            assertEquals("PROCESS_LOST",journal.entry("unknown")?.waitStatus)
            assertEquals("NOT_DISPATCHED",journal.entry("never-sent")?.phase)
            assertFalse(journal.receive("late","wrong nonce",completed,3000))
            assertTrue(journal.receive("late","nonce-a",completed,3000))
            assertFalse(journal.receive("late","nonce-a",buildJsonObject {put("stdout","overwrite")},3001))
            assertEquals("retained command output",journal.entry("late")?.result?.get("stdout")?.jsonPrimitive?.content)
            assertTrue(journal.receive("unknown","nonce-b",completed,3000))
            assertEquals("CONFIRMED_ALLOWED",journal.lastPolicyState(12345,118,1000))
            assertEquals("UNKNOWN",journal.lastPolicyState(12345,119,1000))
            assertEquals("UNKNOWN",journal.lastPolicyState(12345,118,9999))
            assertEquals(3000L,journal.lastCallbackAtMs(12345,118,1000,"owner-a"))
            assertNull(journal.lastCallbackAtMs(12345,118,1000,"unrelated-agent"))
            val ownRecent=journal.recent("owner-a",1,false,null)
            assertEquals(1,ownRecent.getValue("executions").jsonArray.size)
            assertFalse(ownRecent.toString().contains("nonce-a"))
            assertFalse(ownRecent.toString().contains("retained command output"))
            val next=ownRecent.getValue("nextBeforeExecutionId").jsonPrimitive.content
            val older=journal.recent("owner-a",1,false,next)
            assertEquals(1,older.getValue("executions").jsonArray.size)
            assertNotEquals(ownRecent.getValue("executions"),older.getValue("executions"))
            assertEquals(1,journal.compactPayloads(4000,100) {it=="late"})
            assertEquals(0,journal.compactPayloads(4000,100) {it=="late"})
            val compacted=requireNotNull(journal.entry("late"))
            assertTrue(compacted.output().getValue("callbackReceived").jsonPrimitive.boolean)
            assertEquals("TIMED_OUT",compacted.waitStatus)
            assertEquals(true,compacted.result?.get("payloadExpired")?.jsonPrimitive?.boolean)
            assertNull(compacted.result?.get("stdout"))
            assertEquals(0,compacted.result?.get("exitCode")?.jsonPrimitive?.int)
            assertNotNull(journal.entry("unknown")?.result?.get("stdout"))
            journal.close();journal=TermuxJournal(context,name);journal.recoverOrphans()
            assertEquals(true,journal.entry("late")?.result?.get("payloadExpired")?.jsonPrimitive?.boolean)
            assertEquals("retained command output",journal.entry("unknown")?.result?.get("stdout")?.jsonPrimitive?.content)
        } finally {journal.close();context.deleteDatabase(name)}
    }

    @Test fun lapsedDispatchesStopBlockingAdmissionButStillAcceptLateCallbacks() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="termux-journal-test-${UUID.randomUUID()}.db"
        val journal=TermuxJournal(context,name)
        try {
            repeat(TermuxContract.MAX_PENDING) {i->
                journal.prepare("hung-$i","owner-a","nonce-$i",12345,118,1000,2000,2100,1024)
                journal.markDispatch("hung-$i",2001);journal.waiterEnded("hung-$i","TIMED_OUT")
            }
            try {journal.prepare("blocked","owner-a","nonce-x",12345,118,1000,3000,3100,1024);fail("Recent unresolved dispatches must still cap admission")}
            catch(e:dev.siconnect.core.CapabilityException) {assertEquals("TERMUX_UNRESOLVED_LIMIT",e.code)}
            val later=2100+TermuxContract.STALE_DISPATCH_GRACE_MS+1
            journal.prepare("admitted","owner-a","nonce-y",12345,118,1000,later,later+100,1024)
            assertEquals("DISPATCHING",journal.entry("hung-0")?.phase)
            assertEquals(TermuxContract.MAX_PENDING+1,journal.pendingCount("owner-a"))
            assertTrue("A lapsed dispatch must still record its real late result",journal.receive("hung-0","nonce-0",buildJsonObject {
                put("executionErrorCode",TermuxContract.RESULT_ERR_SUCCESS);put("exitCode",0)
            },later+1))
        } finally {journal.close();context.deleteDatabase(name)}
    }

    @Test fun missingExitCodeDoesNotFalselyConfirmExternalAppsPolicy() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="termux-journal-test-${UUID.randomUUID()}.db"
        val journal=TermuxJournal(context,name)
        try {
            journal.prepare("failed","owner-a","nonce",12345,118,1000,2000,2100,1024)
            journal.markDispatch("failed",2001)
            assertTrue(journal.receive("failed","nonce",buildJsonObject {
                put("executionErrorCode",1);put("executionErrorMessage","API failure");put("commandSucceeded",false)
            },3000))
            assertEquals("UNKNOWN",journal.lastPolicyState(12345,118,1000))
            journal.prepare("denied","owner-a","nonce2",12345,118,1000,2000,2100,1024)
            journal.markDispatch("denied",2001)
            assertTrue(journal.receive("denied","nonce2",buildJsonObject {
                put("executionErrorCode",1);put("executionErrorMessage","allow-external-apps must be true");put("commandSucceeded",false)
            },3001))
            assertEquals("REJECTED",journal.lastPolicyState(12345,118,1000))
        } finally {journal.close();context.deleteDatabase(name)}
    }
}
