package dev.siconnect.app

import dev.siconnect.app.capabilities.ForecastWindow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

/** Controlled forecast fixtures test calculations, never production weather observations. */
class ForecastWindowTest {
    private fun fixture(day:String,zone:String):JsonObject {
        val start=LocalDate.parse(day).atStartOfDay(ZoneId.of(zone)).toInstant()
        return buildJsonObject {putJsonObject("hourly") {
            put("time",JsonArray((0..48).map {JsonPrimitive(start.plusSeconds(it*3600L).epochSecond)}))
            for(key in listOf("temperature_2m","precipitation_probability","precipitation","snowfall","weather_code"))
                put(key,JsonArray((0..48).map {JsonPrimitive(if(key=="snowfall") {if(it in 7..9) 1 else 10} else it)}))
        }}
    }
    @Test fun windowExcludesSnowOutsideCommuteAndUsesPrecedingHourEndpoints() {
        val bounds=ForecastWindow.bounds("2026-10-20","America/Chicago",6,9)
        val r=ForecastWindow.summarize(fixture("2026-10-20","America/Chicago"),bounds.first,bounds.second)
        assertEquals(3.0,r.getValue("expectedSnowCm").jsonPrimitive.double,0.0)
        assertEquals(6.0,r.getValue("minimumTemperatureC").jsonPrimitive.double,0.0)
        assertEquals(9.0,r.getValue("maxRainProbability").jsonPrimitive.double,0.0)
        assertEquals("COMPLETE",r.getValue("completeness").jsonPrimitive.content)
    }
    @Test fun daylightSavingWindowUsesActualElapsedHours() {
        val spring=ForecastWindow.bounds("2026-03-08","America/Chicago",0,6)
        val autumn=ForecastWindow.bounds("2026-11-01","America/Chicago",0,6)
        assertEquals(5,Duration.between(spring.first,spring.second).toHours())
        assertEquals(7,Duration.between(autumn.first,autumn.second).toHours())
    }
    @Test fun missingSnowIsUnknownRatherThanZero() {
        val body=fixture("2026-10-20","UTC").toMutableMap()
        val hourly=body.getValue("hourly").jsonObject.toMutableMap()
        hourly["snowfall"]=JsonArray(hourly.getValue("snowfall").jsonArray.toMutableList().also {it[7]=JsonNull})
        body["hourly"]=JsonObject(hourly)
        val bounds=ForecastWindow.bounds("2026-10-20","UTC",6,9)
        val r=ForecastWindow.summarize(JsonObject(body),bounds.first,bounds.second)
        assertEquals("PARTIAL",r.getValue("completeness").jsonPrimitive.content)
        assertEquals(JsonNull,r["expectedSnowCm"])
    }
}
