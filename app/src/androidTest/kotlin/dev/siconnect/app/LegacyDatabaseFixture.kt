package dev.siconnect.app

import android.database.sqlite.SQLiteDatabase

/** Test-only reconstruction of v10 tables. Works on Android SQLite versions without DROP COLUMN. */
internal fun SQLiteDatabase.removeExecutionRetentionSchema() {
    execSQL("CREATE TABLE legacy_request_tombstones (request_id TEXT PRIMARY KEY,agent_id TEXT NOT NULL,idempotency_key TEXT,updated_at INTEGER NOT NULL,json TEXT NOT NULL)")
    execSQL("INSERT INTO legacy_request_tombstones SELECT request_id,agent_id,idempotency_key,updated_at,json FROM request_tombstones")
    execSQL("DROP TABLE request_tombstones");execSQL("ALTER TABLE legacy_request_tombstones RENAME TO request_tombstones")
    execSQL("CREATE UNIQUE INDEX tombstones_idem ON request_tombstones(agent_id,idempotency_key) WHERE idempotency_key IS NOT NULL")
    execSQL("CREATE INDEX tombstones_updated ON request_tombstones(updated_at)")
    execSQL("CREATE TABLE legacy_skill_runs (run_id TEXT PRIMARY KEY,state TEXT NOT NULL,revision INTEGER NOT NULL,json TEXT NOT NULL)")
    execSQL("INSERT INTO legacy_skill_runs SELECT run_id,state,revision,json FROM skill_runs")
    execSQL("DROP TABLE skill_runs"); execSQL("ALTER TABLE legacy_skill_runs RENAME TO skill_runs")
    execSQL("""CREATE TABLE legacy_automation_runs (run_id TEXT PRIMARY KEY,automation_id TEXT NOT NULL,slot_at INTEGER NOT NULL,
        started_at INTEGER NOT NULL,finished_at INTEGER,state TEXT NOT NULL,json TEXT,automation_json TEXT,skill_json TEXT,
        error_code TEXT,updated_at INTEGER NOT NULL DEFAULT 0)""")
    execSQL("INSERT INTO legacy_automation_runs SELECT run_id,automation_id,slot_at,started_at,finished_at,state,json,automation_json,skill_json,error_code,updated_at FROM automation_runs")
    execSQL("DROP TABLE automation_runs"); execSQL("ALTER TABLE legacy_automation_runs RENAME TO automation_runs")
    execSQL("CREATE UNIQUE INDEX automation_runs_slot ON automation_runs(automation_id,slot_at)")
    execSQL("CREATE INDEX automation_runs_reconcile ON automation_runs(state,automation_id)")
    execSQL("DROP INDEX notification_dedup_request")
}
