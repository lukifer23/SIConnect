package dev.siconnect.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.siconnect.app.automation.Automation
import dev.siconnect.app.automation.AutomationRun
import dev.siconnect.app.capabilities.Reminder

/** Display metadata only. Saved action inputs and reminder bodies are loaded for one selection. */
data class AutomationSummary(val id:String,val skillId:String,val nextAtMs:Long?,val enabled:Boolean,
    val zoneId:String,val scheduleState:String,val label:String,val lastState:String?,val error:String?)
data class ReminderSummary(val id:String,val fireAtMs:Long,val title:String,val state:String,val agentId:String)
enum class AutomationFilter { ALL, ENABLED, PAUSED, ATTENTION }
enum class AutomationRunFilter { ALL, ACTIVE, ATTENTION, COMPLETED }

/** One projection writer is shared by normal writes and the streaming legacy migration. */
internal object ScheduleMetadata {
    fun automation(a:Automation)=ContentValues().apply {
        put("label",a.label);put("skill_id",a.skillId);put("zone_id",a.zoneId);put("schedule_state",a.scheduleState)
        put("last_state",a.lastState);put("error_code",a.error)
    }
    fun reminder(r:Reminder)=ContentValues().apply {put("title",r.title)}
    fun migrate(db:SQLiteDatabase) {
        listOf("label TEXT NOT NULL DEFAULT ''","skill_id TEXT NOT NULL DEFAULT ''",
            "zone_id TEXT NOT NULL DEFAULT 'UTC'","schedule_state TEXT NOT NULL DEFAULT 'SCHEDULED'",
            "last_state TEXT","error_code TEXT").forEach {db.execSQL("ALTER TABLE automations ADD COLUMN $it")}
        db.execSQL("ALTER TABLE reminders ADD COLUMN title TEXT NOT NULL DEFAULT ''")
        // SQLiteOpenHelper owns the migration transaction. Decode one legacy row at a time;
        // retain the exact JSON and execution identities rather than rewriting saved actions.
        db.query("automations",arrayOf("automation_id","json"),null,null,null,null,"automation_id").use {c->
            while(c.moveToNext()) {
                val a=AppJson.decodeFromString(Automation.serializer(),c.getString(1))
                check(db.update("automations",automation(a),"automation_id=?",arrayOf(c.getString(0)))==1)
            }
        }
        db.query("reminders",arrayOf("reminder_id","json"),null,null,null,null,"reminder_id").use {c->
            while(c.moveToNext()) {
                val r=AppJson.decodeFromString(Reminder.serializer(),c.getString(1))
                check(db.update("reminders",reminder(r),"reminder_id=?",arrayOf(c.getString(0)))==1)
            }
        }
        db.execSQL("CREATE INDEX automations_overview ON automations(enabled DESC,next_run_at,automation_id)")
        db.execSQL("CREATE INDEX automations_attention ON automations(schedule_state,next_run_at,automation_id)")
        db.execSQL("CREATE INDEX reminders_upcoming ON reminders(state,fire_at,reminder_id)")
    }
}

/** SQL filters and keyset pages do not hydrate action inputs, reminder bodies or run results. */
class ScheduleProjectionStore(private val db:SIConnectDb) {
    fun automationPage(limit:Int=50,cursor:AutomationSummary?=null,filter:AutomationFilter=AutomationFilter.ALL,
                       search:String=""):List<AutomationSummary> {
        val where=mutableListOf<String>();val args=mutableListOf<String>()
        when(filter) {
            AutomationFilter.ENABLED->where+="enabled=1"
            AutomationFilter.PAUSED->where+="enabled=0"
            AutomationFilter.ATTENTION->where+="schedule_state IN ('FAILED','BLOCKED','UNKNOWN_OUTCOME','MISSED')"
            else->Unit
        }
        if(search.isNotBlank()) {
            where+="(label LIKE ? ESCAPE '\\' OR skill_id LIKE ? ESCAPE '\\' OR automation_id LIKE ? ESCAPE '\\')"
            val term=literalSearch(search);repeat(3) {args+=term}
        }
        cursor?.let {
            val enabled=if(it.enabled) "1" else "0"
            if(it.nextAtMs==null) {
                where+="(enabled<? OR (enabled=? AND ((next_run_at IS NULL AND automation_id>?) OR next_run_at IS NOT NULL)))"
                args+=listOf(enabled,enabled,it.id)
            } else {
                where+="(enabled<? OR (enabled=? AND (next_run_at>? OR (next_run_at=? AND automation_id>?))))"
                args+=listOf(enabled,enabled,it.nextAtMs.toString(),it.nextAtMs.toString(),it.id)
            }
        }
        return db.readableDatabase.query("automations",arrayOf("automation_id","skill_id","next_run_at","enabled","zone_id","schedule_state","label","last_state","error_code"),
            where.takeIf {it.isNotEmpty()}?.joinToString(" AND "),args.toTypedArray(),null,null,"enabled DESC,next_run_at,automation_id",limit.coerceIn(1,101).toString())
            .mapAll {AutomationSummary(it.getString(0),it.getString(1),if(it.isNull(2)) null else it.getLong(2),it.getInt(3)!=0,it.getString(4),it.getString(5),it.getString(6),it.getString(7),it.getString(8))}
    }
    fun reminderPage(limit:Int=3,cursor:ReminderSummary?=null,states:Set<String>?=ACTIVE_REMINDER_STATES,
                     agentId:String?=null,search:String=""):List<ReminderSummary> {
        if(states?.isEmpty()==true) return emptyList()
        val where=mutableListOf<String>();val args=mutableListOf<String>()
        states?.let {where+="state IN (${it.joinToString(",") {"?"}})";args+=it}
        agentId?.let {where+="created_by=?";args+=it}
        if(search.isNotBlank()) {where+="title LIKE ? ESCAPE '\\'";args+=literalSearch(search)}
        cursor?.let {where+="(fire_at>? OR (fire_at=? AND reminder_id>?))";args+=listOf(it.fireAtMs.toString(),it.fireAtMs.toString(),it.id)}
        return db.readableDatabase.query("reminders",arrayOf("reminder_id","fire_at","title","state","created_by"),
            where.takeIf {it.isNotEmpty()}?.joinToString(" AND "),args.toTypedArray(),null,null,"fire_at,reminder_id",limit.coerceIn(1,101).toString())
            .mapAll {ReminderSummary(it.getString(0),it.getLong(1),it.getString(2),it.getString(3),it.getString(4))}
    }
    fun runPage(automationId:String,limit:Int=30,cursor:AutomationRun?=null,
                filter:AutomationRunFilter=AutomationRunFilter.ALL):List<AutomationRun> {
        val where=mutableListOf("automation_id=?");val args=mutableListOf(automationId)
        when(filter) {
            AutomationRunFilter.ACTIVE->where+="state IN ('READY','RUNNING','AWAITING_APPROVAL')"
            AutomationRunFilter.ATTENTION->where+="state IN ('AWAITING_APPROVAL','FAILED','PARTIALLY_SUCCEEDED','BLOCKED','UNKNOWN_OUTCOME','MISSED')"
            AutomationRunFilter.COMPLETED->where+="state IN ('SUCCEEDED','CANCELLED')"
            else->Unit
        }
        cursor?.let {
            require(it.automationId==automationId) {"Run cursor belongs to a different automation"}
            where+="(slot_at<? OR (slot_at=? AND run_id<?))";args+=listOf(it.slotAt.toString(),it.slotAt.toString(),it.runId)
        }
        return db.readableDatabase.query("automation_runs",arrayOf("run_id","automation_id","slot_at","state","finished_at","error_code"),
            where.joinToString(" AND "),args.toTypedArray(),null,null,"slot_at DESC,run_id DESC",limit.coerceIn(1,101).toString())
            .mapAll {AutomationRun(it.getString(0),it.getString(1),it.getLong(2),it.getString(3),if(it.isNull(4)) null else it.getLong(4),it.getString(5))}
    }
    companion object {
        val ACTIVE_REMINDER_STATES=setOf("SCHEDULED","BLOCKED","FIRING","AWAITING_APPROVAL","CANCELLING")
        private fun literalSearch(value:String)="%"+value.trim().take(200).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
    }
}
