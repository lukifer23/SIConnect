package dev.siconnect.app.data

import android.content.ContentValues
import android.database.DatabaseUtils
import dev.siconnect.core.*

/** Disabled until the owner chooses an interval. Compact identities are deliberately permanent. */
data class RetentionPolicy(val days: Int? = null) {
    init { require(days == null || days in OPTIONS) { "Choose a supported retention interval" } }
    fun cutoff(nowMs: Long): Long? = days?.let { nowMs - it * 86_400_000L }
    companion object { val OPTIONS = listOf(7, 30, 90, 365) }
}

data class RetentionBatch(val workflows: Int = 0, val requests: Int = 0, val automationRuns: Int = 0) {
    val total: Int get() = workflows + requests + automationRuns
}

data class StorageSummary(val allocatedBytes: Long, val reusableBytes: Long, val fullRequests: Long,
                          val compactRequests: Long, val fullWorkflows: Long, val compactWorkflows: Long,
                          val notificationClaims: Long)

/** Each pass owns a bounded number of rows. No VACUUM, replay-identity expiry or owner-row deletion.
 * The same monitor serializes owner changes and scheduled passes, including disabling retention. */
class StorageRetention(private val db: SIConnectDb, private val readPolicy: () -> RetentionPolicy,
                       private val writePolicy: (RetentionPolicy) -> Unit) {
    private var workflowCursor: Pair<Long, String>? = null
    @Synchronized fun configure(policy: RetentionPolicy) { writePolicy(policy); workflowCursor=null }

    @Synchronized fun maintain(nowMs: Long = System.currentTimeMillis(), limit: Int = 25): RetentionBatch {
        val cutoff = readPolicy().cutoff(nowMs) ?: return RetentionBatch()
        val batchSize = limit.coerceIn(1, 100)
        val workflows = compactWorkflows(cutoff, batchSize)
        val requests = SqlRequestStore(db).prune(cutoff, batchSize)
        val automations = compactAutomations(cutoff, batchSize)
        return RetentionBatch(workflows, requests, automations)
    }

    fun summary(): StorageSummary {
        val r = db.readableDatabase
        fun scalar(sql: String) = DatabaseUtils.longForQuery(r, sql, null)
        val pageSize = scalar("PRAGMA page_size")
        return StorageSummary(scalar("PRAGMA page_count") * pageSize, scalar("PRAGMA freelist_count") * pageSize,
            scalar("SELECT count(*) FROM requests"), scalar("SELECT count(*) FROM request_tombstones"),
            scalar("SELECT count(*) FROM skill_runs WHERE payload_expired=0"), scalar("SELECT count(*) FROM skill_runs WHERE payload_expired=1"),
            scalar("SELECT count(*) FROM notification_dedup"))
    }

    private fun compactWorkflows(cutoff: Long, limit: Int): Int = SqlRequestStore(db).atomic {
        val w = db.writableDatabase
        val cursor=workflowCursor
        val after=if(cursor==null) "" else "AND (s.updated_at>? OR (s.updated_at=? AND s.run_id>?))"
        val args=mutableListOf(cutoff.toString())
        cursor?.let {args+=listOf(it.first.toString(),it.first.toString(),it.second)}
        args+=limit.toString()
        val candidates = w.rawQuery("""SELECT s.run_id,s.updated_at FROM skill_runs s WHERE s.payload_expired=0 AND s.updated_at<? $after
            AND s.state IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELLED')
            AND NOT EXISTS (SELECT 1 FROM requests r WHERE (r.parent_request_id=s.run_id OR r.request_id=s.run_id)
                AND r.state NOT IN ('SUCCEEDED','FAILED','CANCELLED','DENIED','EXPIRED'))
            AND NOT EXISTS (SELECT 1 FROM automation_runs a WHERE a.run_id=s.run_id
                AND a.state NOT IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','BLOCKED','CANCELLED','MISSED'))
            AND NOT EXISTS (SELECT 1 FROM proposals p WHERE p.request_id=s.run_id AND p.state IN ('ACCEPTED','EXECUTING'))
            ORDER BY s.updated_at,s.run_id LIMIT ?""", args.toTypedArray()).mapAll { it.getString(0) to it.getLong(1) }
        // A legacy run with missing linked evidence cannot starve newer, safe work forever.
        workflowCursor=candidates.lastOrNull()?.let {it.second to it.first}
        val runs = SqlSkillRunStore(db)
        var changed = 0
        for ((id, _) in candidates) {
            val run = runs.get(id) ?: continue
            // Include explicit links as well as the indexed parent relationship. Missing evidence
            // is conservative; it never authorizes deletion or execution of an old child.
            val linked = (run.steps.mapNotNull { it.requestId } + listOfNotNull(run.pendingRequestId)).distinct()
            if (linked.any { requestId ->
                val state = w.rawQuery("SELECT state FROM requests WHERE request_id=?", arrayOf(requestId))
                    .use { if (it.moveToFirst()) RequestState.valueOf(it.getString(0)) else null }
                    ?: SqlRequestStore(db).get(requestId)?.state
                state == null || !state.isTerminal || state == RequestState.UNKNOWN_OUTCOME
            }) continue
            val compact = run.compactPayload().copy(revision = run.revision + 1)
            if (runs.update(compact, run.revision)) {
                // update() writes compact step metadata; retain it in the run only.
                w.delete("step_results", "run_id=?", arrayOf(id))
                changed++
            }
        }
        changed
    }

    private fun compactAutomations(cutoff: Long, limit: Int): Int = SqlRequestStore(db).atomic {
        val w = db.writableDatabase
        val ids = w.rawQuery("""SELECT a.run_id FROM automation_runs a WHERE a.payload_expired=0 AND a.finished_at<?
            AND a.state IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','BLOCKED','CANCELLED','MISSED')
            AND NOT EXISTS (SELECT 1 FROM skill_runs s WHERE s.run_id=a.run_id AND s.payload_expired=0)
            AND NOT EXISTS (SELECT 1 FROM requests r WHERE (r.request_id=a.run_id OR r.parent_request_id=a.run_id)
                AND r.state NOT IN ('SUCCEEDED','FAILED','CANCELLED','DENIED','EXPIRED'))
            AND NOT EXISTS (SELECT 1 FROM proposals p WHERE p.request_id=a.run_id AND p.state IN ('ACCEPTED','EXECUTING'))
            ORDER BY a.finished_at,a.run_id LIMIT ?""", arrayOf(cutoff.toString(), limit.toString())).mapAll { it.getString(0) }
        ids.sumOf { id -> w.update("automation_runs", ContentValues().apply {
            putNull("json"); putNull("automation_json"); putNull("skill_json"); put("payload_expired", 1)
        }, "run_id=?", arrayOf(id)) }
        // automation_id + slot_at and run summaries remain: late alarms cannot claim a new run.
    }
}
