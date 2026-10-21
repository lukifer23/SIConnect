package dev.siconnect.app

import dev.siconnect.app.automation.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class RecurrenceTest {
    private val zone="America/Chicago"
    private fun at(t:String)=ZonedDateTime.parse(t).toInstant().toEpochMilli()
    @Test fun dailyKeepsLocalTimeAcrossSpringAndFall() {
        val r=Recurrence(RecurrenceKind.DAILY,"07:00")
        for((start,expected,hours) in listOf(Triple("2026-03-07T07:00-06:00[America/Chicago]","2026-03-08T07:00-05:00[America/Chicago]",23),Triple("2026-10-31T07:00-05:00[America/Chicago]","2026-11-01T07:00-06:00[America/Chicago]",25))) {
            val next=r.nextAfter(at(start),at(start),zone)!!
            assertEquals(at(expected),next);assertEquals(hours*3600000L,next-at(start))
        }
    }
    @Test fun springGapShiftsByGapWithoutShiftingFollowingDay() {
        val r=Recurrence(RecurrenceKind.DAILY,"02:30")
        val start=at("2026-03-07T02:30-06:00[America/Chicago]")
        val next=r.nextAfter(start,start,zone)!!
        assertEquals(at("2026-03-08T03:30-05:00[America/Chicago]"),next)
        assertEquals(at("2026-03-09T02:30-05:00[America/Chicago]"),r.nextAfter(next,next,zone))
    }
    @Test fun overlapRunsEarlierOffsetOnlyOnce() {
        val r=Recurrence(RecurrenceKind.DAILY,"01:30")
        val start=at("2026-10-31T01:30-05:00[America/Chicago]")
        val first=r.nextAfter(start,start,zone)!!
        assertEquals(at("2026-11-01T01:30-05:00[America/Chicago]"),first)
        assertEquals(at("2026-11-02T01:30-06:00[America/Chicago]"),r.nextAfter(first,first,zone))
    }
    @Test fun selectedWeekdaysAndWeeklySkipWeekend() {
        val friday=at("2026-10-23T07:00-05:00[America/Chicago]")
        assertEquals(at("2026-10-26T07:00-05:00[America/Chicago]"),Recurrence(RecurrenceKind.WEEKDAYS,"07:00",weekdays=listOf(1,2,3,4,5)).nextAfter(friday,friday,zone))
        assertEquals(at("2026-10-30T07:00-05:00[America/Chicago]"),Recurrence(RecurrenceKind.WEEKLY,"07:00",weekdays=listOf(5)).nextAfter(friday,friday,zone))
    }
    @Test fun monthlySkipsInvalidDateAndHandlesLeapYear() {
        val r=Recurrence(RecurrenceKind.MONTHLY,"08:00",dayOfMonth=31)
        val jan=at("2026-01-31T08:00-06:00[America/Chicago]")
        assertEquals(at("2026-03-31T08:00-05:00[America/Chicago]"),r.nextAfter(jan,jan,zone))
        val leap=Recurrence(RecurrenceKind.MONTHLY,"08:00",dayOfMonth=29)
        val start=at("2028-01-29T08:00-06:00[America/Chicago]")
        assertEquals(at("2028-02-29T08:00-06:00[America/Chicago]"),leap.nextAfter(start,start,zone))
    }
    @Test fun legacyIntervalRemainsAnchoredAndCoalesces() {
        val start=at("2026-03-07T07:00-06:00[America/Chicago]")
        val a=Automation("fixture","diagnostics.snapshot",start,intervalMs=86400000)
        assertEquals(start+3*86400000,a.nextAfter(start+2*86400000+500))
        assertEquals(8,Instant.ofEpochMilli(a.nextAfter(start)!!).atZone(ZoneId.of(zone)).hour)
    }
    @Test fun timezoneIsExplicitAndOneTimeIsTerminal() {
        val t=Recurrence.oneTime("2026-10-20T09:30","Asia/Tokyo")
        assertEquals(Instant.parse("2026-10-20T00:30:00Z").toEpochMilli(),t)
        assertNull(Recurrence(RecurrenceKind.ONCE).nextAfter(t,t,zone))
    }
    @Test fun malformedRulesAndOverflowFailExplicitly() {
        for(r in listOf(Recurrence(RecurrenceKind.WEEKLY,"07:00"),Recurrence(RecurrenceKind.MONTHLY,"07:00",dayOfMonth=32),Recurrence(RecurrenceKind.DAILY,"25:00"),Recurrence(RecurrenceKind.FIXED_INTERVAL,intervalMs=1))) {
            assertTrue(runCatching {r.validate()}.isFailure)
        }
        assertTrue(runCatching {Recurrence(RecurrenceKind.FIXED_INTERVAL,intervalMs=900000).nextAfter(Long.MAX_VALUE-10,Long.MAX_VALUE,"UTC")}.isFailure)
    }
}
