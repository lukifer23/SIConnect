package dev.siconnect.app

import android.content.Intent
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.provider.AlarmClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.accessibility.SemanticUiRules
import dev.siconnect.app.capabilities.HeadlessRules
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.app.data.AgentStore
import dev.siconnect.app.data.Settings
import dev.siconnect.app.data.SIConnectDb
import dev.siconnect.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real SQLite and registered-capability policy checks. No Clock launch, node action, or shell execution. */
@RunWith(AndroidJUnit4::class)
class FullAccessProfileDeviceTest {
    private suspend fun withAgent(block:(RuntimeGraph,AgentRecord,String)->Unit) {
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph
        g.ready.await()
        fun protectedRecords()=g.agents.all().associate {it.grant.agentId to it.copy(lastSeenAtMs=null)}
        val protectedBefore=protectedRecords()
        val id="full-profile-test-${UUID.randomUUID()}"
        val record=AgentRecord(AgentGrant(id,"Disposable full-profile acceptance",scopes=listOf("device.status"),
            denied=listOf("apps.launch","headless.*","ui.*"),allowSharedScreen=false,
            shellMode=ShellMode.STANDING_COMMANDS,shellStandingCommands=listOf("id"),autoApproveUpTo=RiskClass.READ_ONLY,
            standing=listOf(StandingGrant("device.status",backend=BackendKind.NATIVE,maxRisk=RiskClass.READ_ONLY,note="Original custom permission")),
            expiresAtMs=System.currentTimeMillis()+120_000),Settings.sha256Hex(Settings.randomToken("fixture_")),
            System.currentTimeMillis(),tokenHint="device test only")
        val pm=g.context.packageManager
        val clockCandidates=listOfNotNull(
            pm.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS),0)?.activityInfo?.packageName,
            "com.sec.android.app.clockpackage","com.google.android.deskclock","com.android.deskclock")
        val clock=clockCandidates.firstOrNull {pkg->runCatching {pm.getApplicationInfo(pkg,0);HeadlessRules.validatePackage(pkg)}.isSuccess}
        org.junit.Assume.assumeTrue("An installed Clock app is required",clock!=null)
        try {
            g.agents.put(record)
            block(g,record,requireNotNull(clock))
        } finally {
            val db=g.db.writableDatabase
            db.beginTransaction()
            try {
                db.delete("events","agent_id=?",arrayOf(id))
                g.agents.delete(id)
                db.setTransactionSuccessful()
            } finally {db.endTransaction()}
            assertNull(g.agents.get(id))
            assertEquals("An original identity, credential or permission changed",protectedBefore,protectedRecords())
        }
    }

    private fun audits(g:RuntimeGraph,id:String)=g.db.readableDatabase.rawQuery(
        "SELECT event_type FROM events WHERE agent_id=? ORDER BY seq",arrayOf(id)).use {cursor->
        buildList {while(cursor.moveToNext()) add(cursor.getString(0))}
    }

    private fun reloaded(g:RuntimeGraph,id:String):AgentRecord=SIConnectDb(g.context).use {db->
        requireNotNull(AgentStore(db).get(id))
    }

    private fun policy(g:RuntimeGraph,grant:AgentGrant,id:String,args:JsonObject,controls:DeviceControls=DeviceControls(),
        availability:Availability=Availability.IMPLEMENTED,classifiedRisk:RiskClass?=null):PolicyDecision {
        val capability=requireNotNull(g.registry.get(id)) {"Real capability is not registered: $id"}
        val assessment=capability.assess(args).let {if(classifiedRisk==null) it else it.copy(risk=classifiedRisk)}
        // Availability is an explicit policy input: these checks isolate authorization
        // when Android reports an executable backend, without dispatching any operation.
        return PolicyEngine().evaluate(grant,capability.descriptor,args,assessment,availability,controls,Origin.REMOTE,System.currentTimeMillis())
    }

    @Test fun fullProfilePersistsAndAuthorizesClockShellScreenAndPrivateWorkWithoutPerToolPrompts()=runBlocking {
        withAgent {g,before,clock->
            val id=before.grant.agentId
            g.setFullAccessFromOwner(id,true)
            val saved=reloaded(g,id)
            assertEquals(before.grant,saved.fullAccessPreviousGrant)
            assertEquals(listOf("*"),saved.grant.scopes)
            assertTrue(saved.grant.denied.isEmpty())
            assertEquals(RiskClass.CRITICAL,saved.grant.autoApproveUpTo)
            assertTrue(saved.grant.fullControl)
            assertNull("Full control must persist without a configured grant expiry",saved.grant.expiresAtMs)
            assertEquals(ShellMode.BROAD,saved.grant.shellMode)
            assertTrue(saved.grant.allowSharedScreen)
            assertEquals(before.grant.standing,saved.grant.standing)
            assertEquals(before.tokenSha256,saved.tokenSha256)
            val packageArgs=buildJsonObject {put("packageName",clock)}
            val cases=mapOf(
                "apps.launch" to buildJsonObject {put("package",clock)},
                "shell.execute" to buildJsonObject {put("command","id")},
                // Real assessments consume the package binding. Fresh-node references
                // are separately required at dispatch; this test invents no node handles.
                "ui.click" to packageArgs,
                "headless.open" to packageArgs)
            for((capability,args) in cases) {
                val result=policy(g,saved.grant,capability,args)
                assertEquals("$capability: ${result.rule}: ${result.reason}",PolicyOutcome.ALLOW,result.outcome)
            }
            assertEquals(g.privileged.kind,g.registry.get("headless.open")!!.descriptor.backend)
            assertEquals(g.privileged.kind,g.registry.get("shell.execute")!!.descriptor.backend)
            g.setFullAccessFromOwner(id,true)
            assertEquals("Repeated enable must not replace the original custom snapshot",saved,reloaded(g,id))
            assertEquals(listOf("grant.full_access.allowed"),audits(g,id))
            g.setFullAccessFromOwner(id,false)
            assertEquals("Disabling must restore the exact original custom grant",before,reloaded(g,id))
            g.setFullAccessFromOwner(id,false)
            assertEquals(before,reloaded(g,id))
            assertEquals(listOf("grant.full_access.allowed","grant.full_access.restored"),audits(g,id))
            // A newly authenticated Full decision deliberately replaces a prior custom
            // expiry. Disabling Full still restores that saved custom restriction.
            val expiredCustom=before.copy(grant=before.grant.copy(expiresAtMs=System.currentTimeMillis()-1))
            g.agents.put(expiredCustom)
            g.setFullAccessFromOwner(id,true)
            val reenabled=reloaded(g,id)
            assertTrue(reenabled.grant.fullControl)
            assertNull(reenabled.grant.expiresAtMs)
            assertEquals(expiredCustom.grant,reenabled.fullAccessPreviousGrant)
            g.setFullAccessFromOwner(id,false)
            assertEquals(expiredCustom,reloaded(g,id))
        }
    }

    @Test fun explicitFullProfileCoversClassifiedCriticalWhileKeepingOwnerOnlyAndAndroidBoundaries()=runBlocking {
        withAgent {g,before,clock->
            val id=before.grant.agentId
            g.setFullAccessFromOwner(id,true)
            val full=requireNotNull(g.agents.get(id))
            val shellArgs=buildJsonObject {put("command","id")}
            // A CUSTOM grant still requires classified-CRITICAL approval even with
            // broad scopes and a high auto-approve level. Only explicit fullControl
            // covers it automatically. No dangerous command is classified or executed.
            val customCritical=policy(g,full.grant.copy(fullControl=false),"shell.execute",shellArgs,classifiedRisk=RiskClass.CRITICAL)
            assertEquals(PolicyOutcome.REQUIRE_APPROVAL,customCritical.outcome)
            assertEquals("critical",customCritical.rule)
            val fullCritical=policy(g,full.grant,"shell.execute",shellArgs,classifiedRisk=RiskClass.CRITICAL)
            assertEquals(PolicyOutcome.ALLOW,fullCritical.outcome)
            val ownerOnly=policy(g,full.grant,"headless.stop_all",buildJsonObject {})
            assertEquals(PolicyOutcome.DENY,ownerOnly.outcome)
            assertEquals("owner_only",ownerOnly.rule)
            val args=buildJsonObject {put("packageName",clock)}
            assertEquals("emergency_stop",policy(g,full.grant,"headless.open",args,controls=DeviceControls(remoteExecutionEnabled=false)).rule)
            assertEquals("privileged_disabled",policy(g,full.grant,"headless.open",args,controls=DeviceControls(privilegedEnabled=false)).rule)
            val unavailable=policy(g,full.grant,"headless.open",args,availability=Availability.PRIVILEGE_REQUIRED)
            assertEquals(PolicyOutcome.UNAVAILABLE,unavailable.outcome)
            assertEquals("availability",unavailable.rule)
            for(pkg in listOf("com.android.settings","com.android.systemui","dev.siconnect.app")) {
                try {HeadlessRules.validatePackage(pkg);fail("Protected private target admitted: $pkg")}
                catch(_:IllegalArgumentException) { }
            }
            assertTrue(SemanticUiRules.protectedPackage("com.android.systemui"))
            assertTrue(SemanticUiRules.protectedPackage("com.android.permissioncontroller"))
            val customEdits=listOf<()->Unit>(
                {g.setShellPermissionFromOwner(id,false)},
                {g.setCapabilityFamilyFromOwner(id,"headless",false)},
                {g.setSemanticPackagePermissionFromOwner(id,clock,false)},
                {g.setHeadlessPackagePermissionFromOwner(id,clock,false,g.privileged.kind)},
                {g.setSharedScreenPermissionFromOwner(id,false)})
            for(edit in customEdits) {
                try {edit();fail("A custom edit silently changed full control")}
                catch(e:IllegalArgumentException) {assertTrue(e.message.orEmpty().contains("Switch to custom access"))}
                assertEquals(full,reloaded(g,id))
                assertEquals(listOf("grant.full_access.allowed"),audits(g,id))
            }
            g.enablePrivateAppAccessFromOwner(id,clock,g.privileged.kind)
            assertEquals("Contextual repair must preserve an existing full profile and original snapshot",full,reloaded(g,id))
            assertEquals(listOf("grant.full_access.allowed"),audits(g,id))
        }
    }

    @Test fun disablingCannotUndoLaterRevocationExpiryOrIdentityMetadata()=runBlocking {
        withAgent {g,before,_->
            val id=before.grant.agentId
            g.setFullAccessFromOwner(id,true)
            g.revokeAgentFromOwner(id)
            val revoked=requireNotNull(g.agents.get(id))
            val expiredAt=System.currentTimeMillis()-1
            val current=revoked.copy(grant=revoked.grant.copy(label="Renamed disposable identity",expiresAtMs=expiredAt))
            g.agents.put(current)
            g.setFullAccessFromOwner(id,false)
            val restored=reloaded(g,id)
            assertNull(restored.fullAccessPreviousGrant)
            assertFalse(restored.grant.fullControl)
            assertEquals(before.grant.copy(label=current.grant.label,revoked=true,expiresAtMs=expiredAt),restored.grant)
            assertEquals(before.tokenSha256,restored.tokenSha256)
            try {g.setFullAccessFromOwner(id,true);fail("Revoked identity re-enabled")}
            catch(_:IllegalArgumentException) { }
            assertEquals(restored,reloaded(g,id))
        }
    }

    private fun triggerCount(db:SQLiteDatabase,name:String):Long {
        db.beginTransaction()
        try {
            val count=DatabaseUtils.longForQuery(db,"SELECT count(*) FROM sqlite_temp_master WHERE type='trigger' AND name=?",arrayOf(name))
            db.setTransactionSuccessful();return count
        } finally {db.endTransaction()}
    }

    @Test fun auditWriteFailureRollsBackBothFullProfileTransitions()=runBlocking {
        withAgent {g,before,_->
            val db=g.db.writableDatabase
            val id=before.grant.agentId
            fun rejectAudit(event:String,action:()->Unit) {
                val trigger="full_profile_failure_"+UUID.randomUUID().toString().replace("-","")
                db.execSQL("""CREATE TEMP TRIGGER $trigger BEFORE INSERT ON main.events
                    WHEN NEW.agent_id=${DatabaseUtils.sqlEscapeString(id)} AND NEW.event_type=${DatabaseUtils.sqlEscapeString(event)}
                    BEGIN SELECT RAISE(ABORT,'disposable full-profile audit failure'); END""")
                try {
                    assertEquals(1L,triggerCount(db,trigger))
                    try {action();fail("Injected audit failure was swallowed")}
                    catch(e:SQLiteConstraintException) {assertTrue(e.message.orEmpty().contains("disposable full-profile audit failure"))}
                } finally {db.execSQL("DROP TRIGGER IF EXISTS temp.$trigger")}
                assertEquals(0L,triggerCount(db,trigger))
            }
            rejectAudit("grant.full_access.allowed") {g.setFullAccessFromOwner(id,true)}
            assertEquals(before,reloaded(g,id));assertTrue(audits(g,id).isEmpty())
            g.setFullAccessFromOwner(id,true)
            val enabled=reloaded(g,id)
            rejectAudit("grant.full_access.restored") {g.setFullAccessFromOwner(id,false)}
            assertEquals("Failed restore must retain full control and its original custom snapshot",enabled,reloaded(g,id))
            assertEquals(listOf("grant.full_access.allowed"),audits(g,id))
            g.setFullAccessFromOwner(id,false)
            assertEquals(before,reloaded(g,id))
            assertEquals(listOf("grant.full_access.allowed","grant.full_access.restored"),audits(g,id))
        }
    }
}
