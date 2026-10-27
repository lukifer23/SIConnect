package dev.siconnect.app

import dev.siconnect.app.capabilities.*
import dev.siconnect.app.data.CommuteProfile
import dev.siconnect.core.CapabilityException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MapsRouteReadingTest {
    // Structure observed on the physical Maps route screen; endpoints omitted from fixture.
    private fun labels()=listOf(RouteLabel("Drive"),RouteLabel("Your location","Start location, Your location"),
        RouteLabel("Destination","Destination, Destination"),RouteLabel("57 min",top=200,bottom=260),
        RouteLabel("(51\u00a0mi)",top=200,bottom=260),RouteLabel("Best route now due to traffic conditions"),
        RouteLabel("22 hr",top=100,bottom=140),RouteLabel("Transit mode: 57 minutes"))

    @Test fun readsSelectedRouteInsteadOfModeTabs() {
        val reading=MapsRouteReading.read(labels(),1000)
        assertEquals(3420L,reading.getValue("durationSeconds").jsonPrimitive.long)
        assertEquals(82076.544,reading.getValue("distanceMeters").jsonPrimitive.double,0.001)
        assertFalse(reading.getValue("forecastDepartureTimeVerified").jsonPrimitive.boolean)
        assertEquals("MAPS_CURRENT_LOCATION",reading.getValue("originSource").jsonPrimitive.content)
    }
    @Test fun rejectsAmbiguousCardsAndUsesRangeUpperBound() {
        assertFalse(MapsRouteReading.destinationMatches("Confirmed workplace","Work"))
        assertTrue(MapsRouteReading.destinationMatches("Confirmed workplace","Confirmed Workplace"))
        assertEquals(3300L to 5400L,MapsRouteReading.durationRange("Typically 55 min – 1 hr 30 min"))
        assertEquals(3000L to 3900L,MapsRouteReading.durationRange("Typically 50 min to 1 hr 5 min"))
        try {MapsRouteReading.read(labels()+listOf(RouteLabel("1 hr",top=400,bottom=460),RouteLabel("52 mi",top=400,bottom=460)),1000);fail("Ambiguous routes must not be selected")}
        catch(e:CapabilityException) {assertEquals("ROUTE_SCREEN_UNREADABLE",e.code)}
    }
    @Test fun leaveAndWakeCanFallOnPreviousLocalDate() {
        val arrival=Instant.parse("2026-10-28T05:30:00Z").toEpochMilli()
        val profile=CommuteProfile(timezone="America/Chicago",preparationMinutes=45,bufferMinutes=10)
        val timing=CommuteTiming.calculate(profile,buildJsonObject {put("arrivalAtMs",arrival)},3600.0,arrival-86_400_000)
        assertEquals(arrival-70*60_000,timing.getValue("leaveByAtMs").jsonPrimitive.long)
        assertEquals(arrival-115*60_000,timing.getValue("wakeByAtMs").jsonPrimitive.long)
        assertTrue(timing.getValue("wakeByLocal").jsonPrimitive.content.startsWith("2026-10-27T22:35"))
    }
}
