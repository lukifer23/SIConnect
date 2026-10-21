package dev.siconnect.app

import dev.siconnect.app.capabilities.*
import dev.siconnect.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

/** Controlled threshold fixtures; production workflow fetches real Open-Meteo forecasts. */
class WeatherCommuteTest {
    private val evaluated=Instant.parse("2026-10-20T23:00:00Z").toEpochMilli()
    private fun config(zone:String="America/Chicago",wake:String="06:30",departure:String="08:00")=buildJsonObject {
        put("latitude",0.0);put("longitude",0.0);put("timezone",zone);put("wakeTime",wake);put("departureTime",departure)
    }
    private fun plan()=WeatherCommute.plan(config(),evaluated,null)
    private fun forecast(p:WeatherCommutePlan,rain:Int=70,snow:Double=2.0,temp:Double=-2.0)=buildJsonObject {
        put("fetchedAtMs",evaluated);put("expiresAtMs",evaluated+900_000);put("stale",false)
        put("summaryScope","EXPLICIT_WINDOW");put("completeness","COMPLETE");put("forecastTimezone",p.timezone)
        put("windowStartMs",p.windowStartMs);put("windowEndMs",p.windowEndMs)
        put("maxRainProbability",rain);put("expectedSnowCm",snow);put("minimumTemperatureC",temp)
        put("weatherCodes",JsonArray(listOf(JsonPrimitive(71))))
        putJsonObject("units") {put("temperature","C");put("snowfall","cm");put("probability","percent")}
    }
    @Test fun configuredWakeIsUsedUnlessTheVisibleAlarmActuallyMatchesTargetDate() {
        val p=plan()
        assertEquals("2026-10-21",p.targetDate)
        assertEquals("CONFIGURED_FALLBACK",p.wakeSource)
        assertEquals(LocalTime.of(6,30),Instant.ofEpochMilli(p.wakeAtMs).atZone(ZoneId.of(p.timezone)).toLocalTime())
        val alarm=ZonedDateTime.of(LocalDate.of(2026,10,21),LocalTime.of(6,8),ZoneId.of(p.timezone)).toInstant().toEpochMilli()
        assertEquals(alarm,WeatherCommute.plan(config(),evaluated,alarm).wakeAtMs)
        assertEquals(p.wakeAtMs,WeatherCommute.plan(config(),evaluated,alarm+86_400_000).wakeAtMs)
        assertEquals(p.wakeAtMs,WeatherCommute.plan(config(),evaluated,p.departureAtMs+60_000).wakeAtMs)
        assertEquals(p.deduplicationKey,WeatherCommute.plan(config(),evaluated+60_000,null).deduplicationKey)
    }
    @Test fun snowColdAndRainProduceOnlyTheConfiguredDeterministicActions() {
        val p=plan();val decision=WeatherCommute.decide(p,forecast(p),evaluated+1)
        assertEquals("ACTIONABLE",decision.getValue("status").jsonPrimitive.content)
        assertTrue(decision.getValue("notifyEvening").jsonPrimitive.boolean)
        assertTrue(decision.getValue("createMorningReminder").jsonPrimitive.boolean)
        assertTrue(decision.getValue("notificationText").jsonPrimitive.content.contains("Road and traffic conditions have not been checked"))
        val warm=WeatherCommute.decide(p,forecast(p,rain=0,snow=0.2,temp=10.0),evaluated+1)
        assertEquals("NO_ACTION",warm.getValue("status").jsonPrimitive.content)
        assertFalse(warm.getValue("notifyEvening").jsonPrimitive.boolean)
        assertFalse(warm.getValue("createMorningReminder").jsonPrimitive.boolean)
    }
    @Test fun staleMissingAndMismatchedWindowsCannotBecomeBenignZeroWeather() {
        val p=plan();val original=forecast(p)
        for((key,value) in listOf("stale" to JsonPrimitive(true),"expectedSnowCm" to JsonNull,
            "windowEndMs" to JsonPrimitive(p.windowEndMs+3_600_000),"completeness" to JsonPrimitive("PARTIAL"))) {
            val d=WeatherCommute.decide(p,JsonObject(original+Pair(key,value)),evaluated+1)
            assertFalse(d.getValue("notifyEvening").jsonPrimitive.boolean)
            assertFalse(d.getValue("createMorningReminder").jsonPrimitive.boolean)
            assertNotEquals("NO_ACTION",d.getValue("status").jsonPrimitive.content)
        }
        assertEquals("STALE_OR_UNDATED_FORECAST",WeatherCommute.decide(p,original,evaluated+900_000).getValue("status").jsonPrimitive.content)
    }
    @Test fun gapTimeUsesRealZoneResolutionAndNeverEditsClockAlarms() {
        val spring=Instant.parse("2026-03-08T00:00:00Z").toEpochMilli()
        val p=WeatherCommute.plan(config(wake="02:30",departure="04:30"),spring,null)
        val wake=Instant.ofEpochMilli(p.wakeAtMs).atZone(ZoneId.of(p.timezone))
        assertEquals(LocalDate.of(2026,3,8),wake.toLocalDate())
        assertEquals(LocalTime.of(3,30),wake.toLocalTime())
        assertEquals("CONFIGURED_FALLBACK",p.wakeSource)
        assertTrue(WeatherCommute.skill().steps.none {it.capability.contains("alarm") && it.capability!="alarms.next"})
    }
    @Test fun actualSkillBranchesUseExistingConditionsAndPreserveDedupeReferences() {
        val skill=WeatherCommute.skill();val p=plan();val decision=WeatherCommute.decide(p,forecast(p),evaluated+1)
        Schema.checkSupported(skill.inputs)
        assertTrue(Schema.validate(skill.inputs,config()).isEmpty())
        val scope=mapOf("steps" to buildJsonObject {putJsonObject("decision") {put("output",decision)}})
        val evening=skill.steps.single {it.id=="evening"};val morning=skill.steps.single {it.id=="morning"}
        assertEquals("notifications.post",evening.capability)
        assertEquals("reminders.create",morning.capability)
        assertTrue(Conditions.eval(evening.`when`!!,scope));assertTrue(Conditions.eval(morning.`when`!!,scope))
        val benign=WeatherCommute.decide(p,forecast(p,rain=0,snow=0.0,temp=15.0),evaluated+1)
        val benignScope=mapOf("steps" to buildJsonObject {putJsonObject("decision") {put("output",benign)}})
        assertFalse(Conditions.eval(evening.`when`!!,benignScope));assertFalse(Conditions.eval(morning.`when`!!,benignScope))
        assertEquals("\${steps.plan.output.deduplicationKey}",evening.args.getValue("deduplicationKey").jsonPrimitive.content)
    }
}
