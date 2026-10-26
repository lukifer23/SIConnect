package dev.siconnect.app.capabilities

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Process
import android.os.CancellationSignal
import android.provider.CalendarContract
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId

/** Real Calendar Provider operations. Provider readback never implies server sync or invitation delivery. */
fun registerCalendarCapabilities(g: RuntimeGraph) {
    CalendarOperations(g.context).capabilities().forEach(g.registry::register)
}

internal class CalendarOperations(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val lock = Mutex()
    private val queryCancellation = ThreadLocal<CancellationSignal>()
    private val events = CalendarContract.Events.CONTENT_URI
    private val calendars = CalendarContract.Calendars.CONTENT_URI
    private val reminders = CalendarContract.Reminders.CONTENT_URI
    private val eventProjection = arrayOf("_id", "calendar_id", "title", "description", "eventLocation", "dtstart", "dtend", "duration", "eventTimezone", "allDay", "rrule", "rdate", "exrule", "exdate", "original_id", "originalInstanceTime", "eventStatus", "availability", "accessLevel", "hasAlarm", "dirty", "deleted")

    private fun permitted(write: Boolean) = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        (!write || context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED)

    private fun query(uri: Uri, projection: Array<String>, selection: String? = null, args: Array<String>? = null, order: String? = null): Cursor =
        resolver.query(uri, projection, selection, args, order, queryCancellation.get()) ?: throw CapabilityException("PROVIDER_UNAVAILABLE", "Calendar Provider returned no cursor")

    private fun Cursor.text(name: String, max: Int = 8000): String? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let { getString(it)?.take(max) }
    private fun Cursor.number(name: String): Long? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)
    private fun JsonObject.id(name: String) = long(name, -1).also { if (it <= 0) throw CapabilityException("INVALID_ID", "$name must be positive") }

    private fun calendar(id: Long, write: Boolean = false): JsonObject = query(calendars,
        arrayOf("_id", "calendar_displayName", "account_name", "account_type", "calendar_access_level", "visible", "sync_events", "calendar_timezone", "allowedReminders", "maxReminders"), "_id=?", arrayOf(id.toString())).use { c ->
        if (!c.moveToFirst()) throw CapabilityException("CALENDAR_NOT_FOUND", "Selected calendar is missing or inaccessible")
        val access = c.number("calendar_access_level") ?: 0
        if (write && access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) throw CapabilityException("CALENDAR_READ_ONLY", "Selected calendar does not permit event changes")
        buildJsonObject {
            put("calendarId", id); put("displayName", c.text("calendar_displayName", 500) ?: "")
            c.text("account_name", 500)?.let { put("accountName", it) }; c.text("account_type", 500)?.let { put("accountType", it) }
            put("accessLevel", access); put("writable", access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
            put("visible", c.number("visible") == 1L); put("syncEnabled", c.number("sync_events") == 1L)
            c.text("calendar_timezone", 100)?.let { put("timezone", it) }
            c.text("allowedReminders", 100)?.let { put("allowedReminderMethods", it) }; c.number("maxReminders")?.let { put("maxReminders", it) }
        }
    }

    private fun event(calendarId: Long, eventId: Long): JsonObject = query(events, eventProjection,
        "_id=? AND calendar_id=? AND deleted=0", arrayOf(eventId.toString(), calendarId.toString())).use { c ->
        if (!c.moveToFirst()) throw CapabilityException("EVENT_NOT_FOUND", "Event does not exist in the selected calendar")
        buildJsonObject {
            put("eventId", eventId); put("calendarId", calendarId)
            for ((column, field) in listOf("title" to "title", "description" to "description", "eventLocation" to "location", "eventTimezone" to "timezone", "duration" to "duration", "rrule" to "recurrenceRule", "rdate" to "recurrenceDates", "exrule" to "exceptionRule", "exdate" to "exceptionDates")) c.text(column)?.let { put(field, it) }
            for ((column, field) in listOf("dtstart" to "startAtMs", "dtend" to "endAtMs", "original_id" to "originalEventId", "originalInstanceTime" to "originalInstanceAtMs", "eventStatus" to "status", "availability" to "availability", "accessLevel" to "accessLevel")) c.number(column)?.let { put(field, it) }
            put("allDay", c.number("allDay") == 1L); put("hasReminder", c.number("hasAlarm") == 1L)
            put("localDirty", c.number("dirty") == 1L)
            put("textLimitChars", 8000)
            put("truncatedTextFields", JsonArray(listOf("title", "description", "eventLocation", "rrule", "rdate", "exrule", "exdate").filter { column ->
                val index = c.getColumnIndex(column)
                index >= 0 && !c.isNull(index) && c.getString(index).length > 8000
            }.map(::JsonPrimitive)))
            put("serverSync", "UNVERIFIED"); put("attendeeDelivery", "UNVERIFIED")
        }
    }

    private fun envelope(fields: JsonObject, mutation: Boolean = false) = buildJsonObject {
        fields.forEach { (key, value) -> put(key, value) }
        put("source", "ANDROID_CALENDAR_PROVIDER"); put("observedAtMs", System.currentTimeMillis())
        if (mutation) { put("writeStatus", "LOCAL_PROVIDER_WRITE"); put("serverSync", "UNVERIFIED"); put("attendeeDelivery", "UNVERIFIED") }
    }

    private fun eventIds() = objectSchema { integer("calendarId", "Explicit calendar identifier from calendar.list_calendars", true, min = 1); integer("eventId", "Exact event or recurring-series identifier", true, min = 1) }
    private fun ObjectSchemaBuilder.timeFields(required: Boolean) {
        integer("startAtMs", "Event start Unix milliseconds; all-day dates use UTC midnight", required, min = 0)
        integer("endAtMs", "Exclusive event end Unix milliseconds; required with start", required, min = 1)
        string("timezone", "IANA timezone; UTC required for all-day events", required, maxLength = 100)
        boolean("allDay", "All-day event; requires UTC midnight start/end")
    }

    private fun temporalValues(a: JsonObject): ContentValues = ContentValues().apply {
        val start = a.long("startAtMs", -1); val end = a.long("endAtMs", -1)
        val zone = a["timezone"]?.jsonPrimitive?.content ?: throw CapabilityException("INVALID_TIME", "timezone is required with start/end")
        val allDay = a["allDay"]?.jsonPrimitive?.booleanOrNull ?: false
        CalendarEventRules.validateTimes(start, end, zone, allDay)
        put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, end)
        put(CalendarContract.Events.EVENT_TIMEZONE, zone); put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
    }

    private fun verified(result: JsonObject, detail: String) = CapabilityResult(envelope(result, true), BackendKind.NATIVE, "uid=${Process.myUid()}", VerificationStatus.VERIFIED, detail)

    fun capabilities(): List<Capability> {
        val output = objectSchema {
            string("source", "Authoritative local data source", true); integer("observedAtMs", "Observation timestamp", true)
            obj("event", "Provider event readback"); obj("calendar", "Selected calendar metadata")
            for (name in listOf("calendars", "events", "reminders", "attendees")) prop(name, false, buildJsonObject { put("type", "array"); putJsonObject("items") { put("type", "object") }; put("maxItems", 100) })
            boolean("hasMore", "Additional rows omitted by the requested limit"); integer("nextOffset", "Bounded continuation offset")
            integer("calendarId", "Calendar identifier"); integer("eventId", "Event identifier"); integer("reminderId", "Reminder identifier")
            boolean("deleted", "Local absence verified"); string("writeStatus", "Local provider write only")
            string("serverSync", "No server acknowledgement is inferred"); string("attendeeDelivery", "No invitation delivery is inferred")
        }
        fun op(id: String, title: String, input: JsonObject, write: Boolean = false, body: (JsonObject) -> CapabilityResult): Capability = object : Capability {
            override val descriptor = CapabilityDescriptor(id, title = title,
                description = "$title using Android Calendar Provider in the background. Select an explicit calendar ID. Local writes/readback do not prove account synchronization or invitation delivery. Recurring event IDs refer to entire series; modifying a series requires applyToSeries=true.",
                inputSchema = input, outputSchema = output, permissions = if (write) listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) else listOf(Manifest.permission.READ_CALENDAR),
                backend = BackendKind.NATIVE, risk = if (write) RiskClass.MODERATE_IMPACT else RiskClass.READ_ONLY,
                sideEffects = if (write) listOf("Changes the selected calendar's local provider data; its account may synchronize this change") else emptyList(),
                interaction = InteractionMode.BACKGROUND, verification = if (write) "Local provider readback; external synchronization and attendee delivery unverified" else "Local provider query")
            override fun availability() = if (permitted(write)) Availability.IMPLEMENTED else Availability.PERMISSION_REQUIRED
            override fun availabilityDetail() = if (permitted(write)) null else "Grant calendar access in SIConnect permissions; no special access is bypassed"
            override fun assess(args: JsonObject) = RiskAssessment(descriptor.risk, title, "calendar=${args["calendarId"] ?: "all accessible"}; event=${args["eventId"] ?: "new"}", descriptor.sideEffects, externalCommunication = write)
            override suspend fun execute(args: JsonObject, ctx: ExecutionContext): CapabilityResult = withContext(Dispatchers.IO) { lock.withLock {
                val signal=CancellationSignal()
                withContext(queryCancellation.asContextElement(signal)) {
                    dev.siconnect.app.backend.cancellableIo(signal::cancel) {
                        if (!permitted(write)) throw CapabilityException("PERMISSION_REQUIRED", "Calendar permission was revoked")
                        try { body(args) } catch (e: SecurityException) { throw CapabilityException("PERMISSION_REQUIRED", "Calendar Provider denied this operation") }
                    }
                }
            }
            }
        }
        fun read(out: JsonObject) = CapabilityResult(envelope(out), BackendKind.NATIVE, "uid=${Process.myUid()}")
        fun seriesCheck(a: JsonObject, old: JsonObject) {
            if ((!old["recurrenceRule"]?.jsonPrimitive?.content.isNullOrBlank() || !old["recurrenceDates"]?.jsonPrimitive?.content.isNullOrBlank()) && a["applyToSeries"]?.jsonPrimitive?.booleanOrNull != true)
                throw CapabilityException("SERIES_CONFIRMATION_REQUIRED", "This ID represents an entire recurring series; explicitly set applyToSeries=true")
        }
        return listOf(
            op("calendar.list_calendars", "List accessible calendars", objectSchema { integer("limit", "Maximum rows", min = 1, max = 100) }) { a ->
                val limit = a.long("limit", 50).toInt()
                val ids = query(calendars, arrayOf("_id"), order = "_id ASC").use { c -> buildList { while (size <= limit && c.moveToNext()) add(c.getLong(0)) } }
                read(buildJsonObject { put("calendars", JsonArray(ids.take(limit).map { calendar(it) })); put("hasMore", ids.size > limit) })
            },
            op("calendar.list_events", "List calendar occurrences in a bounded date range", objectSchema {
                integer("calendarId", "Explicit calendar identifier", true, min = 1); integer("startAtMs", "Inclusive range start", true, min = 0); integer("endAtMs", "Exclusive range end; maximum 31 days", true, min = 1)
                integer("limit", "Maximum occurrences", min = 1, max = 100); integer("offset", "Continuation offset; maximum 10000", min = 0, max = 10000)
            }) { a ->
                val cid = a.id("calendarId"); calendar(cid)
                val start = a.long("startAtMs", -1); val end = a.long("endAtMs", -1)
                if (start < 0 || end <= start || end - start > 31L * 86400000) throw CapabilityException("INVALID_RANGE", "Select a positive range of at most 31 days")
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, start); ContentUris.appendId(it, end) }.build()
                val limit = a.long("limit", 50).toInt(); val offset = a.long("offset", 0).toInt()
                val rows = query(uri, arrayOf("event_id", "begin", "end", "title", "allDay", "eventTimezone", "availability"), "calendar_id=? AND begin<? AND end>?", arrayOf(cid.toString(), end.toString(), start.toString()), "begin ASC, event_id ASC").use { c ->
                    buildList { if (offset == 0 || c.moveToPosition(offset - 1)) while (size <= limit && c.moveToNext()) add(buildJsonObject {
                        put("eventId", c.number("event_id")!!); put("calendarId", cid); put("beginAtMs", c.number("begin")!!); put("endAtMs", c.number("end")!!)
                        put("title", c.text("title", 1000) ?: ""); put("allDay", c.number("allDay") == 1L)
                        c.text("eventTimezone", 100)?.let { put("timezone", it) }; c.number("availability")?.let { put("availability", it) }
                    }) }
                }
                read(buildJsonObject { put("events", JsonArray(rows.take(limit))); put("hasMore", rows.size > limit); if (rows.size > limit && offset + limit <= 10000) put("nextOffset", offset + limit) })
            },
            op("calendar.get_event", "Read event or recurring-series metadata and attendees", eventIds()) { a ->
                val cid = a.id("calendarId"); val eid = a.id("eventId"); calendar(cid)
                val row = event(cid, eid)
                val attendees = query(CalendarContract.Attendees.CONTENT_URI, arrayOf("attendeeName", "attendeeEmail", "attendeeRelationship", "attendeeType", "attendeeStatus"), "event_id=?", arrayOf(eid.toString())).use { c ->
                    buildList { while (size < 100 && c.moveToNext()) add(buildJsonObject {
                        c.text("attendeeName", 500)?.let { put("name", it) }; c.text("attendeeEmail", 500)?.let { put("email", it) }
                        c.number("attendeeRelationship")?.let { put("relationship", it) }; c.number("attendeeType")?.let { put("type", it) }; c.number("attendeeStatus")?.let { put("status", it) }
                    }) }
                }
                read(buildJsonObject { put("event", row); put("attendees", JsonArray(attendees)); put("hasMore", attendees.size == 100) })
            },
            op("calendar.create_event", "Create and verify an event in an explicitly selected calendar", objectSchema {
                integer("calendarId", "Explicit writable calendar identifier", true, min = 1); string("title", "Event title", true, maxLength = 1000)
                string("description", "Optional event description", maxLength = 8000); string("location", "Optional event location", maxLength = 1000); timeFields(true)
                string("repeat", "Supported recurrence subset; default NONE", enum = listOf("NONE", "DAILY", "WEEKLY", "MONTHLY")); integer("repeatInterval", "Recurrence interval", min = 1, max = 365); integer("repeatCount", "Total occurrences; required for repeating events", min = 2, max = 366)
                string("availability", "Busy state", enum = listOf("BUSY", "FREE", "TENTATIVE"))
            }, true) { a ->
                val cid = a.id("calendarId"); calendar(cid, true)
                if (a.str("title").isBlank()) throw CapabilityException("INVALID_TITLE", "Event title must not be blank")
                val values = temporalValues(a).apply {
                    put("calendar_id", cid); put("title", a.str("title")); a["description"]?.let { put("description", it.jsonPrimitive.content) }; a["location"]?.let { put("eventLocation", it.jsonPrimitive.content) }
                    put("availability", when (a["availability"]?.jsonPrimitive?.content) { "FREE" -> CalendarContract.Events.AVAILABILITY_FREE; "TENTATIVE" -> CalendarContract.Events.AVAILABILITY_TENTATIVE; else -> CalendarContract.Events.AVAILABILITY_BUSY })
                    val repeat = a["repeat"]?.jsonPrimitive?.content ?: "NONE"
                    if (repeat != "NONE") {
                        val rule = CalendarEventRules.recurrence(repeat, a.long("repeatInterval", 1), a.long("repeatCount", -1))
                        put("rrule", rule); putNull("dtend")
                        val delta = a.long("endAtMs", 0) - a.long("startAtMs", 0)
                        put("duration", if (a["allDay"]?.jsonPrimitive?.booleanOrNull == true) "P${delta / 86400000}D" else "PT${delta / 1000}S")
                    } else if ("repeatCount" in a || "repeatInterval" in a) throw CapabilityException("INVALID_RECURRENCE", "Repeat bounds require an explicit repeating frequency")
                }
                val uri = resolver.insert(events, values) ?: throw CapabilityException("PROVIDER_WRITE_FAILED", "Provider did not return the inserted event URI")
                val eid = ContentUris.parseId(uri); val row = event(cid, eid)
                if (row["title"]?.jsonPrimitive?.content != a.str("title") || row["startAtMs"]?.jsonPrimitive?.longOrNull != a.long("startAtMs", -1)) throw CapabilityException("POSTCONDITION_FAILED", "Inserted event could not be verified; inspect original request before retrying")
                verified(buildJsonObject { put("event", row) }, "Inserted event ID/title/start read back from local provider; server sync and attendee delivery unverified")
            },
            op("calendar.update_event", "Update and verify selected event fields", objectSchema {
                integer("calendarId", "Explicit calendar identifier", true, min = 1); integer("eventId", "Exact event/series identifier", true, min = 1)
                string("title", "New title", maxLength = 1000); string("description", "New description", maxLength = 8000); string("location", "New location", maxLength = 1000)
                timeFields(false); boolean("applyToSeries", "Confirm changes to the entire recurring series")
            }, true) { a ->
                val cid = a.id("calendarId"); val eid = a.id("eventId"); calendar(cid, true); val old = event(cid, eid); seriesCheck(a, old)
                val values = ContentValues()
                for ((arg, column) in listOf("title" to "title", "description" to "description", "location" to "eventLocation")) a[arg]?.let {
                    if (arg == "title" && it.jsonPrimitive.content.isBlank()) throw CapabilityException("INVALID_TITLE", "Event title must not be blank")
                    values.put(column, it.jsonPrimitive.content)
                }
                if (listOf("startAtMs", "endAtMs", "timezone", "allDay").any { it in a }) {
                    if (listOf("startAtMs", "endAtMs", "timezone", "allDay").any { it !in a }) throw CapabilityException("INVALID_TIME", "Timing changes require startAtMs, endAtMs, timezone and allDay together")
                    if ("recurrenceRule" in old || "recurrenceDates" in old) throw CapabilityException("RECURRENCE_TIME_CHANGE_UNSUPPORTED", "Changing recurring-series timing is unsupported; metadata updates are supported")
                    values.putAll(temporalValues(a))
                }
                if (values.size() == 0) throw CapabilityException("EMPTY_UPDATE", "Supply at least one supported field")
                if (resolver.update(events, values, "_id=? AND calendar_id=? AND deleted=0", arrayOf(eid.toString(), cid.toString())) != 1) throw CapabilityException("EVENT_CHANGED", "Event disappeared or changed while updating")
                val row = event(cid, eid)
                for ((key, _) in values.valueSet()) {
                    val field = mapOf("eventLocation" to "location", "dtstart" to "startAtMs", "dtend" to "endAtMs", "eventTimezone" to "timezone")[key] ?: key
                    val expected = values.get(key)
                    val matches = when (expected) { is Long -> row[field]?.jsonPrimitive?.longOrNull == expected; is Int -> row[field]?.jsonPrimitive?.booleanOrNull == (expected == 1); else -> row[field]?.jsonPrimitive?.content == expected.toString() }
                    if (!matches) throw CapabilityException("POSTCONDITION_FAILED", "Updated event did not match readback; inspect before retrying")
                }
                verified(buildJsonObject { put("event", row) }, "Updated fields read back locally; account synchronization unverified")
            },
            op("calendar.delete_event", "Delete and verify absence of selected event", objectSchema {
                integer("calendarId", "Explicit calendar identifier", true, min = 1); integer("eventId", "Exact event or series identifier", true, min = 1); boolean("applyToSeries", "Confirm deletion of entire recurring series")
            }, true) { a ->
                val cid = a.id("calendarId"); val eid = a.id("eventId"); calendar(cid, true); seriesCheck(a, event(cid, eid))
                if (resolver.delete(events, "_id=? AND calendar_id=? AND deleted=0", arrayOf(eid.toString(), cid.toString())) != 1) throw CapabilityException("EVENT_CHANGED", "Event changed before deletion")
                query(events, arrayOf("_id"), "_id=? AND calendar_id=? AND deleted=0", arrayOf(eid.toString(), cid.toString())).use { if (it.moveToFirst()) throw CapabilityException("POSTCONDITION_FAILED", "Event remains locally visible after deletion") }
                verified(buildJsonObject { put("calendarId", cid); put("eventId", eid); put("deleted", true) }, "Event absent from local nondeleted rows; server deletion unverified")
            },
            op("calendar.list_reminders", "List local reminder offsets for selected event", eventIds()) { a ->
                val cid = a.id("calendarId"); val eid = a.id("eventId"); calendar(cid); event(cid, eid)
                val rows = query(reminders, arrayOf("_id", "minutes", "method"), "event_id=?", arrayOf(eid.toString()), "_id ASC").use { c -> buildList {
                    while (size < 100 && c.moveToNext()) add(buildJsonObject { put("reminderId", c.number("_id")!!); put("minutesBefore", c.number("minutes")!!); put("method", c.number("method")!!) })
                } }
                read(buildJsonObject { put("reminders", JsonArray(rows)); put("hasMore", rows.size == 100) })
            },
            op("calendar.add_reminder", "Add a local Calendar Provider alert offset", objectSchema {
                integer("calendarId", "Explicit calendar identifier", true, min = 1); integer("eventId", "Exact event identifier", true, min = 1); integer("minutesBefore", "Minutes before event; zero means event start", true, min = 0, max = 40320); boolean("applyToSeries", "Confirm reminder changes for entire recurring series")
            }, true) { a ->
                val cid = a.id("calendarId"); val eid = a.id("eventId"); val selected = calendar(cid, true); seriesCheck(a, event(cid, eid))
                val allowed = selected["allowedReminderMethods"]?.jsonPrimitive?.content?.split(',')
                if (allowed != null && CalendarContract.Reminders.METHOD_ALERT.toString() !in allowed) throw CapabilityException("REMINDER_METHOD_UNSUPPORTED", "Selected calendar does not support local alert reminders")
                val count = query(reminders, arrayOf("_id"), "event_id=?", arrayOf(eid.toString())).use { it.count }
                val maximum = selected["maxReminders"]?.jsonPrimitive?.longOrNull ?: 5
                if (count >= maximum.coerceAtMost(100)) throw CapabilityException("REMINDER_LIMIT", "Selected calendar reminder limit reached")
                val uri = resolver.insert(reminders, ContentValues().apply { put("event_id", eid); put("minutes", a.long("minutesBefore", 0)); put("method", CalendarContract.Reminders.METHOD_ALERT) }) ?: throw CapabilityException("PROVIDER_WRITE_FAILED", "No reminder URI returned")
                val rid = ContentUris.parseId(uri)
                query(reminders, arrayOf("minutes", "method"), "_id=? AND event_id=?", arrayOf(rid.toString(), eid.toString())).use { if (!it.moveToFirst() || it.getLong(0) != a.long("minutesBefore", 0) || it.getInt(1) != CalendarContract.Reminders.METHOD_ALERT) throw CapabilityException("POSTCONDITION_FAILED", "Reminder readback did not match") }
                verified(buildJsonObject { put("calendarId", cid); put("eventId", eid); put("reminderId", rid) }, "Local alert offset read back; actual future alert delivery is not yet verified")
            },
            op("calendar.remove_reminder", "Remove and verify a selected local event reminder", objectSchema {
                integer("calendarId", "Explicit calendar identifier", true, min = 1); integer("eventId", "Exact event identifier", true, min = 1); integer("reminderId", "Exact reminder identifier", true, min = 1); boolean("applyToSeries", "Confirm reminder changes for entire recurring series")
            }, true) { a ->
                val cid = a.id("calendarId"); val eid = a.id("eventId"); val rid = a.id("reminderId"); calendar(cid, true); seriesCheck(a, event(cid, eid))
                if (resolver.delete(reminders, "_id=? AND event_id=?", arrayOf(rid.toString(), eid.toString())) != 1) throw CapabilityException("REMINDER_NOT_FOUND", "Reminder does not exist on the selected event")
                query(reminders, arrayOf("_id"), "_id=? AND event_id=?", arrayOf(rid.toString(), eid.toString())).use { if (it.moveToFirst()) throw CapabilityException("POSTCONDITION_FAILED", "Reminder remains after deletion") }
                verified(buildJsonObject { put("calendarId", cid); put("eventId", eid); put("reminderId", rid); put("deleted", true) }, "Reminder row absent locally; server synchronization unverified")
            },
        )
    }
}

/** Small explicit recurrence subset; not an RRULE parser or an iCalendar implementation. */
internal object CalendarEventRules {
    fun validateTimes(start: Long, end: Long, timezone: String, allDay: Boolean) {
        if (start < 0 || end <= start || end - start > 366L * 86400000 || start % 1000 != 0L || end % 1000 != 0L)
            throw CapabilityException("INVALID_TIME", "Use whole-second start/end, positive duration of at most 366 days")
        try { ZoneId.of(timezone); Instant.ofEpochMilli(end) } catch (_: Exception) { throw CapabilityException("INVALID_TIMEZONE", "A valid IANA timezone is required") }
        if (allDay && (timezone != "UTC" || start % 86400000 != 0L || end % 86400000 != 0L))
            throw CapabilityException("INVALID_ALL_DAY", "All-day events use exclusive UTC-midnight boundaries and timezone UTC")
    }
    fun recurrence(frequency: String, interval: Long, count: Long): String {
        if (frequency !in setOf("DAILY", "WEEKLY", "MONTHLY") || interval !in 1..365 || count !in 2..366)
            throw CapabilityException("INVALID_RECURRENCE", "Supported recurrence requires DAILY/WEEKLY/MONTHLY, interval 1..365, count 2..366")
        return "FREQ=$frequency;INTERVAL=$interval;COUNT=$count"
    }
}
