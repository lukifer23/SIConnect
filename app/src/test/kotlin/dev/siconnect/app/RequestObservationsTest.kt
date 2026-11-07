package dev.siconnect.app

import dev.siconnect.app.ui.refreshesFor
import dev.siconnect.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestObservationsTest {
    private fun record(id:String="owned")=RequestRecord(id,id,"agent","device","device.status",1,JsonObject(emptyMap()),"hash",origin="REMOTE",
        state=RequestState.SUCCEEDED,createdAtMs=1,updatedAtMs=2,expiresAtMs=3)

    @Test fun updateDuringInitialFileReadIsObservedAndSubscriptionClosesOnCancellation()=runTest {
        val file=Files.createTempFile("request-observation-",".txt")
        try {
            Files.writeString(file,"EXECUTING")
            val events=MutableSharedFlow<RequestRecord>(extraBufferCapacity=1)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val values=mutableListOf<String>()
            val job=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                events.refreshesFor("owned").collect {
                    val read=Files.readString(file)
                    if(!entered.isCompleted) {entered.complete(Unit);release.await()}
                    values+=read
                }
            }
            runCurrent();entered.await();assertEquals(1,events.subscriptionCount.value)
            Files.writeString(file,"SUCCEEDED");events.emit(record());release.complete(Unit);runCurrent()
            assertEquals(listOf("EXECUTING","SUCCEEDED"),values)
            job.cancelAndJoin();assertEquals(0,events.subscriptionCount.value)
        } finally {Files.deleteIfExists(file)}
    }

    @Test fun repeatedOwnUpdatesCoalesceAndOtherRequestsDoNotReloadSelectedFile()=runTest {
        val events=MutableSharedFlow<RequestRecord>(extraBufferCapacity=1)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var reads=0
        val job=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            events.refreshesFor("owned").collect {reads++;if(reads==1) {entered.complete(Unit);release.await()}}
        }
        runCurrent();entered.await()
        repeat(1000) {events.emit(record())}
        release.complete(Unit);runCurrent();assertEquals(2,reads)
        repeat(10) {events.emit(record("other"))};runCurrent();assertEquals(2,reads)
        job.cancelAndJoin();assertEquals(0,events.subscriptionCount.value)
    }
}
