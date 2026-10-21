package dev.siconnect.app

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import dev.siconnect.app.automation.*
import dev.siconnect.app.data.*
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import java.util.UUID

/** Actual SQLite/WorkManager/skill/engine integration; only disposable native read-only workflows. */
@RunWith(AndroidJUnit4::class)
class SchedulerDeviceTest {
    private val g=InstrumentationRegistry.getInstrumentation().targetContext.graph
    @Before fun ready()=runBlocking {g.ready.await()}
    private fun cleanup(id:String) {
        g.scheduler.get(id)?.let {g.scheduler.setEnabled(id,false)}
        g.db.writableDatabase.delete("automations","automation_id=?",arrayOf(id))
        g.db.writableDatabase.delete("automation_runs","automation_id=?",arrayOf(id))
    }
    @Test fun actualDeferrableRecurringSlotExecutesOnceAndAdvances()=runBlocking {
        val id="scheduler-fixture-${UUID.randomUUID()}";val slot=System.currentTimeMillis()+2000
        try {
            g.scheduler.put(Automation(id,"diagnostics.snapshot",slot,recurrence=Recurrence(RecurrenceKind.DAILY,"08:00"),zoneId="UTC"))
            withTimeout(90_000) {while(g.scheduler.get(id)?.lastState!="SUCCEEDED") delay(200)}
            val run=Scheduler.runId(id,slot);val child=requireNotNull(g.requests.get("$run:status"))
            Assert.assertEquals(AutomationRunState.SUCCEEDED,g.scheduler.fire(id,slot))
            Assert.assertEquals(child,g.requests.get("$run:status"))
            Assert.assertEquals(1,g.scheduler.runs(id).size)
            Assert.assertTrue(g.scheduler.get(id)!!.nextAtMs>slot)
            Assert.assertNotNull(g.scheduler.runs(id).single().finishedAt)
        } finally {cleanup(id)}
    }
    @Test fun ownedSlotWithoutSkillRunRecoversAndConfigChangeDoesNotStrandIt()=runBlocking {
        val id="scheduler-fixture-${UUID.randomUUID()}";val slot=System.currentTimeMillis()-1000
        val a=Automation(id,"diagnostics.snapshot",slot,intervalMs=900_000,enabled=false)
        try {
            val saved=g.scheduler.put(a);val run=Scheduler.runId(id,slot)
            // Reproduce process death after slot insert but before DurableSkillRunner.start.
            g.db.writableDatabase.insertOrThrow("automation_runs",null,ContentValues().apply {
                put("run_id",run);put("automation_id",id);put("slot_at",slot);put("started_at",slot);put("state","RUNNING")
                put("automation_json",AppJson.encodeToString(Automation.serializer(),saved))
                put("skill_json",SkillJson.json.encodeToString(SkillDefinition.serializer(),g.skills.get(a.skillId)!!))
            })
            g.scheduler.setEnabled(id,true) // new config revision, same owned slot
            Assert.assertEquals(AutomationRunState.SUCCEEDED,g.scheduler.fire(id,slot))
            Assert.assertEquals("SUCCEEDED",g.scheduler.get(id)!!.lastState)
            Assert.assertTrue(g.scheduler.get(id)!!.nextAtMs>System.currentTimeMillis())
            Assert.assertEquals(1,g.scheduler.runs(id).size)
        } finally {cleanup(id)}
    }
    @Test fun pendingApprovalIsNotFinishedAndCompletionReconcilesOriginalRun()=runBlocking {
        val id="scheduler-fixture-${UUID.randomUUID()}";val slot=System.currentTimeMillis()-1000
        val saved=g.scheduler.put(Automation(id,"diagnostics.snapshot",slot,intervalMs=900_000,enabled=false))
        val run=Scheduler.runId(id,slot);val definition=g.skills.get(saved.skillId)!!
        try {
            val pending=SkillExecution(run,definition,JsonObject(emptyMap()),"local-automation",g.settings.deviceId,run,Origin.LOCAL_AUTOMATION.name,slot,slot+60000,state=SkillState.AWAITING_APPROVAL,pendingRequestId="$run:status")
            g.skillRuns.insert(pending)
            g.db.writableDatabase.insertOrThrow("automation_runs",null,ContentValues().apply {
                put("run_id",run);put("automation_id",id);put("slot_at",slot);put("started_at",slot);put("state","RUNNING")
                put("automation_json",AppJson.encodeToString(Automation.serializer(),saved))
            })
            g.scheduler.reconcileRun(run)
            Assert.assertEquals("AWAITING_APPROVAL",g.scheduler.get(id)!!.lastState)
            Assert.assertNull(g.scheduler.runs(id).single().finishedAt)
            Assert.assertEquals(slot,g.scheduler.get(id)!!.nextAtMs)
            val done=pending.copy(state=SkillState.SUCCEEDED,pendingRequestId=null,revision=1,cursor=3)
            Assert.assertTrue(g.skillRuns.update(done,0))
            g.scheduler.reconcileRun(run);g.scheduler.reconcileRun(run)
            Assert.assertEquals("SUCCEEDED",g.scheduler.runs(id).single().state)
            Assert.assertNotNull(g.scheduler.runs(id).single().finishedAt)
            Assert.assertEquals(1,g.scheduler.runs(id).size)
        } finally {cleanup(id);g.db.writableDatabase.delete("skill_runs","run_id=?",arrayOf(run))}
    }
}
