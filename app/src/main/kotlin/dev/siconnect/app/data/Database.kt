package dev.siconnect.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteOpenHelper
import dev.siconnect.core.AgentDirectory
import dev.siconnect.core.AgentGrant
import dev.siconnect.core.Event
import dev.siconnect.core.EventSink
import dev.siconnect.core.RequestRecord
import dev.siconnect.core.RequestState
import dev.siconnect.core.RequestStore
import kotlinx.serialization.json.Json

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/**
 * Single SQLite database. Migrations are explicit and append-only: add a new `if (old < N)` block,
 * never edit an existing one.
 */
class SIConnectDb(context: Context) : SQLiteOpenHelper(context, "siconnect.db", null, VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = onUpgrade(db, 0, VERSION)

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 1) {
            db.execSQL("""CREATE TABLE requests (
                request_id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, idempotency_key TEXT,
                capability_id TEXT NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, parent_request_id TEXT, json TEXT NOT NULL)""")
            db.execSQL("CREATE UNIQUE INDEX requests_idem ON requests(agent_id, idempotency_key) WHERE idempotency_key IS NOT NULL")
            db.execSQL("CREATE INDEX requests_state ON requests(state)")
            db.execSQL("CREATE INDEX requests_updated ON requests(updated_at)")
            db.execSQL("""CREATE TABLE events (
                seq INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL, ts INTEGER NOT NULL,
                event_type TEXT NOT NULL, trace_id TEXT, request_id TEXT, agent_id TEXT,
                capability_id TEXT, execution_status TEXT, error_code TEXT, json TEXT NOT NULL)""")
            db.execSQL("CREATE INDEX events_ts ON events(ts)")
            db.execSQL("CREATE INDEX events_request ON events(request_id)")
            db.execSQL("CREATE INDEX events_trace ON events(trace_id)")
            db.execSQL("""CREATE TABLE agents (
                agent_id TEXT PRIMARY KEY, token_sha256 TEXT NOT NULL, created_at INTEGER NOT NULL,
                last_seen_at INTEGER, json TEXT NOT NULL)""")
            db.execSQL("""CREATE TABLE skills (
                skill_id TEXT PRIMARY KEY, version INTEGER NOT NULL, enabled INTEGER NOT NULL,
                builtin INTEGER NOT NULL, installed_at INTEGER NOT NULL, json TEXT NOT NULL)""")
            db.execSQL("""CREATE TABLE automations (
                automation_id TEXT PRIMARY KEY, enabled INTEGER NOT NULL, next_run_at INTEGER,
                owner TEXT NOT NULL, json TEXT NOT NULL)""")
            db.execSQL("""CREATE TABLE automation_runs (
                run_id TEXT PRIMARY KEY, automation_id TEXT NOT NULL, slot_at INTEGER NOT NULL,
                started_at INTEGER NOT NULL, finished_at INTEGER, state TEXT NOT NULL, json TEXT)""")
            // One run per (automation, scheduled slot): duplicate alarm deliveries cannot double-run.
            db.execSQL("CREATE UNIQUE INDEX automation_runs_slot ON automation_runs(automation_id, slot_at)")
            db.execSQL("""CREATE TABLE reminders (
                reminder_id TEXT PRIMARY KEY, fire_at INTEGER NOT NULL, state TEXT NOT NULL,
                created_by TEXT NOT NULL, json TEXT NOT NULL)""")
            db.execSQL("""CREATE TABLE notifications (
                key TEXT PRIMARY KEY, package_name TEXT NOT NULL, posted_at INTEGER NOT NULL,
                removed_at INTEGER, json TEXT NOT NULL)""")
            db.execSQL("CREATE INDEX notifications_posted ON notifications(posted_at)")
        }
        if (old < 2) {
            db.execSQL("CREATE TABLE request_transitions (seq INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL, from_state TEXT, to_state TEXT NOT NULL, ts INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX transitions_request ON request_transitions(request_id)")
            db.execSQL("CREATE TABLE skill_runs (run_id TEXT PRIMARY KEY, state TEXT NOT NULL, revision INTEGER NOT NULL, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE step_results (run_id TEXT NOT NULL, step_id TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(run_id, step_id))")
        }
        if (old < 3) db.execSQL("ALTER TABLE reminders ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
    }

    companion object { const val VERSION = 3 }
}

inline fun <T> Cursor.mapAll(block: (Cursor) -> T): List<T> = use { c ->
    buildList { while (c.moveToNext()) add(block(c)) }
}

/** Small history projection: listing activity never decodes potentially large shell output. */
data class RequestSummary(val requestId:String,val agentId:String,val capabilityId:String,val state:RequestState,val createdAtMs:Long,val updatedAtMs:Long)

class SqlRequestStore(private val db: SIConnectDb) : RequestStore {
    override fun insert(record: RequestRecord): Boolean {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            w.insertOrThrow("requests", null, values(record))
            transition(w, record, null)
            w.setTransactionSuccessful()
            return true
        } catch (_: SQLiteConstraintException) { return false }
        finally { w.endTransaction() }
    }

    override fun update(record: RequestRecord, expected: RequestState): Boolean {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            val changed = w.update("requests", values(record), "request_id = ? AND state = ?", arrayOf(record.requestId, expected.name)) == 1
            if (changed) transition(w, record, expected)
            w.setTransactionSuccessful()
            return changed
        } finally { w.endTransaction() }
    }

    private fun transition(w: SQLiteDatabase, r: RequestRecord, from: RequestState?) {
        w.insertOrThrow("request_transitions", null, ContentValues().apply {
            put("request_id", r.requestId); put("from_state", from?.name); put("to_state", r.state.name); put("ts", r.updatedAtMs)
        })
    }

    override fun get(requestId: String): RequestRecord? =
        query("request_id = ?", arrayOf(requestId)).firstOrNull()

    override fun findByIdempotencyKey(agentId: String, key: String): RequestRecord? =
        query("agent_id = ? AND idempotency_key = ?", arrayOf(agentId, key)).firstOrNull()

    override fun listByStates(states: Set<RequestState>): List<RequestRecord> =
        if (states.isEmpty()) emptyList()
        else query("state IN (${states.joinToString(",") { "?" }})", states.map { it.name }.toTypedArray())

    fun recent(limit: Int, states: Set<RequestState>? = null): List<RequestRecord> {
        val where = states?.let { s -> "state IN (${s.joinToString(",") { "?" }})" }
        return db.readableDatabase.query("requests", arrayOf("json"), where, states?.map { it.name }?.toTypedArray(),
            null, null, "updated_at DESC", limit.toString()).mapAll { decode(it.getString(0)) }
    }

    fun recentSummaries(limit:Int=100):List<RequestSummary> = db.readableDatabase.query("requests",
        arrayOf("request_id","agent_id","capability_id","state","created_at","updated_at"),null,null,null,null,"updated_at DESC, request_id DESC",limit.coerceIn(1,500).toString())
        .mapAll { RequestSummary(it.getString(0),it.getString(1),it.getString(2),RequestState.valueOf(it.getString(3)),it.getLong(4),it.getLong(5)) }

    fun recentDeniedApps():List<RequestRecord> = db.readableDatabase.query("requests",arrayOf("json"),"state = ? AND capability_id LIKE ?",
        arrayOf(RequestState.DENIED.name,"apps.%"),null,null,"updated_at DESC","10").mapAll {decode(it.getString(0))}

    fun prune(olderThanMs: Long): Int {
        val terminal = RequestState.TERMINAL.joinToString(",") { "'${it.name}'" }
        // Keep idempotency tombstones for retained workflow children; pruning them would permit replay.
        return db.writableDatabase.delete("requests", "updated_at < ? AND state IN ($terminal) AND parent_request_id IS NULL", arrayOf(olderThanMs.toString()))
    }

    private fun query(where: String, args: Array<String>) =
        db.readableDatabase.query("requests", arrayOf("json"), where, args, null, null, null).mapAll { decode(it.getString(0)) }

    private fun decode(s: String) = AppJson.decodeFromString(RequestRecord.serializer(), s)

    private fun values(r: RequestRecord) = ContentValues().apply {
        put("request_id", r.requestId); put("agent_id", r.agentId); put("idempotency_key", r.idempotencyKey)
        put("capability_id", r.capabilityId); put("state", r.state.name); put("created_at", r.createdAtMs)
        put("updated_at", r.updatedAtMs); put("parent_request_id", r.parentRequestId)
        put("json", AppJson.encodeToString(RequestRecord.serializer(), r))
    }
}

/** Durable, bounded structured event log. Never pass secrets or message bodies into [Event.detail]. */
class SqlEventLog(private val db: SIConnectDb, private val maxEvents: Int = 50_000) : EventSink {
    @Volatile private var sinceTrim = 0

    override fun emit(event: Event) {
        db.writableDatabase.insertOrThrow("events", null, ContentValues().apply {
            put("event_id", event.eventId); put("ts", event.timestampMs); put("event_type", event.eventType)
            put("trace_id", event.traceId); put("request_id", event.requestId); put("agent_id", event.agentId)
            put("capability_id", event.capabilityId); put("execution_status", event.executionStatus)
            put("error_code", event.errorCode); put("json", AppJson.encodeToString(Event.serializer(), event))
        })
        if (++sinceTrim >= 500) { sinceTrim = 0; trim() }
    }

    fun query(
        sinceMs: Long? = null, untilMs: Long? = null, requestId: String? = null, traceId: String? = null,
        eventTypePrefix: String? = null, errorsOnly: Boolean = false, limit: Int = 200,
    ): List<Event> {
        val where = mutableListOf<String>(); val args = mutableListOf<String>()
        sinceMs?.let { where += "ts >= ?"; args += it.toString() }
        untilMs?.let { where += "ts <= ?"; args += it.toString() }
        requestId?.let { where += "(request_id = ? OR request_id LIKE ?)"; args += it; args += "$it:%" }
        traceId?.let { where += "trace_id = ?"; args += it }
        eventTypePrefix?.let { where += "event_type LIKE ?"; args += "$it%" }
        if (errorsOnly) where += "error_code IS NOT NULL"
        return db.readableDatabase.query("events", arrayOf("json"), where.joinToString(" AND ").ifEmpty { null },
            args.toTypedArray(), null, null, "seq DESC", limit.coerceIn(1, 5000).toString())
            .mapAll { AppJson.decodeFromString(Event.serializer(), it.getString(0)) }
    }

    fun count(): Long = android.database.DatabaseUtils.queryNumEntries(db.readableDatabase, "events")

    fun trim(olderThanMs: Long? = null) {
        val w = db.writableDatabase
        olderThanMs?.let { w.delete("events", "ts < ?", arrayOf(it.toString())) }
        w.execSQL("DELETE FROM events WHERE seq <= (SELECT MAX(seq) FROM events) - $maxEvents")
    }
}

@kotlinx.serialization.Serializable
data class AgentRecord(
    val grant: AgentGrant,
    val tokenSha256: String,
    val createdAtMs: Long,
    val lastSeenAtMs: Long? = null,
    /** Short token prefix for display only (never the token itself). */
    val tokenHint: String,
)

class AgentStore(private val db: SIConnectDb) : AgentDirectory {
    override fun grant(agentId: String): AgentGrant? = get(agentId)?.grant

    fun get(agentId: String): AgentRecord? = db.readableDatabase.query("agents", arrayOf("json"), "agent_id = ?",
        arrayOf(agentId), null, null, null).mapAll { decode(it.getString(0)) }.firstOrNull()

    fun all(): List<AgentRecord> = db.readableDatabase.query("agents", arrayOf("json"), null, null, null, null, "created_at")
        .mapAll { decode(it.getString(0)) }

    fun put(record: AgentRecord) {
        db.writableDatabase.insertWithOnConflict("agents", null, ContentValues().apply {
            put("agent_id", record.grant.agentId); put("token_sha256", record.tokenSha256)
            put("created_at", record.createdAtMs); put("last_seen_at", record.lastSeenAtMs)
            put("json", AppJson.encodeToString(AgentRecord.serializer(), record))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(agentId: String) { db.writableDatabase.delete("agents", "agent_id = ?", arrayOf(agentId)) }

    fun touch(agentId: String, now: Long) { get(agentId)?.let { put(it.copy(lastSeenAtMs = now)) } }

    private fun decode(s: String) = AppJson.decodeFromString(AgentRecord.serializer(), s)
}

/** Generic JSON document table access for skills/automations/reminders/notifications. */
class JsonTable(private val db: SIConnectDb, private val table: String, private val keyColumn: String) {
    fun get(key: String): String? = db.readableDatabase.query(table, arrayOf("json"), "$keyColumn = ?", arrayOf(key),
        null, null, null).mapAll { it.getString(0) }.firstOrNull()

    fun list(where: String? = null, args: Array<String>? = null, orderBy: String? = null, limit: Int? = null): List<String> =
        db.readableDatabase.query(table, arrayOf("json"), where, args, null, null, orderBy, limit?.toString())
            .mapAll { it.getString(0) }

    fun upsert(values: ContentValues) {
        db.writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Insert that fails (returns false) on a unique-constraint conflict. */
    fun insertUnique(values: ContentValues): Boolean =
        db.writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L

    fun update(values: ContentValues, where: String, args: Array<String>): Int = db.writableDatabase.update(table, values, where, args)

    fun delete(where: String, args: Array<String>): Int = db.writableDatabase.delete(table, where, args)
}
