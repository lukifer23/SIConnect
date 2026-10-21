package dev.siconnect.app.automation

import kotlinx.serialization.Serializable
import java.time.*

@Serializable enum class RecurrenceKind { ONCE, FIXED_INTERVAL, DAILY, WEEKDAYS, WEEKLY, MONTHLY }
@Serializable enum class MissedRunPolicy { SKIP, RUN_ONCE_IF_STILL_RELEVANT, COALESCE, MARK_MISSED }

/** Deliberately small recurrence language. Zone is pinned; travel never silently changes a task. */
@Serializable data class Recurrence(
    val kind: RecurrenceKind,
    val localTime: String? = null,
    val intervalMs: Long? = null,
    /** ISO weekdays: Monday=1, Sunday=7. WEEKLY requires exactly one. */
    val weekdays: List<Int> = emptyList(),
    /** MONTHLY skips months without this date (31 is not silently changed to month end). */
    val dayOfMonth: Int? = null,
) {
    fun validate() {
        when(kind) {
            RecurrenceKind.FIXED_INTERVAL -> require(intervalMs != null && intervalMs >= 900_000) { "Interval minimum is 15 minutes" }
            RecurrenceKind.DAILY, RecurrenceKind.WEEKDAYS, RecurrenceKind.WEEKLY, RecurrenceKind.MONTHLY -> LocalTime.parse(requireNotNull(localTime))
            else -> Unit
        }
        require(weekdays.distinct().size == weekdays.size && weekdays.all {it in 1..7}) { "Invalid weekdays" }
        if(kind == RecurrenceKind.WEEKDAYS) require(weekdays.isNotEmpty())
        if(kind == RecurrenceKind.WEEKLY) require(weekdays.size == 1)
        if(kind == RecurrenceKind.MONTHLY) require(dayOfMonth in 1..31)
        require(kind == RecurrenceKind.FIXED_INTERVAL || intervalMs == null) { "Interval belongs only to FIXED_INTERVAL" }
        require(kind in setOf(RecurrenceKind.WEEKDAYS,RecurrenceKind.WEEKLY) || weekdays.isEmpty())
        require(kind == RecurrenceKind.MONTHLY || dayOfMonth == null)
        require(kind !in setOf(RecurrenceKind.ONCE,RecurrenceKind.FIXED_INTERVAL) || localTime == null)
    }

    /** Strictly after max(slot, after). Gaps shift by the gap, overlaps use the earlier offset once. */
    fun nextAfter(slot: Long, after: Long, zoneId: String): Long? {
        validate()
        val boundary = maxOf(slot, after)
        val zone = ZoneId.of(zoneId)
        if(kind == RecurrenceKind.ONCE) return null
        if(kind == RecurrenceKind.FIXED_INTERVAL) {
            val interval = requireNotNull(intervalMs)
            return Math.addExact(slot, Math.multiplyExact(Math.floorDiv(Math.subtractExact(boundary,slot),interval)+1,interval))
        }
        val time=LocalTime.parse(localTime)
        val date=Instant.ofEpochMilli(boundary).atZone(zone).toLocalDate()
        // At most one year is enough for every supported calendar form, including February 29.
        for(offset in 0..366) {
            val day=date.plusDays(offset.toLong())
            val selected=when(kind) {
                RecurrenceKind.DAILY -> true
                RecurrenceKind.WEEKDAYS,RecurrenceKind.WEEKLY -> day.dayOfWeek.value in weekdays
                RecurrenceKind.MONTHLY -> day.dayOfMonth == dayOfMonth
                else -> false
            }
            if(selected) {
                val instant=day.atTime(time).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
                if(instant>boundary) return instant
            }
        }
        error("No recurrence within supported horizon")
    }

    companion object {
        fun oneTime(localDateTime:String,zoneId:String):Long = LocalDateTime.parse(localDateTime)
            .atZone(ZoneId.of(zoneId)).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
    }
}
