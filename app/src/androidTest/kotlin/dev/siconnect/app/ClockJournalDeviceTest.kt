package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.clocks.*
import dev.siconnect.core.CapabilityException
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real disposable SQLite transactions and process-recovery identities; no Clock app actions. */
class ClockJournalDeviceTest {
    @Test fun timerIdentitiesRollbackAtomicallyAndUncertainCancelCannotReplayAfterReopen() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="clock-journal-test-${UUID.randomUUID()}.db"
        var journal=ClockJournal(context,name)
        fun timer(request:String):ManagedTimer {
            val id=UUID.randomUUID().toString()
            return ManagedTimer(id,"own-agent",request,ClockContract.SAMSUNG_CLOCK,12345,100,300,"Disposable",
                ClockContract.publishedLabel("Disposable",id),200,"PREPARED")
        }
        try {
            val rejected=timer("reject-write")
            journal.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_own_start BEFORE INSERT ON operations WHEN NEW.request_id='reject-write' BEGIN SELECT RAISE(ABORT,'own fixture failure'); END")
            try {journal.prepare(rejected);fail("Aborted operation must abort its timer identity too")}
            catch(_:android.database.sqlite.SQLiteException) { }
            assertNull(journal.timer(rejected.timerId));assertNull(journal.operation("reject-write"))
            journal.writableDatabase.execSQL("DROP TRIGGER reject_own_start")
            val kept=timer("start-kept");journal.prepare(kept);journal.phase("start-kept","DISPATCHING")
            assertEquals(kept.timerId,journal.owned("own-agent",kept.timerId).timerId)
            try {journal.owned("another-agent",kept.timerId);fail("Other agent accessed timer")}
            catch(error:CapabilityException) {assertEquals("CLOCK_TIMER_NOT_FOUND",error.code)}
            journal.prepareCancel(kept,"cancel-once",210);journal.phase("cancel-once","DISPATCHING")
            journal.close();journal=ClockJournal(context,name);journal.recoverOrphans()
            assertEquals("OUTCOME_UNKNOWN",journal.timer(kept.timerId)?.startPhase)
            assertEquals("OUTCOME_UNKNOWN",journal.operation("cancel-once")?.get("phase")?.toString()?.trim('"'))
            try {journal.prepareCancel(kept,"never-repeat",220);fail("Unknown cancellation was replayable")}
            catch(error:CapabilityException) {assertEquals("CLOCK_CANCEL_ALREADY_DISPATCHED",error.code)}
            assertNull(journal.operation("never-repeat"))
            assertEquals(1,journal.page("own-agent",50,null).size)
            assertTrue(journal.page("another-agent",50,null).isEmpty())
        } finally {journal.close();context.deleteDatabase(name)}
    }
}
