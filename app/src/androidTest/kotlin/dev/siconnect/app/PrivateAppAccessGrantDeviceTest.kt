package dev.siconnect.app

import android.content.pm.PackageManager
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.capabilities.HeadlessRules
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.app.data.Settings
import dev.siconnect.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual app SQLite and grant transactions, without launches or remote requests. Trusted owner API coverage, not biometric UI acceptance. */
@RunWith(AndroidJUnit4::class)
class PrivateAppAccessGrantDeviceTest {
    private val privateActions=setOf("headless.open","headless.click","headless.set_text","headless.scroll","headless.close")

    private suspend fun withDisposableAgent(block:(RuntimeGraph,AgentRecord,String)->Unit) {
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph
        g.ready.await()
        // Remote reads may advance last-seen timestamps during acceptance. All existing
        // identities, credentials and owner-selected grants must remain exactly unchanged.
        fun originalRecords()=g.agents.all().associate {it.grant.agentId to it.copy(lastSeenAtMs=null)}
        val before=originalRecords()
        val id="private-grant-test-${UUID.randomUUID()}"
        val existingStanding=StandingGrant("device.status",maxRisk=RiskClass.READ_ONLY,backend=BackendKind.NATIVE,note="Disposable pre-existing allowance")
        val record=AgentRecord(AgentGrant(id,"Disposable private-app grant acceptance",scopes=listOf("device.status"),
            denied=listOf("headless.*","headless.open","ui.*"),standing=listOf(existingStanding),
            autoApproveUpTo=RiskClass.READ_ONLY,allowSharedScreen=false,expiresAtMs=System.currentTimeMillis()+120_000),
            Settings.sha256Hex(Settings.randomToken("fixture_")),System.currentTimeMillis(),tokenHint="device test only")
        val pm=g.context.packageManager
        val installed=if(android.os.Build.VERSION.SDK_INT>=33) pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)) else pm.getInstalledApplications(0)
        val app=installed.firstOrNull {app->
            runCatching {HeadlessRules.validatePackage(app.packageName)}.isSuccess && pm.getLaunchIntentForPackage(app.packageName)!=null
        }
        assertNotNull("A real launchable, unprotected app is required",app)
        try {
            g.agents.put(record)
            assertEquals(record,g.agents.get(id))
            block(g,record,requireNotNull(app).packageName)
        } finally {
            // Only this unique agent's acceptance records are removed, even on failure.
            val db=g.db.writableDatabase
            db.beginTransaction()
            try {
                db.delete("events","agent_id=?",arrayOf(id))
                g.agents.delete(id)
                db.setTransactionSuccessful()
            } finally {db.endTransaction()}
            assertNull("Disposable agent cleanup failed",g.agents.get(id))
            assertEquals("Original identities, credentials or grants changed",before,originalRecords())
        }
    }

    private fun auditTypes(g:RuntimeGraph,id:String)=g.db.readableDatabase.rawQuery(
        "SELECT event_type FROM events WHERE agent_id=? ORDER BY seq",arrayOf(id)).use {cursor->
        buildList {while(cursor.moveToNext()) add(cursor.getString(0))}
    }

    private fun temporaryTriggerCount(db:SQLiteDatabase,name:String):Long {
        // WAL read connections have their own TEMP schema. Bind this brief inspection
        // to the writer connection, independently of the production method transaction.
        db.beginTransaction()
        try {
            val count=DatabaseUtils.longForQuery(db,"SELECT count(*) FROM sqlite_temp_master WHERE type='trigger' AND name=?",arrayOf(name))
            db.setTransactionSuccessful()
            return count
        } finally {db.endTransaction()}
    }

    @Test fun oneOwnerDecisionPersistsFamilyAndExactlyFiveAppBoundActions()=runBlocking {
        withDisposableAgent {g,before,pkg->
            val selected=g.privileged.kind
            g.enablePrivateAppAccessFromOwner(before.grant.agentId,pkg,selected)
            val saved=requireNotNull(g.agents.get(before.grant.agentId))
            val privateStanding=saved.grant.standing.filter {it.capabilityId in privateActions}
            assertTrue("Private capability family was not enabled","headless.*" in saved.grant.scopes)
            assertTrue("Unrelated scope was removed","device.status" in saved.grant.scopes)
            assertEquals("Only private-family deny rules may be removed",listOf("ui.*"),saved.grant.denied)
            assertEquals(privateActions,privateStanding.map {it.capabilityId}.toSet())
            assertEquals(5,privateStanding.size)
            privateStanding.forEach {grant->
                assertEquals(mapOf("packageName" to JsonPrimitive(pkg)),grant.constraints)
                assertEquals(selected,grant.backend)
                assertEquals(RiskClass.HIGH_IMPACT,grant.maxRisk)
                assertNull("App access must not become an exact-action bypass",grant.actionHash)
            }
            assertTrue(saved.grant.standing.containsAll(before.grant.standing))
            assertEquals(before.grant.autoApproveUpTo,saved.grant.autoApproveUpTo)
            assertEquals(before.grant.allowSharedScreen,saved.grant.allowSharedScreen)
            assertEquals(before.grant.shellMode,saved.grant.shellMode)
            assertEquals(before.tokenSha256,saved.tokenSha256)
            assertEquals(listOf("grant.family.changed","grant.private_app.changed"),auditTypes(g,before.grant.agentId))
            // Repeating the same decision must retain one allowance per action and package.
            g.enablePrivateAppAccessFromOwner(before.grant.agentId,pkg,selected)
            assertEquals(saved,g.agents.get(before.grant.agentId))
        }
    }

    @Test fun protectedMalformedAndChangedBackendTargetsLeaveGrantAndAuditUnchanged()=runBlocking {
        withDisposableAgent {g,before,pkg->
            val selected=g.privileged.kind
            for(target in listOf("com.android.settings","dev.siconnect.app","invalid package")) {
                try {
                    g.enablePrivateAppAccessFromOwner(before.grant.agentId,target,selected)
                    fail("Protected or malformed target was accepted: $target")
                } catch(_:IllegalArgumentException) { /* Real validation must reject before mutation. */ }
                assertEquals(before,g.agents.get(before.grant.agentId))
                assertTrue(auditTypes(g,before.grant.agentId).isEmpty())
            }
            val stale=if(selected==BackendKind.ADB_SERVICE) BackendKind.SHIZUKU else BackendKind.ADB_SERVICE
            try {
                g.enablePrivateAppAccessFromOwner(before.grant.agentId,pkg,stale)
                fail("A backend changed after review must not be accepted")
            } catch(_:IllegalArgumentException) { }
            assertEquals(before,g.agents.get(before.grant.agentId))
            assertTrue(auditTypes(g,before.grant.agentId).isEmpty())
        }
    }

    @Test fun secondGrantWriteFailureRollsBackFamilyPackageAndBothAuditEvents()=runBlocking {
        withDisposableAgent {g,before,pkg->
            val db=g.db.writableDatabase
            val trigger="private_grant_failure_"+UUID.randomUUID().toString().replace("-","")
            // AgentStore uses INSERT OR REPLACE. This real SQLite trigger allows the
            // family-only write, then aborts only this agent's app-standing write.
            // No unrelated row can match the unique agent identity.
            db.execSQL("""CREATE TEMP TRIGGER $trigger BEFORE INSERT ON main.agents
                WHEN NEW.agent_id=${DatabaseUtils.sqlEscapeString(before.grant.agentId)}
                  AND instr(NEW.json,'"capabilityId":"headless.open"')>0
                BEGIN SELECT RAISE(ABORT,'disposable private-app second-write failure'); END""")
            try {
                assertEquals(1L,temporaryTriggerCount(db,trigger))
                try {
                    g.enablePrivateAppAccessFromOwner(before.grant.agentId,pkg,g.privileged.kind)
                    fail("The injected second grant write failure was swallowed")
                } catch(e:Exception) {
                    // Android versions differ: some propagate the real trigger exception;
                    // others return -1 and AgentStore's persistence guard throws instead.
                    assertTrue("Unexpected failure instead of the injected SQLite rejection: $e",
                        (e is SQLiteConstraintException && e.message?.contains("disposable private-app second-write failure")==true) ||
                        (e is IllegalStateException && e.message=="Agent permission record could not be saved"))
                }
                assertEquals("Family and app allowance must roll back together",before,g.agents.get(before.grant.agentId))
                assertTrue("The nested family audit must roll back with the failed allowance",auditTypes(g,before.grant.agentId).isEmpty())
            } finally {db.execSQL("DROP TRIGGER IF EXISTS temp.$trigger")}
            assertEquals(0L,temporaryTriggerCount(db,trigger))
        }
    }
}
