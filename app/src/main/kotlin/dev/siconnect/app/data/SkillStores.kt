package dev.siconnect.app.data

import android.content.ContentValues
import dev.siconnect.core.*

class SqlSkillRunStore(private val db: SIConnectDb) : SkillRunStore {
    override fun insert(run: SkillExecution): Boolean = db.writableDatabase.insertWithOnConflict("skill_runs", null,
        values(run), android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE) != -1L
    override fun get(runId: String): SkillExecution? = db.readableDatabase.storedJson("skill_runs","run_id",runId)
        ?.let {AppJson.decodeFromString(SkillExecution.serializer(),it)}
    override fun update(run: SkillExecution, expectedRevision: Long): Boolean {
        val w = db.writableDatabase; w.beginTransaction()
        try {
            val changed = w.update("skill_runs", values(run), "run_id=? AND revision=?", arrayOf(run.runId, expectedRevision.toString())) == 1
            if (changed) run.steps.forEach { step -> w.insertWithOnConflict("step_results", null, ContentValues().apply {
                put("run_id", run.runId); put("step_id", step.id); put("json", AppJson.encodeToString(StepOutcome.serializer(), step))
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) }
            w.setTransactionSuccessful(); return changed
        } finally { w.endTransaction() }
    }
    override fun active() = db.readableDatabase.query("skill_runs", arrayOf("run_id"), "state IN ('READY','RUNNING','AWAITING_APPROVAL')", null, null, null, null)
        .mapAll {it.getString(0)}.mapNotNull(::get)
    override fun cancellations() = db.readableDatabase.query("skill_runs",arrayOf("run_id"),"state = ?",arrayOf(SkillState.UNKNOWN_OUTCOME.name),null,null,null)
        .mapAll {it.getString(0)}.mapNotNull(::get)
        .filter {it.error in setOf("CANCELLATION_REQUESTED","CANCELLATION_HANDOFF_UNCERTAIN")}
    private fun values(r: SkillExecution) = ContentValues().apply {
        put("run_id", r.runId); put("state", r.state.name); put("revision", r.revision); put("json", AppJson.encodeToString(SkillExecution.serializer(), r))
        put("updated_at",System.currentTimeMillis()); put("payload_expired",if(r.payloadExpired) 1 else 0)
    }
}

class SkillCatalog(private val db: SIConnectDb, private val registry: CapabilityRegistry) {
    fun install(def: SkillDefinition) {
        val errors = SkillValidator.validate(def, registry); require(errors.isEmpty()) { errors.joinToString("; ") }
        db.writableDatabase.insertWithOnConflict("skills", null, ContentValues().apply {
            put("skill_id", def.id); put("version", def.version); put("enabled", 1); put("builtin", 0)
            put("installed_at", System.currentTimeMillis()); put("json", SkillJson.json.encodeToString(SkillDefinition.serializer(), def))
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun all(includeDisabled:Boolean=false): List<SkillDefinition> = db.readableDatabase.query("skills", arrayOf("json"), if(includeDisabled) null else "enabled=1", null, null, null, null)
        .mapAll { SkillJson.parse(it.getString(0)) }
    /** Owner projection: definition and enabled state come from the same SQLite cursor. */
    fun rowsIncludingDisabled():List<Pair<SkillDefinition,Boolean>> = db.readableDatabase.query("skills",arrayOf("json","enabled"),null,null,null,null,"skill_id")
        .mapAll {SkillJson.parse(it.getString(0)) to (it.getInt(1)==1)}
    /** Atomic owner update: a changed or disabled definition cannot be overwritten after review. */
    fun replaceIfUnchanged(expected:SkillDefinition,replacement:SkillDefinition) {
        require(expected.id==replacement.id && replacement.version>expected.version) {"Invalid workflow upgrade"}
        require(SkillValidator.validate(replacement,registry).isEmpty()) {"Invalid replacement workflow"}
        val json=SkillJson.json
        val updated=db.writableDatabase.update("skills",ContentValues().apply {
            put("version",replacement.version);put("json",json.encodeToString(SkillDefinition.serializer(),replacement));put("installed_at",System.currentTimeMillis())
        },"skill_id=? AND enabled=1 AND json=?",arrayOf(expected.id,json.encodeToString(SkillDefinition.serializer(),expected)))
        check(updated==1) {"Workflow changed or was disabled; reopen Automations before upgrading"}
    }
    fun exists(id:String):Boolean = db.readableDatabase.query("skills",arrayOf("skill_id"),"skill_id=?",arrayOf(id),null,null,null,"1").use {it.moveToFirst()}
    fun isEnabled(id:String):Boolean = db.readableDatabase.query("skills",arrayOf("enabled"),"skill_id=?",arrayOf(id),null,null,null,"1").use {it.moveToFirst() && it.getInt(0)==1}
    fun get(id:String):SkillDefinition? = db.readableDatabase.query("skills",arrayOf("json"),"skill_id=? AND enabled=1",arrayOf(id),null,null,null,"1")
        .mapAll {SkillJson.parse(it.getString(0))}.firstOrNull()
    fun enable(id: String, enabled: Boolean) { db.writableDatabase.update("skills", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "skill_id=?", arrayOf(id)) }
    fun remove(id: String) { db.writableDatabase.delete("skills", "skill_id=?", arrayOf(id)) }
}
