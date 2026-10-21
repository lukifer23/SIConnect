package dev.siconnect.app.automation

import dev.siconnect.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AutomationDraftTest {
    private val now=Instant.parse("2026-10-20T12:00:00Z").toEpochMilli()
    @Test fun editingLegacyIntervalPreservesExactAnchorAndMilliseconds() {
        val original=Automation("legacy","diagnostics.snapshot",now+12345,intervalMs=900123,zoneId="UTC",revision=4,maxLatenessMs=123456)
        val saved=AutomationDraft.from(original).copy(label="Health check").build(now,original)
        assertEquals(original.nextAtMs,saved.nextAtMs)
        assertEquals(900123L,saved.recurrence!!.intervalMs)
        assertEquals(4L,saved.revision);assertNull(saved.intervalMs);assertEquals(123456L,saved.maxLatenessMs)
    }
    @Test fun wallClockPreviewCrossesDstWithoutDriftingLocalTime() {
        val first=Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()
        val draft=AutomationDraft("daily","Morning","diagnostics.snapshot",JsonObject(emptyMap()),localTime="07:00",zoneId="America/Chicago")
        assertEquals(listOf("2026-11-01T13:00:00Z","2026-11-02T13:00:00Z","2026-11-03T13:00:00Z"),draft.preview(first).map {Instant.ofEpochMilli(it).toString()})
    }
    @Test fun commuteUsesOneTimezoneAndRejectsFixedDateForRecurrence() {
        val inputs=buildJsonObject {put("timezone","UTC");put("wakeTime","06:00");put("departureTime","08:00")}
        val draft=AutomationDraft("weather","Weather","weather.commute_check",inputs,zoneId="America/Chicago")
        assertEquals("America/Chicago",draft.build(now).inputs.getValue("timezone").jsonPrimitive.content)
        try {draft.copy(inputs=JsonObject(inputs+("targetDate" to JsonPrimitive("2026-10-21")))).build(now);fail("fixed recurring date accepted")}
        catch(_:IllegalArgumentException) {}
    }
    @Test fun reminderTemplateIsRealCapabilityCompositionAndHasRequiredContent() {
        val definition=AutomationTemplates.notice()
        assertEquals(listOf("notifications.post"),definition.steps.map {it.capability})
        assertTrue(Schema.validate(definition.inputs,buildJsonObject {put("title","");put("text","hello")}).isNotEmpty())
        assertTrue(Schema.validate(definition.inputs,buildJsonObject {put("title","Reminder");put("text","hello")}).isEmpty())
    }
}
