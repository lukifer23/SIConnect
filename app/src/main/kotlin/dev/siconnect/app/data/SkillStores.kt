package dev.siconnect.app.data

import android.content.ContentValues
import dev.siconnect.core.*

class SqlSkillRunStore(private val db: SIConnectDb) : SkillRunStore {
    override fun insert(run: SkillExecution): Boolean = db.writableDatabase.insertWithOnConflict("skill_runs", null,
        values(run), android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE) != -1L
    override fun get(runId: String): SkillExecution? = db.readableDatabase.query("skill_runs", arrayOf("json"), "run_id=?", arrayOf(runId), null, null, null)
        .mapAll { AppJson.decodeFromString(SkillExecution.serializer(), it.getString(0)) }.firstOrNull()
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
    override fun active() = db.readableDatabase.query("skill_runs", arrayOf("json"), "state IN ('READY','RUNNING','AWAITING_APPROVAL')", null, null, null, null)
        .mapAll { AppJson.decodeFromString(SkillExecution.serializer(), it.getString(0)) }
    private fun values(r: SkillExecution) = ContentValues().apply {
        put("run_id", r.runId); put("state", r.state.name); put("revision", r.revision); put("json", AppJson.encodeToString(SkillExecution.serializer(), r))
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
    fun all(): List<SkillDefinition> = db.readableDatabase.query("skills", arrayOf("json"), "enabled=1", null, null, null, null)
        .mapAll { SkillJson.parse(it.getString(0)) }
    fun get(id: String) = all().firstOrNull { it.id == id }
    fun enable(id: String, enabled: Boolean) { db.writableDatabase.update("skills", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "skill_id=?", arrayOf(id)) }
    fun remove(id: String) { db.writableDatabase.delete("skills", "skill_id=?", arrayOf(id)) }
}
