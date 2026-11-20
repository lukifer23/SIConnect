package dev.siconnect.app

import android.app.Notification
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CountdownDeviceTest {
    @Test fun existingClockToolsStartReadAndCancelOneOwnedCountdown() = runBlocking {
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph
        g.ready.await()
        suspend fun clock(tool:String,args:JsonObject):JsonObject {
            val r=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="clock.timer_$tool",arguments=args),Origin.LOCAL_OWNER)
            assertEquals(r.errorMessage,RequestState.SUCCEEDED,r.state)
            return requireNotNull(r.result).output.jsonObject
        }
        val row=clock("start",buildJsonObject {put("durationSeconds",480);put("label","Countdown controls verification")})
        val id=row.getValue("timerId").jsonPrimitive.content
        val exact=buildJsonObject {put("timerId",id)}
        try {
            assertEquals("SICONNECT_COUNTDOWN",row.getValue("source").jsonPrimitive.content)
            assertEquals(480,row.getValue("durationSeconds").jsonPrimitive.int)
            assertEquals(id,clock("status",exact).getValue("timers").jsonArray.single().jsonObject.getValue("timerId").jsonPrimitive.content)
            assertTrue(clock("status",buildJsonObject {}).getValue("timers").jsonArray.any {it.jsonObject["timerId"]?.jsonPrimitive?.content==id})
            try { g.reminders.ownedCountdown("unrelated-test-agent",id); fail("Another agent obtained the timer") }
            catch(e:CapabilityException) { assertEquals("CLOCK_TIMER_NOT_FOUND",e.code) }
            assertEquals("CANCELLED",clock("cancel",exact).getValue("state").jsonPrimitive.content)
        } finally { g.reminders.cancel(id) }
    }

    @Test fun backgroundCountdownExpiresThroughItsOriginalDeliveryRequest() = runBlocking {
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph
        g.ready.await()
        assertTrue(g.reminders.countdownAvailability().isExecutable)
        val nm=g.context.getSystemService(NotificationManager::class.java)
        val args=buildJsonObject { put("durationSeconds",6); put("label","SIConnect countdown verification") }
        val start=InvocationRequest(agentId="local-owner",capabilityId="reminders.start_countdown",arguments=args)
        val saved=g.engine.submit(start,Origin.LOCAL_OWNER)
        assertEquals(saved.errorMessage,RequestState.SUCCEEDED,saved.state)
        val id=requireNotNull(saved.result).output.jsonObject.getValue("id").jsonPrimitive.content
        try {
            assertEquals(saved,g.engine.submit(start,Origin.LOCAL_OWNER))
            withTimeout(3000) {
                while(nm.activeNotifications.none { it.tag==id && it.notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN) }) delay(50)
            }
            val deliveryId="reminder:$id"
            withTimeout(25000) { while(g.requests.get(deliveryId)?.state!=RequestState.SUCCEEDED) delay(100) }
            assertTrue(nm.activeNotifications.any { it.tag==deliveryId && it.notification.channelId=="timer_alerts" })
        } finally {
            g.reminders.cancel(id)
            nm.cancel("reminder:$id",0)
        }
    }
}
