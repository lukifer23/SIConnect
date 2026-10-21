package dev.siconnect.app

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import dev.siconnect.app.automation.*
import dev.siconnect.core.*
import dev.siconnect.app.data.AppJson
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Disposable read-only schedule; no existing reminders, grants, or applications are changed. */
@RunWith(AndroidJUnit4::class)
class ManualAutomationDeviceTest {
    private val g=InstrumentationRegistry.getInstrumentation().targetContext.graph
    @Test fun queuedCancellationPersistsCursorBeforeAnyWorkerCanStartIt()=runBlocking {
        g.ready.await()
        val id="manual-cancel-fixture-"+UUID.randomUUID();val slot=System.currentTimeMillis()-1000
        val saved=g.scheduler.put(Automation(id,"diagnostics.snapshot",slot+3_600_000,enabled=false,label="Disposable cancelled check"))
        val run=Scheduler.runId(id,slot)
        val definition=g.skills.get(saved.skillId)!!
        try {
            // Controlled interruption fixture: a slot was committed, but its worker has not run.
            g.db.writableDatabase.insertOrThrow("automation_runs",null,ContentValues().apply {
                put("run_id",run);put("automation_id",id);put("slot_at",slot);put("started_at",slot);put("updated_at",slot);put("state","READY")
                put("automation_json",AppJson.encodeToString(Automation.serializer(),saved.copy(nextAtMs=slot,enabled=true,manualRun=true)))
                put("skill_json",SkillJson.json.encodeToString(SkillDefinition.serializer(),definition))
            })
            g.scheduler.cancelRun(run)
            assertEquals(SkillState.CANCELLED,g.skillRuns.get(run)!!.state)
            assertEquals(AutomationRunState.CANCELLED,g.scheduler.fire(id,slot))
            assertNull(g.requests.get("$run:status"))
            assertEquals(saved,g.scheduler.get(id))
        } finally {
            g.db.writableDatabase.delete("automation_runs","automation_id=?",arrayOf(id))
            g.db.writableDatabase.delete("automations","automation_id=?",arrayOf(id))
            g.db.writableDatabase.delete("skill_runs","run_id=?",arrayOf(run))
        }
    }
    @Test fun runNowUsesOneDurableSlotAndLeavesTheScheduleUnchanged()=runBlocking {
        g.ready.await()
        val id="manual-fixture-"+UUID.randomUUID()
        val before=g.scheduler.put(Automation(id,"diagnostics.snapshot",System.currentTimeMillis()+3_600_000,
            recurrence=Recurrence(RecurrenceKind.DAILY,"08:00"),zoneId="UTC",enabled=false,label="Disposable health check"))
        val slot=System.currentTimeMillis()
        var runId:String?=null
        try {
            val owned=g.scheduler.runNow(id,slot,before.revision);runId=owned
            assertEquals(owned,g.scheduler.runNow(id,slot,before.revision))
            withTimeout(90_000) {while(g.scheduler.runs(id).singleOrNull()?.state!="SUCCEEDED") delay(100)}
            val complete=g.skillRuns.get(owned)!!
            assertEquals(SkillState.SUCCEEDED,complete.state)
            assertEquals(before,g.scheduler.get(id))
            assertEquals(1,g.scheduler.runs(id).size)
            val child=g.requests.get("$owned:status")!!
            assertEquals(RequestState.SUCCEEDED,child.state)
            assertEquals(AutomationRunState.SUCCEEDED,g.scheduler.fire(id,slot))
            assertEquals(child,g.requests.get("$owned:status"))
            assertEquals(before,g.scheduler.get(id))
        } finally {
            WorkManager.getInstance(g.context).cancelUniqueWork("automation:$id:$slot").result.get()
            g.scheduler.setEnabled(id,false)
            g.db.writableDatabase.delete("automation_runs","automation_id=?",arrayOf(id))
            g.db.writableDatabase.delete("automations","automation_id=?",arrayOf(id))
            runId?.let {g.db.writableDatabase.delete("skill_runs","run_id=?",arrayOf(it))}
        }
    }
}
