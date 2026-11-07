package dev.siconnect.app.termux

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class TermuxReliabilityTest {
    @Test fun aCommittedResultSurvivesAMissingWakeupAtTheWaitDeadline()=runBlocking {
        val signal=CompletableDeferred<Unit>()
        assertEquals("committed",awaitTermuxResult(10,signal) {"committed"})
        assertFalse(signal.isCompleted)
    }
    @Test fun parentDeadlinePropagatesWithoutReadingAnOutcomeAfterCancellation()=runBlocking {
        var reads=0
        try {
            withTimeout(20) {awaitTermuxResult(5000,CompletableDeferred<Unit>()) {reads++;"committed"}}
            fail("Parent timeout was swallowed")
        } catch(_:TimeoutCancellationException) { }
        assertEquals(0,reads)
    }
    @Test fun realOuterShellSuppressesAllChildOutputAndPreservesExactArgvAndExitStatus() {
        assumeTrue("Real POSIX shell is required for the Unix command contract",File("/bin/sh").canExecute())
        val arguments=TermuxOwnerChecks.silentCommandArguments("/bin/sh",listOf("-c",
            "printf 'fixture startup stdout'; printf 'fixture startup stderr' >&2; test \"$1\" = 'space ; dollar $ literal' && exit 7; exit 9",
            "fixture","space ; dollar $ literal"))
        val process=ProcessBuilder(listOf("/bin/sh")+arguments).start()
        try {
            assertTrue(process.waitFor(5,TimeUnit.SECONDS))
            assertEquals(7,process.exitValue())
            assertEquals("",process.inputStream.bufferedReader().readText())
            assertEquals("",process.errorStream.bufferedReader().readText())
        } finally {if(process.isAlive) process.destroyForcibly()}
    }
}
