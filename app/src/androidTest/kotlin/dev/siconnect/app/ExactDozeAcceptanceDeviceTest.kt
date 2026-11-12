package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.automation.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Two-phase harness for a real exact automation under Doze; the host puts the phone into deep idle
 * between phases. Uses only the built-in read-only diagnostics workflow and removes its fixture.
 * Instrumentation stops the app, so the host must restart it normally after "create" to restore alarms.
 *   -e exactDoze create  -e slotDelayMs 150000
 *   -e exactDoze check
 */
class ExactDozeAcceptanceDeviceTest {
    private val g=InstrumentationRegistry.getInstrumentation().targetContext.graph
    private val state get()=File(g.context.cacheDir,"exact-doze-acceptance.json")

    @Test fun exactAutomationRunsOnTimeUnderDoze()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val mode=args.getString("exactDoze")
        assumeTrue("Explicit harness phase is required",mode in setOf("create","check"))
        g.ready.await()
        if(mode=="create") {
            assertFalse("Reconcile the previous exact Doze fixture first",state.exists())
            assertTrue("Exact alarm access is required for this acceptance",g.reminders.canScheduleExact())
            val delay=(args.getString("slotDelayMs") ?: "150000").toLong().coerceIn(60_000,600_000)
            val id="exact-doze-${UUID.randomUUID()}";val slot=System.currentTimeMillis()+delay
            g.scheduler.put(Automation(id,"diagnostics.snapshot",slot,exact=true,maxLatenessMs=30*60_000L,label="Disposable exact Doze acceptance"))
            state.writeText(buildJsonObject {put("id",id);put("slot",slot)}.toString())
            assertEquals("SCHEDULED",g.scheduler.get(id)?.scheduleState)
        } else {
            val saved=Json.parseToJsonElement(state.readText()).jsonObject
            val id=saved.getValue("id").jsonPrimitive.content;val slot=saved.getValue("slot").jsonPrimitive.long
            try {
                val row=g.db.readableDatabase.rawQuery("SELECT state,started_at FROM automation_runs WHERE automation_id=? AND slot_at=?",arrayOf(id,slot.toString()))
                    .use {c->if(c.moveToFirst()) c.getString(0) to c.getLong(1) else null}
                assertNotNull("The exact slot never started",row)
                val lateness=row!!.second-slot
                android.util.Log.i("SIConnectExactDoze","state=${row.first} latenessMs=$lateness")
                assertEquals("SUCCEEDED",row.first)
                assertTrue("Exact automation started ${lateness}ms after its slot under Doze",lateness<=60_000)
            } finally {
                g.db.writableDatabase.delete("automations","automation_id=?",arrayOf(id))
                g.db.writableDatabase.delete("automation_runs","automation_id=?",arrayOf(id))
                state.delete()
            }
        }
    }
}
