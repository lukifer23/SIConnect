package dev.siconnect.app

import dev.siconnect.app.capabilities.CalendarEventRules
import dev.siconnect.core.CapabilityException
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CalendarEventRulesTest {
    @Test fun typedRecurrenceProducesBoundedRule() {
        assertEquals("FREQ=WEEKLY;INTERVAL=2;COUNT=12", CalendarEventRules.recurrence("WEEKLY", 2, 12))
        for (invalid in listOf(Triple("YEARLY", 1L, 4L), Triple("DAILY", 0L, 4L), Triple("DAILY", 1L, -1L), Triple("MONTHLY", 1L, 367L))) {
            try { CalendarEventRules.recurrence(invalid.first, invalid.second, invalid.third); fail("Invalid recurrence accepted") }
            catch (e: CapabilityException) { assertEquals("INVALID_RECURRENCE", e.code) }
        }
    }
    @Test fun allDayUsesExclusiveUtcMidnights() {
        val start = Instant.parse("2026-10-20T00:00:00Z").toEpochMilli()
        CalendarEventRules.validateTimes(start, start + 86400000, "UTC", true)
        for ((time, zone) in listOf((start + 3600000) to "UTC", start to "America/Chicago")) {
            try { CalendarEventRules.validateTimes(time, time + 86400000, zone, true); fail("Invalid all-day boundary accepted") }
            catch (e: CapabilityException) { assertEquals("INVALID_ALL_DAY", e.code) }
        }
    }
    @Test fun invalidTimeAndTimezoneHaveSpecificErrors() {
        val cases = listOf(Triple(1000L, 1000L, "UTC"), Triple(-1000L, 1000L, "UTC"), Triple(1001L, 2000L, "UTC"), Triple(0L, 367L * 86400000, "UTC"))
        for ((start, end, zone) in cases) {
            try { CalendarEventRules.validateTimes(start, end, zone, false); fail("Invalid time accepted") }
            catch (e: CapabilityException) { assertEquals("INVALID_TIME", e.code) }
        }
        try { CalendarEventRules.validateTimes(0, 1000, "invented/timezone", false); fail("Invalid timezone accepted") }
        catch (e: CapabilityException) { assertEquals("INVALID_TIMEZONE", e.code) }
    }
    @Test fun timedEventKeepsRealInstantsAcrossDst() {
        val begin = Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()
        CalendarEventRules.validateTimes(begin, begin + 3600000, "America/Chicago", false)
        CalendarEventRules.validateTimes(begin, begin + 3600000, "Asia/Tokyo", false)
    }
}
