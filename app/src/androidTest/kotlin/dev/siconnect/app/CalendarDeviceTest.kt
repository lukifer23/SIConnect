package dev.siconnect.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.capabilities.CalendarOperations
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.app.data.Settings
import dev.siconnect.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** All mutations opt in to a specific, explicitly designated SIConnect test calendar. No account creation. */
@RunWith(AndroidJUnit4::class)
class CalendarDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun actualPermissionStateControlsCalendarAvailability() = runBlocking {
        val operations = CalendarOperations(context).capabilities()
        val read = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val write = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        for (op in operations) {
            val mutation = op.descriptor.risk > RiskClass.READ_ONLY
            assertEquals(if (read && (!mutation || write)) Availability.IMPLEMENTED else Availability.PERMISSION_REQUIRED, op.availability())
            Schema.checkSupported(op.descriptor.inputSchema)
            Schema.checkSupported(requireNotNull(op.descriptor.outputSchema))
            if (!read || mutation && !write) {
                try { op.execute(buildJsonObject {}, ExecutionContext("calendar-permission-fixture", "calendar-permission-fixture", "local-owner", "test")); fail("Missing permission accepted") }
                catch (e: CapabilityException) { assertEquals("PERMISSION_REQUIRED", e.code) }
            }
        }
        if (read) {
            val g = context.graph; g.ready.await()
            val result = g.engine.submit(InvocationRequest(agentId = "local-owner", capabilityId = "calendar.list_calendars", arguments = buildJsonObject { put("limit", 5) }), Origin.LOCAL_OWNER)
            assertEquals(result.errorMessage, RequestState.SUCCEEDED, result.state)
            assertTrue(result.result!!.output.jsonObject.getValue("calendars").jsonArray.size <= 5)
        }
    }

    @Test fun designatedTestCalendarRoundTrip() = runBlocking {
        val calendarId = InstrumentationRegistry.getArguments().getString("calendarTestCalendarId")?.toLongOrNull()
        assumeTrue("Requires explicitly selected dedicated test calendar; no personal calendar is selected automatically", calendarId != null && calendarId > 0)
        assumeTrue("Calendar permission must be explicitly granted", context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED)
        val g = context.graph; g.ready.await()
        val agent = "calendar-fixture:${UUID.randomUUID()}"; val token = Settings.randomToken("test_")
        g.agents.put(AgentRecord(AgentGrant(agent, "Disposable calendar fixture", scopes = listOf("calendar.*"), autoApproveUpTo = RiskClass.MODERATE_IMPACT), Settings.sha256Hex(token), System.currentTimeMillis(), tokenHint = "test only"))
        val created = mutableListOf<Long>()
        suspend fun call(id: String, fields: JsonObject): RequestRecord = g.remoteInvoke(buildJsonObject { put("capabilityId", id); put("arguments", fields) }, token)
        fun eventArgs(id: Long, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("calendarId", calendarId!!); put("eventId", id); extra() }
        fun succeeded(record: RequestRecord): JsonObject { assertEquals(record.errorMessage, RequestState.SUCCEEDED, record.state); return record.result!!.output.jsonObject }
        try {
            val rows = succeeded(call("calendar.list_calendars", buildJsonObject { put("limit", 100) })).getValue("calendars").jsonArray
            val selected = rows.map { it.jsonObject }.singleOrNull { it["calendarId"]?.jsonPrimitive?.longOrNull == calendarId }
            assumeTrue("Explicit test calendar must be writable and named SIConnect test...", selected != null && selected.getValue("displayName").jsonPrimitive.content.startsWith("SIConnect test", ignoreCase = true) && selected.getValue("writable").jsonPrimitive.boolean)
            val start = (System.currentTimeMillis() / 1000 + 86400) * 1000
            val createdRequest = call("calendar.create_event", buildJsonObject { put("calendarId", calendarId!!); put("title", "Disposable SIConnect provider fixture"); put("startAtMs", start); put("endAtMs", start + 3600000); put("timezone", "America/Chicago") })
            val eventId = succeeded(createdRequest).getValue("event").jsonObject.getValue("eventId").jsonPrimitive.long; created += eventId
            assertEquals("LOCAL_PROVIDER_WRITE", createdRequest.result!!.output.jsonObject.getValue("writeStatus").jsonPrimitive.content)
            val reread = succeeded(call("calendar.get_event", eventArgs(eventId))).getValue("event").jsonObject
            assertEquals(start, reread.getValue("startAtMs").jsonPrimitive.long)
            succeeded(call("calendar.update_event", eventArgs(eventId) { put("title", "Updated SIConnect provider fixture") }))
            assertEquals("Updated SIConnect provider fixture", succeeded(call("calendar.get_event", eventArgs(eventId))).getValue("event").jsonObject.getValue("title").jsonPrimitive.content)
            val occurrenceList = succeeded(call("calendar.list_events", buildJsonObject { put("calendarId", calendarId!!); put("startAtMs", start - 1000); put("endAtMs", start + 86400000) })).getValue("events").jsonArray
            assertTrue(occurrenceList.any { it.jsonObject.getValue("eventId").jsonPrimitive.long == eventId })
            val reminder = succeeded(call("calendar.add_reminder", eventArgs(eventId) { put("minutesBefore", 5) })).getValue("reminderId").jsonPrimitive.long
            assertTrue(succeeded(call("calendar.list_reminders", eventArgs(eventId))).getValue("reminders").jsonArray.any { it.jsonObject.getValue("reminderId").jsonPrimitive.long == reminder })
            succeeded(call("calendar.remove_reminder", eventArgs(eventId) { put("reminderId", reminder) }))
            for (repeat in listOf(false, true)) {
                val utcStart = start / 86400000 * 86400000
                val row = succeeded(call("calendar.create_event", buildJsonObject {
                    put("calendarId", calendarId!!); put("title", "Disposable SIConnect recurrence fixture"); put("startAtMs", utcStart); put("endAtMs", utcStart + 86400000); put("timezone", "UTC"); put("allDay", true)
                    if (repeat) { put("repeat", "DAILY"); put("repeatCount", 3) }
                })).getValue("event").jsonObject
                created += row.getValue("eventId").jsonPrimitive.long
                assertTrue(row.getValue("allDay").jsonPrimitive.boolean)
                if (repeat) assertEquals("FREQ=DAILY;INTERVAL=1;COUNT=3", row.getValue("recurrenceRule").jsonPrimitive.content)
            }
            for (id in created.toList()) { succeeded(call("calendar.delete_event", eventArgs(id) { put("applyToSeries", true) })); created.remove(id); assertEquals("EVENT_NOT_FOUND", call("calendar.get_event", eventArgs(id)).errorCode) }
        } finally {
            // Only IDs created by this fixture are removed; no arbitrary calendar rows are touched.
            created.forEach { id -> call("calendar.delete_event", eventArgs(id) { put("applyToSeries", true) }) }
            g.agents.delete(agent)
        }
    }
}
