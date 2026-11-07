package dev.siconnect.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.automation.Automation
import dev.siconnect.app.automation.AutomationRun
import dev.siconnect.app.capabilities.Reminder
import dev.siconnect.app.data.*
import dev.siconnect.core.AgentGrant
import dev.siconnect.core.Canonical
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Disposable real SQLite databases only: no alarms, workflow dispatch, owner records or UI. */
class ScheduleProjectionDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun name()="schedule-projection-${UUID.randomUUID()}.db"
    private fun automationValues(a:Automation)=ContentValues().apply {
        put("automation_id",a.id);put("enabled",if(a.enabled) 1 else 0);put("next_run_at",a.nextAtMs)
        put("owner","local-automation");put("revision",a.revision);put("json",AppJson.encodeToString(Automation.serializer(),a))
    }
    private fun reminderValues(r:Reminder)=ContentValues().apply {
        put("reminder_id",r.id);put("fire_at",r.fireAtMs);put("state",r.state);put("created_by",r.agentId)
        put("json",AppJson.encodeToString(Reminder.serializer(),r));put("revision",r.revision)
    }
    private fun raw(db:SQLiteDatabase,table:String,key:String,id:String)=db.query(table,arrayOf("json"),"$key=?",arrayOf(id),null,null,null)
        .use {assertTrue(it.moveToFirst());it.getString(0)}

    @Test fun realSchema11UpgradeBackfillsOnlyMetadataAndPreservesOriginalPayloadsAndPrincipals() {
        val file=name()
        val a=Automation("legacy-routine","fixture.workflow",123456,enabled=false,label="Legacy %_\\ routine",
            lastState="FAILED",scheduleState="BLOCKED",error="EXACT_ACCESS",inputs=buildJsonObject {put("body","original private workflow input")})
        val r=Reminder("legacy-reminder",123456,"Legacy reminder","original reminder body","fixture-agent",state="SCHEDULED")
        val agent=AgentRecord(AgentGrant("fixture-agent","Original fixture principal",scopes=listOf("device.status")),
            Canonical.sha256Hex("fixture credential"),1000,tokenHint="isolated migration fixture")
        val agentJson=AppJson.encodeToString(AgentRecord.serializer(),agent)
        val aJson=AppJson.encodeToString(Automation.serializer(),a);val rJson=AppJson.encodeToString(Reminder.serializer(),r)
        try {
            // Build the real preceding production schema through its unchanged migrations.
            // Only migration12 is guarded by the requested target version.
            object:SQLiteOpenHelper(context,file,null,11) {
                override fun onCreate(db:SQLiteDatabase) {SIConnectDb(context,"unused-${UUID.randomUUID()}.db").use {it.onUpgrade(db,0,11)}}
                override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {error("Unexpected legacy upgrade")}
            }.use {legacy->
                val db=legacy.writableDatabase
                db.insertOrThrow("automations",null,automationValues(a).apply {putNull("next_run_at")})
                db.insertOrThrow("reminders",null,reminderValues(r))
                db.insertOrThrow("agents",null,ContentValues().apply {
                    put("agent_id",agent.grant.agentId);put("token_sha256",agent.tokenSha256);put("created_at",agent.createdAtMs);put("json",agentJson)
                })
                assertEquals(11,db.version)
            }
            SIConnectDb(context,file).use {upgraded->
                val db=upgraded.readableDatabase
                assertEquals(SIConnectDb.VERSION,db.version)
                assertEquals(aJson,raw(db,"automations","automation_id",a.id))
                assertEquals(rJson,raw(db,"reminders","reminder_id",r.id))
                assertEquals(agentJson,raw(db,"agents","agent_id",agent.grant.agentId))
                val store=ScheduleProjectionStore(upgraded)
                val summary=store.automationPage().single()
                assertEquals(a.id,summary.id);assertEquals(a.label,summary.label);assertEquals(a.skillId,summary.skillId)
                assertEquals(a.scheduleState,summary.scheduleState);assertEquals(a.lastState,summary.lastState);assertEquals(a.error,summary.error)
                assertFalse(summary.enabled);assertNull(summary.nextAtMs)
                assertEquals(r.title,store.reminderPage().single().title)
                assertEquals("ok",db.rawQuery("PRAGMA quick_check",null).use {it.moveToFirst();it.getString(0)})
            }
        } finally {context.deleteDatabase(file)}
    }

    @Test fun automationPagesCoverEnabledPausedTiesAndNullableTimesWithGlobalLiteralSearch() {
        val file=name()
        SIConnectDb(context,file).use {db->try {
            val rows=(0 until 173).map {i->Automation("routine-%03d".format(i),"fixture.workflow",1000L+i/5,
                enabled=i%2==0,label=if(i==169) "Needle literal %_\\ marker" else "Routine $i",
                scheduleState=if(i%5==0) "BLOCKED" else "SCHEDULED",inputs=buildJsonObject {put("body","x".repeat(16384))})}
            val nullIds=rows.filterIndexed {i,_->i%13==0}.map {it.id}.toSet()
            val w=db.writableDatabase;w.beginTransaction()
            try {
                rows.forEach {a->w.insertOrThrow("automations",null,automationValues(a).apply {
                    putAll(ScheduleMetadata.automation(a));if(a.id in nullIds) putNull("next_run_at")
                })}
                w.setTransactionSuccessful()
            } finally {w.endTransaction()}
            val store=ScheduleProjectionStore(db)
            val seen=mutableListOf<AutomationSummary>();var cursor:AutomationSummary?=null
            repeat(30) {
                val page=store.automationPage(7,cursor)
                if(page.isEmpty()) return@repeat
                seen+=page;cursor=page.last()
            }
            val expected=rows.sortedWith(compareByDescending<Automation> {it.enabled}.thenBy {if(it.id in nullIds) null else it.nextAtMs}.thenBy {it.id}).map {it.id}
            assertEquals(expected,seen.map {it.id});assertEquals(173,seen.map {it.id}.distinct().size)
            assertEquals(101,store.automationPage(Int.MAX_VALUE).size)
            assertEquals(rows.count {it.enabled},store.automationPage(101,filter=AutomationFilter.ENABLED).size)
            assertEquals(rows.count {!it.enabled},store.automationPage(101,filter=AutomationFilter.PAUSED).size)
            assertEquals(rows.count {it.scheduleState=="BLOCKED"},store.automationPage(101,filter=AutomationFilter.ATTENTION).size)
            assertEquals(listOf("routine-169"),store.automationPage(search="Needle").map {it.id})
            assertEquals(listOf("routine-169"),store.automationPage(search="%_\\").map {it.id})
            // The original large action input is never rewritten by search or page traversal.
            assertEquals(AppJson.encodeToString(Automation.serializer(),rows.last()),raw(w,"automations","automation_id",rows.last().id))
        } finally {db.close();context.deleteDatabase(file)}}
    }

    @Test fun reminderProjectionExcludesTerminalHistoryAndRunHistoryPagesPreserveEveryOriginalOutcome() {
        val file=name()
        SIConnectDb(context,file).use {db->try {
            val w=db.writableDatabase
            for(i in 0 until 45) {
                val r=Reminder("reminder-%02d".format(i),1000L+i/3,"Reminder $i","retained body $i",if(i%2==0) "owner-a" else "owner-b",
                    state=if(i%4==0) "SCHEDULED" else "SUCCEEDED")
                w.insertOrThrow("reminders",null,reminderValues(r).apply {putAll(ScheduleMetadata.reminder(r))})
            }
            val store=ScheduleProjectionStore(db)
            val upcoming=store.reminderPage(101)
            assertEquals(12,upcoming.size);assertTrue(upcoming.all {it.state=="SCHEDULED"})
            assertEquals(upcoming.filter {it.agentId=="owner-a"},store.reminderPage(101,agentId="owner-a"))
            assertEquals(45,store.reminderPage(101,states=null).size)
            assertEquals(listOf("reminder-44"),store.reminderPage(101,states=null,search="Reminder 44").map {it.id})
            val reminders=mutableListOf<ReminderSummary>();var reminderCursor:ReminderSummary?=null
            repeat(8) {val page=store.reminderPage(2,reminderCursor);if(page.isNotEmpty()) {reminders+=page;reminderCursor=page.last()}}
            assertEquals(upcoming,reminders)
            val states=listOf("READY","RUNNING","AWAITING_APPROVAL","SUCCEEDED","PARTIALLY_SUCCEEDED","FAILED","BLOCKED","CANCELLED","UNKNOWN_OUTCOME","MISSED")
            for(i in 0 until 95) w.insertOrThrow("automation_runs",null,ContentValues().apply {
                put("run_id","run-%03d".format(i));put("automation_id","routine");put("slot_at",1000+i)
                put("started_at",1000+i);put("finished_at",2000+i);put("state",states[i%states.size]);put("json","{}")
            })
            w.insertOrThrow("automation_runs",null,ContentValues().apply {
                put("run_id","other-run");put("automation_id","other");put("slot_at",10000);put("started_at",10000);put("state","READY")
            })
            val history=mutableListOf<AutomationRun>();var runCursor:AutomationRun?=null
            repeat(12) {val page=store.runPage("routine",9,runCursor);if(page.isNotEmpty()) {history+=page;runCursor=page.last()}}
            assertEquals((94 downTo 0).map {"run-%03d".format(it)},history.map {it.runId})
            assertEquals(95,history.map {it.runId}.distinct().size)
            assertEquals(history.filter {it.state in setOf("READY","RUNNING","AWAITING_APPROVAL")},store.runPage("routine",101,filter=AutomationRunFilter.ACTIVE))
            assertEquals(history.filter {it.state in setOf("SUCCEEDED","CANCELLED")},store.runPage("routine",101,filter=AutomationRunFilter.COMPLETED))
            assertEquals(history.filter {it.state in setOf("AWAITING_APPROVAL","FAILED","PARTIALLY_SUCCEEDED","BLOCKED","UNKNOWN_OUTCOME","MISSED")},store.runPage("routine",101,filter=AutomationRunFilter.ATTENTION))
            try {store.runPage("other",cursor=history.first());fail("A cursor from another routine must be rejected")}
            catch(_:IllegalArgumentException) { /* Correct ownership boundary. */ }
        } finally {db.close();context.deleteDatabase(file)}}
    }
}
