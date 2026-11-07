package dev.siconnect.app

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeStartupTest {
    @Test fun deferredFilesystemFailureDoesNotBlockOtherStagesAndRetryNeverRepeatsAuthorityRecovery()=runBlocking {
        val dir=Files.createTempDirectory("siconnect-startup-")
        try {
            val essential=dir.resolve("authority.txt");val missing=dir.resolve("missing/output.txt");val later=dir.resolve("later.txt")
            val startup=RuntimeStartup(listOf(
                StartupStep("authority","Authority recovery",true) {Files.writeString(essential,"exactly once")},
                StartupStep("maintenance","Saved maintenance",false) {Files.writeString(missing,"completed")},
                StartupStep("later","Unrelated stage",false) {Files.writeString(later,"ready")},
            ))
            startup.start()
            assertEquals(listOf("READY","FAILED","READY"),startup.stages.value.map {it.state})
            assertEquals("ready",Files.readString(later));assertFalse(Files.exists(missing))
            Files.createDirectories(missing.parent)
            val retries=coroutineScope {listOf(async {startup.retryDeferred()},async {startup.retryDeferred()}).awaitAll()}
            assertEquals(1,retries.flatten().size)
            assertEquals("completed",Files.readString(missing));assertEquals("exactly once",Files.readString(essential))
            assertEquals(listOf(1,2,1),startup.stages.value.map {it.attempts})
            assertTrue(startup.stages.value.all {it.state=="READY" && it.errorType==null})
        } finally {dir.toFile().deleteRecursively()}
    }

    @Test fun essentialFilesystemFailurePreservesDataAndRefusesInProcessReplay()=runBlocking {
        val dir=Files.createTempDirectory("siconnect-critical-startup-")
        try {
            val saved=dir.resolve("saved.txt");Files.writeString(saved,"owner data")
            val forbidden=dir.resolve("must-not-run.txt")
            val startup=RuntimeStartup(listOf(
                StartupStep("database","Database",true) {Files.newOutputStream(dir).use {it.write(1)}},
                StartupStep("later","Deferred task",false) {Files.writeString(forbidden,"wrong")},
            ))
            try {startup.start();fail("Essential failure was swallowed")} catch(_:java.io.IOException) { }
            assertEquals(listOf("FAILED","PENDING"),startup.stages.value.map {it.state})
            try {startup.retryDeferred();fail("Essential recovery was replayed")} catch(_:IllegalStateException) { }
            try {startup.start();fail("Startup ran twice")} catch(_:IllegalStateException) { }
            assertEquals(1,startup.stages.value.first().attempts)
            assertEquals("owner data",Files.readString(saved));assertFalse(Files.exists(forbidden))
        } finally {dir.toFile().deleteRecursively()}
    }

    @Test fun cancelledDeferredTaskRemainsExplicitAndOnlyRetriesWhenRequested()=runBlocking {
        val dir=Files.createTempDirectory("siconnect-interrupted-startup-")
        try {
            val entered=CompletableDeferred<Unit>();var complete=false
            val startup=RuntimeStartup(listOf(
                StartupStep("authority","Authority recovery",true) {Files.writeString(dir.resolve("authority"),"preserved")},
                StartupStep("deferred","Deferred stage",false) {if(!complete) {entered.complete(Unit);awaitCancellation()} else Files.writeString(dir.resolve("result"),"done")},
            ))
            val job=launch {startup.start()};entered.await();job.cancelAndJoin()
            assertEquals("INTERRUPTED",startup.stages.value.last().state);assertFalse(Files.exists(dir.resolve("result")))
            complete=true;assertEquals(listOf("deferred"),startup.retryDeferred())
            assertEquals(1,startup.stages.value.first().attempts);assertEquals("done",Files.readString(dir.resolve("result")))
        } finally {dir.toFile().deleteRecursively()}
    }
}
