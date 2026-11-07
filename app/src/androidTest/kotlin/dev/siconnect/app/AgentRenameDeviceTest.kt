package dev.siconnect.app

import android.content.ContentValues
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.*
import dev.siconnect.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Isolated real SQLite files only: no production identities, credentials, network, or UI. */
@RunWith(AndroidJUnit4::class)
class AgentRenameDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val id="development-client"
    private fun fixture():AgentRecord {
        val custom=AgentGrant(id,"Original saved name",scopes=listOf("device.status","requests.*"),
            denied=listOf("apps.uninstall"),autoApproveUpTo=RiskClass.READ_ONLY,shellMode=ShellMode.STANDING_COMMANDS,
            shellStandingCommands=listOf("id"),allowSharedScreen=false,expiresAtMs=123456789,
            standing=listOf(StandingGrant("device.status",backend=BackendKind.NATIVE,maxRisk=RiskClass.READ_ONLY,
                constraints=buildJsonObject {put("fixture",true)},note="Original custom profile")))
        return AgentRecord(custom.copy(scopes=listOf("*"),denied=emptyList(),autoApproveUpTo=RiskClass.CRITICAL,
            shellMode=ShellMode.BROAD,allowSharedScreen=true,fullControl=true,expiresAtMs=null),
            Canonical.sha256Hex("isolated-rename-${UUID.randomUUID()}"),1000,1100,"isolated test credential",custom)
    }
    private fun raw(db:SIConnectDb)=db.readableDatabase.query("agents",arrayOf("json"),"agent_id=?",arrayOf(id),null,null,null)
        .use {assertTrue(it.moveToFirst());it.getString(0)}
    private fun indexed(db:SIConnectDb)=db.readableDatabase.query("agents",arrayOf("agent_id","token_sha256","created_at","last_seen_at"),"agent_id=?",arrayOf(id),null,null,null)
        .use {assertTrue(it.moveToFirst());List(4) {column->if(it.isNull(column)) null else it.getString(column)}}
    private fun renamed(raw:JsonObject,label:String)=JsonObject(raw.toMutableMap().apply {
        put("grant",JsonObject(raw.getValue("grant").jsonObject.toMutableMap().apply {put("label",JsonPrimitive(label))}))
    })
    private fun rejected(block:()->Unit) {
        try {block();fail("The invalid or stale rename must be rejected")}
        catch(_:IllegalArgumentException) {}
        catch(_:IllegalStateException) {}
    }

    @Test fun renamePersistsOnlyCurrentLabelAndPreservesCredentialLookupFullGrantAndSavedProfile() {
        val file="agent-rename-${UUID.randomUUID()}.db"
        val original=fixture()
        try {
            val expected=SIConnectDb(context,file).use {db->
                val store=AgentStore(db);store.put(original)
                // Unknown fields must survive a label edit, including fields in the saved profile.
                val stored=AppJson.parseToJsonElement(raw(db)).jsonObject
                val extended=JsonObject(stored.toMutableMap().apply {
                    put("futureIdentityMetadata",buildJsonObject {put("preserve",true)})
                    put("grant",JsonObject(stored.getValue("grant").jsonObject.toMutableMap().apply {put("futureGrantMetadata",JsonPrimitive("keep"))}))
                    put("fullAccessPreviousGrant",JsonObject(stored.getValue("fullAccessPreviousGrant").jsonObject.toMutableMap().apply {put("futureProfileMetadata",JsonPrimitive(9))}))
                })
                db.writableDatabase.update("agents",ContentValues().apply {put("json",extended.toString())},"agent_id=?",arrayOf(id))
                val beforeIndex=indexed(db)
                val updated=store.rename(id,"  Grok Bot  ",original.grant.label)
                assertEquals(original.copy(grant=original.grant.copy(label="Grok Bot")),updated)
                assertEquals(beforeIndex,indexed(db))
                renamed(extended,"Grok Bot").also {assertEquals(it,AppJson.parseToJsonElement(raw(db)))}
            }
            SIConnectDb(context,file).use {reopened->
                val saved=AgentStore(reopened).byCredentialHash(original.tokenSha256)
                assertEquals(original.copy(grant=original.grant.copy(label="Grok Bot")),saved)
                assertEquals(expected,AppJson.parseToJsonElement(raw(reopened)))
                assertEquals(original.fullAccessPreviousGrant,saved?.fullAccessPreviousGrant)
            }
        } finally {context.deleteDatabase(file)}
    }

    @Test fun staleInvalidMissingAndFailedRenamesNeverReplaceTheSavedAgent() {
        val file="agent-rename-rejection-${UUID.randomUUID()}.db"
        try {SIConnectDb(context,file).use {db->
            val store=AgentStore(db);val original=fixture();store.put(original)
            store.rename(id,"Saved new name",original.grant.label)
            val before=raw(db);val beforeIndex=indexed(db)
            rejected {store.rename(id,"Stale owner name",original.grant.label)}
            listOf("", " ","x".repeat(61),"Two\nlines","Control\u0000name").forEach {label->rejected {store.rename(id,label,"Saved new name")}}
            rejected {store.rename("missing-agent","Valid name","Missing")}
            rejected {store.rename("local-owner","Changed owner","Device owner")}
            assertEquals(before,raw(db));assertEquals(beforeIndex,indexed(db))
            db.writableDatabase.execSQL("""CREATE TRIGGER reject_fixture_rename BEFORE UPDATE OF json ON agents
                WHEN NEW.agent_id='development-client' BEGIN SELECT RAISE(ABORT,'isolated write rejection'); END""")
            try {store.rename(id,"Rejected write","Saved new name");fail("SQLite must reject the write")}
            catch(_:SQLiteException) {}
            assertEquals(before,raw(db));assertEquals(beforeIndex,indexed(db))
        }} finally {context.deleteDatabase(file)}
    }

    @Test fun concurrentActivityTouchesCannotUndoOwnerRenamesOrPermissionProfiles() {
        val file="agent-rename-concurrent-${UUID.randomUUID()}.db"
        val pool=Executors.newSingleThreadExecutor()
        try {SIConnectDb(context,file).use {db->
            val store=AgentStore(db);val original=fixture();store.put(original)
            val extended=JsonObject(AppJson.parseToJsonElement(raw(db)).jsonObject.toMutableMap().apply {put("futureActivityMetadata",JsonPrimitive("preserve during touches"))})
            db.writableDatabase.update("agents",ContentValues().apply {put("json",extended.toString())},"agent_id=?",arrayOf(id))
            SIConnectDb(context,file).use {activityDb->
                val activityStore=AgentStore(activityDb);val start=CountDownLatch(1)
                val activity=pool.submit {start.await();repeat(120) {activityStore.touch(id,2000L+it)}}
                start.countDown()
                var previous=original.grant.label
                repeat(40) {index->
                    val next="Owner name $index"
                    store.rename(id,next,previous);previous=next
                }
                activity.get(30,TimeUnit.SECONDS)
                val saved=store.get(id)
                assertEquals(original.copy(grant=original.grant.copy(label="Owner name 39"),lastSeenAtMs=2119),saved)
                assertEquals(saved,store.byCredentialHash(original.tokenSha256))
                assertEquals("2119",indexed(db).last())
                assertEquals(original.fullAccessPreviousGrant,saved?.fullAccessPreviousGrant)
                val expected=JsonObject(renamed(extended,"Owner name 39").toMutableMap().apply {put("lastSeenAtMs",JsonPrimitive(2119))})
                assertEquals(expected,AppJson.parseToJsonElement(raw(db)))
            }
        }} finally {pool.shutdownNow();context.deleteDatabase(file)}
    }
}
