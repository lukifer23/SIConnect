package dev.siconnect.app

import android.Manifest
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.capabilities.CalendarOperations
import dev.siconnect.core.ExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarReadDeviceTest {
    /** A resolver bound only to in-memory rows. The test never queries the phone's accounts. */
    private class Fixture : ContentProvider() {
        var count = 0
        val queries = mutableListOf<Uri>()

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
            queries += uri
            val columns = requireNotNull(projection)
            val size = when (uri) {
                CalendarContract.Calendars.CONTENT_URI -> if (selection == null) {
                    assertEquals("_id ASC", sortOrder)
                    count
                } else 1
                CalendarContract.Events.CONTENT_URI -> 1
                CalendarContract.Attendees.CONTENT_URI, CalendarContract.Reminders.CONTENT_URI -> count
                else -> error("Unexpected URI")
            }
            return MatrixCursor(columns).apply {
                repeat(size) { index ->
                    val row = newRow()
                    columns.forEach { column -> row.add(when (column) {
                        "_id" -> index + 1
                        "calendar_displayName" -> "Fixture ${index + 1}"
                        "calendar_access_level" -> CalendarContract.Calendars.CAL_ACCESS_READ
                        "visible", "method" -> 1
                        "minutes" -> 5
                        else -> null
                    }) }
                }
            }
        }

        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri = error("Unexpected insert")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Unexpected delete")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Unexpected update")
    }

    @Test fun boundedReadsUseOneCalendarQueryAndReportOnlyActualOverflow() = runBlocking {
        val fixture = Fixture()
        val resolver = ContentResolver.wrap(fixture)
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getContentResolver() = resolver
            override fun checkSelfPermission(permission: String) =
                if (permission == Manifest.permission.READ_CALENDAR) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        val tools = CalendarOperations(context).capabilities().associateBy { it.descriptor.id }
        val execution = ExecutionContext("fixture", "fixture", "local-owner", "test")
        for (count in listOf(0, 1, 99, 100, 101)) {
            fixture.count = count
            fixture.queries.clear()
            val result = tools.getValue("calendar.list_calendars").execute(buildJsonObject { put("limit", 100) }, execution).output.jsonObject
            assertEquals(listOf(CalendarContract.Calendars.CONTENT_URI), fixture.queries)
            val rows = result.getValue("calendars").jsonArray
            assertEquals(count.coerceAtMost(100), rows.size)
            assertEquals(count > 100, result.getValue("hasMore").jsonPrimitive.boolean)
            rows.forEachIndexed { index, item ->
                val row = item.jsonObject
                assertEquals(index + 1, row.getValue("calendarId").jsonPrimitive.int)
                assertEquals("Fixture ${index + 1}", row.getValue("displayName").jsonPrimitive.content)
                assertTrue(row.getValue("visible").jsonPrimitive.boolean)
                assertFalse(row.getValue("writable").jsonPrimitive.boolean)
                assertFalse("accountName" in row)
            }

            val ids = buildJsonObject { put("calendarId", 1); put("eventId", 1) }
            for ((id, field) in listOf("calendar.get_event" to "attendees", "calendar.list_reminders" to "reminders")) {
                val detail = tools.getValue(id).execute(ids, execution).output.jsonObject
                assertEquals(count.coerceAtMost(100), detail.getValue(field).jsonArray.size)
                assertEquals(count > 100, detail.getValue("hasMore").jsonPrimitive.boolean)
            }
        }
    }
}
