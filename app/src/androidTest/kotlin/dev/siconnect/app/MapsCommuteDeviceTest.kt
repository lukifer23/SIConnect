package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.capabilities.CommuteRouter
import dev.siconnect.core.ExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.UUID

/** Opt-in physical check: first prepare Maps for tomorrow 06:45 in America/Chicago.
 * Reads the real foreground route; never seeds Maps, location or a fabricated provider response.
 * This direct implementation check is not evidence of any remote client's scope grant.
 */
class MapsCommuteDeviceTest {
    @Test fun readsRealPreparedMorningRoute()=runBlocking {
        org.junit.Assume.assumeTrue("Opt in by selecting this physical test class explicitly",InstrumentationRegistry.getArguments().getString("class").orEmpty().contains("MapsCommuteDeviceTest"))
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph
        g.ready.await()
        // Instrumentation force-stops the normal app process; allow the external authorized
        // bootstrap to attach its real Binder to this new process. Never seed an authority.
        withTimeout(25_000) {while(!g.privileged.available() && dev.siconnect.app.accessibility.SemanticAccessibilityService.instance==null) delay(150)}
        val zone=ZoneId.of("America/Chicago")
        val departure=LocalDate.now(zone).plusDays(1).atTime(6,45).atZone(zone).toInstant().toEpochMilli()
        val id="maps-physical-"+UUID.randomUUID()
        val result=CommuteRouter(g).calculate(buildJsonObject {
            put("expectedDepartureAtMs",departure);put("timezone",zone.id)
        },ExecutionContext(id,id,"local-owner",g.settings.deviceId),open=false)
        assertEquals("GOOGLE_MAPS_SCREEN",result.getValue("provider").jsonPrimitive.content)
        assertEquals("MAPS_CURRENT_LOCATION",result.getValue("originSource").jsonPrimitive.content)
        assertEquals("PREDICTED_TRAVEL_TIME_RANGE",result.getValue("trafficStatus").jsonPrimitive.content)
        assertTrue(result.getValue("forecastDepartureTimeVerified").jsonPrimitive.boolean)
        assertTrue(result.getValue("durationSeconds").jsonPrimitive.long>0)
        assertTrue(result.getValue("distanceMeters").jsonPrimitive.double>0)
    }
}
