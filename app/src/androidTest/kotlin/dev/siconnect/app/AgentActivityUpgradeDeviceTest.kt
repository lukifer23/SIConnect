package dev.siconnect.app

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.*
import dev.siconnect.core.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AgentActivityUpgradeDeviceTest {
    @Test fun busyHistoryAndCredentialsSurviveUpgradeAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "history-upgrade-${UUID.randomUUID()}.db"
        val quiet = AgentRecord(AgentGrant("quiet", "Quiet"), Canonical.sha256Hex("quiet-fixture"), 1, tokenHint = "fixture")
        val busy = quiet.copy(grant = AgentGrant("busy", "Busy", revoked = true), tokenSha256 = Canonical.sha256Hex("busy-fixture"))
        fun record(id: String, agent: String, time: Long) = RequestRecord(id, id, agent, "fixture", "device.status", 1,
            JsonObject(emptyMap()), id, id, "REMOTE", RequestState.SUCCEEDED, createdAtMs = time, updatedAtMs = time, expiresAtMs = time + 1)
        try {
            object : SQLiteOpenHelper(context, file, null, 13) {
                override fun onCreate(db: SQLiteDatabase) {
                    SIConnectDb(context, file).use { it.onUpgrade(db, 0, 13) }
                }
                override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = error("Unexpected fixture migration")
            }.use { it.writableDatabase }
            // Open the v13 file directly so all fixture rows predate the real upgrade.
            SQLiteDatabase.openDatabase(context.getDatabasePath(file).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.writeTransaction {
                    for (a in listOf(quiet, busy, quiet.copy(grant = AgentGrant("unused", "Unused"), tokenSha256 = "unused"))) {
                        db.execSQL("INSERT INTO agents(agent_id,token_sha256,created_at,json) VALUES(?,?,?,?)",
                            arrayOf<Any>(a.grant.agentId, a.tokenSha256, a.createdAtMs, AppJson.encodeToString(AgentRecord.serializer(), a)))
                    }
                    fun insert(table: String, r: RequestRecord) {
                        db.execSQL("INSERT INTO $table(request_id,agent_id,capability_id,state,created_at,updated_at,json) VALUES(?,?,?,?,?,?,?)",
                            arrayOf<Any>(r.requestId, r.agentId, r.capabilityId, r.state.name, r.createdAtMs, r.updatedAtMs, "unused payload"))
                    }
                    insert("requests", record("quiet-a", "quiet", 2))
                    insert("request_tombstones", record("quiet-z", "quiet", 2))
                    repeat(2000) { i -> insert("requests", record("busy-$i", "busy", 3L + i)) }
                }
            }
            repeat(2) {
                SIConnectDb(context, file).use { db ->
                    val store = SqlRequestStore(db)
                    val latest = store.latestByAgent()
                    assertEquals(setOf("quiet", "busy"), latest.keys)
                    assertEquals("busy-1999", latest.getValue("busy").requestId)
                    assertEquals("quiet-z", latest.getValue("quiet").requestId)
                    assertTrue(latest.getValue("quiet").payloadExpired)
                    assertTrue(store.historyPage(50).all { it.agentId == "busy" })
                    assertEquals(listOf("quiet-z", "quiet-a"), store.historyPage(20, agentId = "quiet").map { it.requestId })
                    assertEquals(listOf("busy"), AgentStore(db).revokedRemoteIds())
                    assertEquals(quiet, AgentStore(db).byCredentialHash(quiet.tokenSha256))
                    assertEquals(busy, AgentStore(db).byCredentialHash(busy.tokenSha256))
                    val plan = db.readableDatabase.rawQuery("EXPLAIN QUERY PLAN $LATEST_AGENT_HISTORY", null).mapAll { it.getString(3) }
                    for (index in listOf("requests_agent_history", "tombstones_agent_history")) {
                        assertTrue(plan.joinToString("\n"), plan.any { "SEARCH" in it && index in it })
                    }
                    assertFalse(plan.joinToString("\n"), plan.any { it.startsWith("SCAN requests ") || it.startsWith("SCAN request_tombstones ") })
                }
            }
        } finally { context.deleteDatabase(file) }
    }
}
