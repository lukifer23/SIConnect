package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.clocks.ClockContract
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.app.notifications.NotificationListenerAccess
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Explicit opt-in: one real disposable labeled timer; never dismisses preexisting user work. */
class ClockNativeDeviceTest {
    @Test fun managedTimerIsOwnedObservableAndCancelableWithoutReplacingExistingClockWork()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assertEquals("Coordinate this real disposable timer with the owner first","true",InstrumentationRegistry.getArguments().getString("allowClockTimerTest"))
        val g=instrumentation.targetContext.graph;g.ready.await()
        assertTrue("Managed Clock acceptance requires Android's actual system user; never target another profile",
            g.context.getSystemService(android.os.UserManager::class.java).isSystemUser)
        withTimeout(15000) {while(!g.privileged.available() || !NotificationListenerAccess.availability(g.context).isExecutable) delay(100)}
        assertTrue(g.settings.controls.value.remoteExecutionEnabled && g.settings.controls.value.privilegedEnabled)
        fun clockNotifications()=NotificationListenerAccess.requireConnected(g.context).metadata().filter {it.packageName==ClockContract.SAMSUNG_CLOCK && it.user==android.os.Process.myUserHandle()}
        assertTrue("Existing Clock work must be left untouched: remove no user timer automatically",clockNotifications().isEmpty())
        val originalGrants=g.agents.all().associate {it.grant.agentId to it.grant}
        val id="clock-native-test-${UUID.randomUUID()}";val other="clock-isolation-test-${UUID.randomUUID()}"
        fun fixture(agent:String)=AgentRecord(AgentGrant(agent,"Disposable managed Clock acceptance",scopes=listOf("clock.*"),
            autoApproveUpTo=RiskClass.MODERATE_IMPACT,allowSharedScreen=true,expiresAtMs=System.currentTimeMillis()+300000),"",System.currentTimeMillis(),tokenHint="Clock native acceptance only")
        g.agents.put(fixture(id));g.agents.put(fixture(other))
        var timerId:String?=null;var cleanupConfirmed=false
        suspend fun invoke(agent:String,capability:String,args:JsonObject)=g.engine.submit(InvocationRequest(agentId=agent,capabilityId="clock.$capability",arguments=args),Origin.REMOTE)
        suspend fun status():JsonObject {
            val r=invoke(id,"timer_status",buildJsonObject {timerId?.let {put("timerId",it)}})
            assertEquals(r.errorMessage,RequestState.SUCCEEDED,r.state)
            return requireNotNull(r.result).output.jsonObject
        }
        suspend fun cancel():RequestRecord {
            // Retry only a proven pre-input stale-reference refusal. Never repeat
            // accepted dispatch or an UNKNOWN_OUTCOME native action.
            var result:RequestRecord?=null
            for(attempt in 1..3) {
                result=invoke(id,"timer_cancel",buildJsonObject {put("timerId",requireNotNull(timerId))})
                if(result.state!=RequestState.FAILED || result.errorCode!="NOTIFICATION_CHANGED") break
            }
            return requireNotNull(result)
        }
        try {
            val startArgs=buildJsonObject {put("durationSeconds",600);put("label","Disposable acceptance");put("packageName",ClockContract.SAMSUNG_CLOCK)}
            val start=InvocationRequest(agentId=id,capabilityId="clock.timer_start",arguments=startArgs)
            val started=g.engine.submit(start,Origin.REMOTE)
            val owned=status().getValue("timers").jsonArray.singleOrNull()?.jsonObject
            timerId=owned?.get("timerId")?.jsonPrimitive?.content
            assertEquals("A dispatched timer is retained for owner cleanup if observation fails: ${started.errorCode}: ${started.errorMessage}",RequestState.SUCCEEDED,started.state)
            val observed=requireNotNull(owned)
            assertEquals(true,observed["notificationObserved"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(true,observed["cancellationSupported"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(false,observed["countdownPublished"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(false,observed["timerStartVerified"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(started,g.engine.submit(start,Origin.REMOTE))
            val executionCount=g.db.readableDatabase.rawQuery("SELECT count(*) FROM request_transitions WHERE request_id=? AND to_state='EXECUTING'",arrayOf(start.requestId)).use {it.moveToFirst();it.getInt(0)}
            assertEquals(1,executionCount)
            val blocked=invoke(id,"timer_start",startArgs)
            assertEquals(RequestState.FAILED,blocked.state)
            assertEquals("CAPABILITY_BLOCKED_PREEXISTING_CLOCK_WORK",blocked.errorCode)
            assertEquals(1,status().getValue("timers").jsonArray.size)
            val foreign=invoke(other,"timer_cancel",buildJsonObject {put("timerId",requireNotNull(timerId))})
            assertEquals(RequestState.FAILED,foreign.state);assertEquals("CLOCK_TIMER_NOT_FOUND",foreign.errorCode)
            assertEquals(true,status().getValue("timers").jsonArray.single().jsonObject["notificationObserved"]?.jsonPrimitive?.booleanOrNull)
            val cancelled=cancel()
            assertEquals(cancelled.errorMessage,RequestState.SUCCEEDED,cancelled.state)
            assertEquals(true,cancelled.result?.output?.jsonObject?.get("ownedNotificationRemovedObserved")?.jsonPrimitive?.booleanOrNull)
            withTimeout(5000) {while(clockNotifications().isNotEmpty()) delay(100)}
            cleanupConfirmed=true
            assertEquals(false,status().getValue("timers").jsonArray.single().jsonObject["notificationObserved"]?.jsonPrimitive?.booleanOrNull)
        } finally {
            // Discover even a start whose result was lost, but only this fixture's identities.
            if(!cleanupConfirmed) {
                runCatching {
                    val known=status().getValue("timers").jsonArray
                    timerId=timerId ?: known.singleOrNull()?.jsonObject?.get("timerId")?.jsonPrimitive?.content
                    if(known.isEmpty()) cleanupConfirmed=true // Journal always precedes any start IPC.
                    else if(timerId!=null) {
                        val own=known.singleOrNull {it.jsonObject["timerId"]?.jsonPrimitive?.content==timerId}?.jsonObject
                        if(own?.get("startPhase")?.jsonPrimitive?.content in setOf("PREPARED","NOT_DISPATCHED")) cleanupConfirmed=clockNotifications().isEmpty()
                        else if(own?.get("cancellationSupported")?.jsonPrimitive?.booleanOrNull==true) {
                            val cancelled=cancel()
                            if(cancelled.state==RequestState.SUCCEEDED && cancelled.result?.output?.jsonObject?.get("ownedNotificationRemovedObserved")?.jsonPrimitive?.booleanOrNull==true) {
                                withTimeout(5000) {while(clockNotifications().isNotEmpty()) delay(100)}
                                cleanupConfirmed=true
                            }
                        }
                    }
                }
            }
            g.agents.delete(other)
            if(cleanupConfirmed) g.agents.delete(id)
            else {
                g.revokeAgentFromOwner(id)
                fail("Disposable Clock timer identity retained/revoked for owner reconciliation: agent=$id timer=$timerId; no global dismissal or uncertain retry was attempted")
            }
            assertEquals(originalGrants,g.agents.all().associate {it.grant.agentId to it.grant})
            // Retain real core request and Clock-journal evidence; no owner record is deleted.
        }
    }
}
