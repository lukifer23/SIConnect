package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.siconnect.app.data.*
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import java.util.UUID

/** Uses the installed app, real SQLite, actual Android APIs and the actual privileged daemon. */
@RunWith(AndroidJUnit4::class)
class RuntimeDeviceTest {
    private val g=InstrumentationRegistry.getInstrumentation().targetContext.graph
    @Before fun ready()=runBlocking { g.ready.await() }
    private suspend fun authority() { withTimeout(10000) { while(!g.privileged.available()) delay(50) } }
    @Test fun nativeExecutionIsDurableAndIdempotencyIsBound()=runBlocking {
        val id="physical:${UUID.randomUUID()}"
        val req=InvocationRequest(requestId=id,agentId="local-owner",capabilityId="device.status",idempotencyKey=id)
        val record=g.engine.submit(req,Origin.LOCAL_OWNER)
        Assert.assertEquals(RequestState.SUCCEEDED,record.state)
        Assert.assertEquals(android.os.Build.MODEL,record.result!!.output.jsonObject["model"]!!.jsonPrimitive.content)
        val reopened=SIConnectDb(g.context)
        Assert.assertEquals(record,SqlRequestStore(reopened).get(id))
        val summary=SqlRequestStore(reopened).recentSummaries().single {it.requestId==id}
        Assert.assertEquals(record.agentId,summary.agentId)
        Assert.assertEquals(record.capabilityId,summary.capabilityId)
        Assert.assertEquals(record.state,summary.state)
        Assert.assertEquals(record.updatedAtMs,summary.updatedAtMs)
        reopened.close()
        Assert.assertEquals(record.requestId,g.engine.submit(req,Origin.LOCAL_OWNER).requestId)
        try { g.engine.submit(req.copy(capabilityId="device.battery"),Origin.LOCAL_OWNER); Assert.fail("collision accepted") }
        catch(e: CapabilityException) { Assert.assertEquals("IDEMPOTENCY_CONFLICT",e.code) }
        Assert.assertTrue(g.events.query(requestId=id).any { it.eventType=="execution.finished" })
    }
    /** Real singleTop intent reentry, using a resolved native record; no approval is created. */
    @Test fun ownerReviewCanReopenSameResolvedRequest()=runBlocking {
        val r=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="device.status"),Origin.LOCAL_OWNER)
        Assert.assertEquals(RequestState.SUCCEEDED,r.state)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun intent()=Intent(g.context,dev.siconnect.app.approvals.ApprovalActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("requestId",r.requestId)
        val automation=instrumentation.uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        // Compose virtual nodes are exposed through children, not framework find-by-text.
        fun node(text:String):AccessibilityNodeInfo? {
            val todo=java.util.ArrayDeque<AccessibilityNodeInfo>()
            automation.windows.mapNotNull {it.root}.forEach(todo::add)
            var visited=0
            while(todo.isNotEmpty() && visited++<5000) {
                val n=todo.removeFirst()
                if(n.text?.toString()==text && n.packageName?.toString()==g.context.packageName) return n
                for(i in 0 until n.childCount) n.getChild(i)?.let(todo::add)
            }
            return null
        }
        suspend fun waitFor(text:String,present:Boolean,phase:String) {
            try {withTimeout(10000) {while((node(text)!=null)!=present) delay(50)}}
            catch(e:TimeoutCancellationException) {
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(g.context.noBackupFilesDir,"review-test-failure.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                }
                val root=instrumentation.uiAutomation.rootInActiveWindow
                val matches=root?.findAccessibilityNodeInfosByText(text).orEmpty()
                throw AssertionError("Review stage $phase timed out; root=${root?.packageName}; matches=${matches.map {it.text?.toString()+"@"+it.packageName}}",e)
            }
        }
        ActivityScenario.launch<dev.siconnect.app.approvals.ApprovalActivity>(intent()).use { scenario ->
            waitFor("Action succeeded",true,"initial resolved review")
            var close=requireNotNull(node("Close"))
            while(!close.isClickable) close=requireNotNull(close.parent)
            Assert.assertTrue(close.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("Action succeeded",false,"explicit close")
            scenario.onActivity {it.startActivity(intent())}
            waitFor("Action succeeded",true,"same-request intent reentry")
            Assert.assertNull("Resolved review must not offer another approval",node("Allow once"))
        }
    }
    @Test fun pairingSameNamesRemainIndependentAndRenewPreservesIdentity()=runBlocking {
        Assume.assumeTrue("Do not disturb an owner's invitation",g.settings.pendingPairing()==null)
        Assume.assumeTrue("Owner has remote connectivity enabled",g.settings.gateway.value.enabled)
        g.context.startForegroundService(Intent(g.context,dev.siconnect.app.transport.ConnectionService::class.java))
        withTimeout(15000) {while(g.gateway.state.value!=dev.siconnect.app.transport.ConnectionState.ONLINE) delay(50)}
        val created=mutableListOf<String>()
        try {
            val first=g.pairing.create("Same-label regression fixture");created+=first.agentId
            val firstRecord=requireNotNull(g.agents.get(first.agentId))
            g.agents.put(firstRecord.copy(grant=firstRecord.grant.copy(scopes=listOf("device.*"))))
            val expired=first.copy(expiresAt=System.currentTimeMillis()-1)
            g.settings.setPendingPairing(expired)
            val renewed=g.pairing.renew(first.agentId)
            Assert.assertEquals(first.agentId,renewed.agentId)
            Assert.assertNotEquals(first.code,renewed.code)
            Assert.assertEquals(listOf("device.*"),g.agents.get(first.agentId)!!.grant.scopes)
            g.pairing.dismissInvitation(first.agentId)
            val second=g.pairing.create(first.label);created+=second.agentId
            Assert.assertNotEquals(first.agentId,second.agentId)
            Assert.assertNotEquals(g.agents.get(first.agentId)!!.tokenSha256,g.agents.get(second.agentId)!!.tokenSha256)
            Assert.assertNotEquals(listOf("device.*"),g.agents.get(second.agentId)!!.grant.scopes)
            g.pairing.dismissInvitation(second.agentId)
            val reconnected=g.pairing.reconnect(first.agentId)
            Assert.assertEquals(first.agentId,reconnected.agentId)
            Assert.assertEquals(listOf("device.*"),g.agents.get(first.agentId)!!.grant.scopes)
        } finally {
            val pending=g.settings.pendingPairing()
            if(pending?.agentId in created) g.settings.setPendingPairing(null)
            created.forEach {id -> g.agents.delete(id);g.context.getSharedPreferences("siconnect_secrets",0).edit().remove("paired_"+id).commit()}
        }
    }
    @Test fun reminderReconcilesInterruptedClaimWithoutDuplicatePost()=runBlocking {
        val id="physical-reconcile:"+UUID.randomUUID()
        val row=dev.siconnect.app.capabilities.Reminder(id,System.currentTimeMillis()-100,"SIConnect reconciliation test","Disposable delivery fixture","local-owner",state="FIRING")
        JsonTable(g.db,"reminders","reminder_id").upsert(android.content.ContentValues().apply {
            put("reminder_id",id);put("fire_at",row.fireAtMs);put("state",row.state);put("created_by",row.agentId);put("revision",0)
            put("json",AppJson.encodeToString(dev.siconnect.app.capabilities.Reminder.serializer(),row))
        })
        try {
            Assert.assertEquals(RequestState.SUCCEEDED,g.reminders.deliver(id))
            val record=requireNotNull(g.requests.get("reminder:$id"))
            Assert.assertEquals(RequestState.SUCCEEDED,g.reminders.deliver(id))
            Assert.assertEquals(record,g.requests.get("reminder:$id"))
            Assert.assertEquals("SUCCEEDED",g.reminders.get(id)!!.state)
        } finally {
            g.context.getSystemService(android.app.NotificationManager::class.java).cancel("reminder:$id".hashCode())
            g.db.writableDatabase.delete("reminders","reminder_id=?",arrayOf(id))
        }
    }
    @Test fun managedFilesRoundTripAndTraversalBoundary()=runBlocking {
        val agent="file-fixture:"+UUID.randomUUID();val token=Settings.randomToken("test_")
        val folder="fixture-"+UUID.randomUUID()
        g.agents.put(AgentRecord(AgentGrant(agent,"Disposable file fixture",scopes=listOf("files.*"),autoApproveUpTo=RiskClass.MODERATE_IMPACT),Settings.sha256Hex(token),System.currentTimeMillis(),tokenHint="test only"))
        suspend fun call(cap:String,args:JsonObject):RequestRecord=g.remoteInvoke(buildJsonObject {put("capabilityId",cap);put("arguments",args)},token)
        fun path(value:String)=buildJsonObject {put("path",value)}
        try {
            Assert.assertEquals(RequestState.SUCCEEDED,call("files.mkdir",path(folder)).state)
            val written=call("files.write",buildJsonObject {put("path","$folder/original.txt");put("content","SIConnect fixture")})
            Assert.assertEquals(RequestState.SUCCEEDED,written.state)
            val read=call("files.read",buildJsonObject {put("path","$folder/original.txt");put("encoding","utf8")})
            Assert.assertEquals("SIConnect fixture",read.result!!.output.jsonObject.getValue("content").jsonPrimitive.content)
            Assert.assertEquals(written.result!!.output.jsonObject["sha256"],call("files.hash",path("$folder/original.txt")).result!!.output.jsonObject["sha256"])
            Assert.assertEquals(RequestState.SUCCEEDED,call("files.copy",buildJsonObject {put("path","$folder/original.txt");put("destination","$folder/copy.txt")}).state)
            Assert.assertEquals(RequestState.SUCCEEDED,call("files.move",buildJsonObject {put("path","$folder/copy.txt");put("destination","$folder/renamed.txt")}).state)
            val listing=call("files.list",path(folder));Assert.assertEquals(listing.errorMessage,RequestState.SUCCEEDED,listing.state)
            Assert.assertEquals(2,listing.result!!.output.jsonObject.getValue("entries").jsonArray.size)
            Assert.assertEquals("INVALID_PATH",call("files.read",path("../siconnect.db")).errorCode)
            Assert.assertEquals(RequestState.SUCCEEDED,call("files.delete",path("$folder/original.txt")).state)
            Assert.assertEquals(RequestState.SUCCEEDED,call("files.delete",path("$folder/renamed.txt")).state)
            Assert.assertEquals(RequestState.SUCCEEDED,call("files.delete",path(folder)).state)
        } finally {
            // Cleanup is confined to this test's unique app-owned disposable directory.
            java.io.File(g.context.filesDir,"managed/$folder").deleteRecursively();g.agents.delete(agent)
        }
    }
    @Test fun executionStateRollsBackIfItsAuditInsertFails()=runBlocking {
        val id="audit-fixture:"+UUID.randomUUID()
        val trigger="siconnect_test_audit_failure"
        val w=g.db.writableDatabase
        // SQLite-backed fault injection is test-only and scoped to this request ID.
        w.execSQL("CREATE TEMP TRIGGER $trigger BEFORE INSERT ON events WHEN NEW.request_id='$id' AND NEW.event_type='execution.started' BEGIN SELECT RAISE(ABORT,'test audit insert failure'); END")
        try {
            try {g.engine.submit(InvocationRequest(requestId=id,agentId="local-owner",capabilityId="device.status"),Origin.LOCAL_OWNER);Assert.fail("Audit failure must prevent execution")}
            catch(_:android.database.sqlite.SQLiteException) {}
            Assert.assertEquals(RequestState.VALIDATED,g.requests.get(id)!!.state)
            Assert.assertFalse(g.events.query(requestId=id).any {it.eventType=="execution.started" || it.eventType=="execution.finished"})
        } finally {w.execSQL("DROP TRIGGER IF EXISTS $trigger");g.engine.cancel(id,"fixture cleanup")}
    }
    @Test fun historyKeysetPaginationHandlesEqualTimestampsAndFilters()=runBlocking {
        val agent="history-fixture:"+UUID.randomUUID();val now=System.currentTimeMillis()
        val ids=(1..5).map {"$agent:$it"}
        try {
            ids.forEach {id -> Assert.assertTrue(g.requests.insert(RequestRecord(id,id,agent,g.settings.deviceId,"device.status",arguments=JsonObject(emptyMap()),argsHash="fixture",origin=Origin.REMOTE.name,state=RequestState.SUCCEEDED,createdAtMs=now,updatedAtMs=now,expiresAtMs=now+60000)))}
            val first=g.requests.historyPage(2,agentId=agent)
            val second=g.requests.historyPage(2,first.last(),agentId=agent)
            val third=g.requests.historyPage(2,second.last(),agentId=agent)
            Assert.assertEquals(ids.sortedDescending(),(first+second+third).map {it.requestId})
            Assert.assertTrue(g.requests.historyPage(agentId=agent,states=setOf(RequestState.FAILED)).isEmpty())
            Assert.assertEquals(ids.sorted(),g.requests.historyPage(agentId=agent,newest=false).map {it.requestId})
        } finally {
            ids.forEach {id->g.db.writableDatabase.delete("request_transitions","request_id=?",arrayOf(id));g.db.writableDatabase.delete("requests","request_id=?",arrayOf(id))}
        }
    }
    @Test fun structuredPackageInspectionUsesRealPackageManager()=runBlocking {
        suspend fun inspect(name:String)=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="apps.inspect_detailed",arguments=buildJsonObject {put("packageName",name);put("user",0)}),Origin.LOCAL_OWNER)
        for(name in listOf(g.context.packageName,"android")) {
            val r=inspect(name)
            Assert.assertEquals(r.errorMessage,RequestState.SUCCEEDED,r.state)
            Assert.assertEquals(VerificationStatus.VERIFIED,r.result!!.verification)
            val out=r.result!!.output.jsonObject
            Assert.assertTrue(out.getValue("installed").jsonPrimitive.boolean)
            Assert.assertEquals(name,out.getValue("packageName").jsonPrimitive.content)
            Assert.assertEquals(0,out.getValue("user").jsonPrimitive.int)
            Assert.assertTrue(out.getValue("versionCode").jsonPrimitive.long>=0)
        }
        val missing=inspect("dev.siconnect.missing.p"+UUID.randomUUID().toString().replace("-",""))
        Assert.assertEquals(RequestState.SUCCEEDED,missing.state)
        Assert.assertFalse(missing.result!!.output.jsonObject.getValue("installed").jsonPrimitive.boolean)
        val invalid=inspect("invalid;id")
        Assert.assertEquals(RequestState.FAILED,invalid.state)
        Assert.assertEquals("INVALID_PACKAGE",invalid.errorCode)
    }
    @Test fun shellSupervisorBoundsTimeoutOutputAndOwnedCancellation()=runBlocking {
        authority()
        suspend fun command(text:String,timeout:Long=3000,limit:Int=8192)=g.privileged.execute(dev.siconnect.app.backend.ShellRequest("shell-fixture:"+UUID.randomUUID(),command=text,timeoutMs=timeout,maxOutputBytes=limit))
        val normal=command("printf 'hello'; printf 'error' >&2; exit 7")
        Assert.assertEquals(7,normal.exitCode);Assert.assertEquals("hello",normal.stdout);Assert.assertEquals("error",normal.stderr)
        val input=g.privileged.execute(dev.siconnect.app.backend.ShellRequest("shell-fixture:"+UUID.randomUUID(),command="cat; printf '%s' \"${'$'}SICONNECT_TEST\"",stdin="input",env=mapOf("SICONNECT_TEST" to "environment"),cwd="/data/local/tmp"))
        Assert.assertEquals("inputenvironment",input.stdout)
        val large=command("yes abc | head -c 20000")
        Assert.assertTrue(large.stdoutTruncated);Assert.assertEquals(8192,large.stdout.toByteArray().size)
        val binary=command("printf '\\000\\377'")
        Assert.assertEquals("AP8=",binary.stdoutBase64)
        val timeout=command("trap '' TERM; sleep 10",200)
        Assert.assertTrue(timeout.timedOut);Assert.assertTrue(timeout.durationMs<2500)
        val early="shell-fixture:"+UUID.randomUUID()
        Assert.assertTrue(dev.siconnect.app.privileged.DirectAuthority.service!!.cancel(early))
        val cancelledBeforeStart=g.privileged.execute(dev.siconnect.app.backend.ShellRequest(early,command="printf must-not-run"))
        Assert.assertTrue(cancelledBeforeStart.cancelled);Assert.assertEquals("",cancelledBeforeStart.stdout)
        val path="/data/local/tmp/siconnect-shell-"+UUID.randomUUID()+".pids"
        try {
            val job=async {command("sleep 20 & child=${'$'}!; printf '%s %s' ${'$'}${'$'} ${'$'}child > $path; wait",30000)}
            withTimeout(5000) {while(command("test -s $path").exitCode!=0) delay(30)}
            job.cancelAndJoin()
            val check=command("read parent child < $path; kill -0 \"${'$'}parent\" 2>/dev/null && exit 1; kill -0 \"${'$'}child\" 2>/dev/null && exit 2; exit 0")
            Assert.assertEquals(check.stdout+check.stderr,0,check.exitCode)
        } finally {command("rm -f $path")}
    }
    @Test fun bundledBackendActuallyRunsAsShell()=runBlocking {
        authority()
        g.settings.privilegeBackend="direct"; g.privileged.refresh()
        Assert.assertTrue(g.privileged.detail(),g.privileged.available())
        val r=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="shell.identity"),Origin.LOCAL_OWNER)
        Assert.assertEquals(RequestState.SUCCEEDED,r.state)
        Assert.assertTrue(r.result!!.identity!!.startsWith("uid=2000(shell)"))
        Assert.assertEquals(BackendKind.ADB_SERVICE,r.result!!.backend)
        Assert.assertEquals(0,r.result!!.output.jsonObject.getValue("exitCode").jsonPrimitive.int)
    }
    @Test fun actualShellApprovalDenyApproveAndRevocation()=runBlocking {
        authority()
        val id="physical:${UUID.randomUUID()}"
        val token=Settings.randomToken("test_")
        val testAgent="physical-test-${UUID.randomUUID()}"
        g.agents.put(AgentRecord(AgentGrant(testAgent,"Device test only",scopes=listOf("shell.*")),Settings.sha256Hex(token),System.currentTimeMillis(),tokenHint="test only"))
        fun body(requestId: String)=buildJsonObject { put("requestId",requestId); put("capabilityId","shell.execute"); putJsonObject("arguments") { put("command","id") } }
        val denied=g.remoteInvoke(body("$id:deny"),token)
        Assert.assertEquals(RequestState.AWAITING_APPROVAL,denied.state)
        Assert.assertEquals(RequestState.DENIED,g.engine.resolveApproval(denied.requestId,false,"instrumented-local-owner",denied.argsHash).state)
        val approved=g.remoteInvoke(body("$id:approve"),token)
        val executed=g.engine.resolveApproval(approved.requestId,true,"instrumented-local-owner",approved.argsHash)
        Assert.assertEquals(RequestState.SUCCEEDED,executed.state)
        Assert.assertTrue(executed.result!!.identity!!.startsWith("uid=2000(shell)"))
        val revoked=g.remoteInvoke(body("$id:revoked"),token)
        val agent=g.agents.get(testAgent)!!
        g.agents.put(agent.copy(grant=agent.grant.copy(revoked=true)))
        Assert.assertEquals(RequestState.FAILED,g.engine.resolveApproval(revoked.requestId,true,"instrumented-local-owner",revoked.argsHash).state)
        Assert.assertNull(g.requests.get(revoked.requestId)!!.result)
        // Never rotate or revoke the owner's actual paired bot while running a device test.
        g.agents.delete(testAgent)
        Unit
    }
    @Test fun exactReminderActuallyDeliversThroughEngine()=runBlocking {
        Assert.assertTrue("Enable exact alarm access before this physical test",g.reminders.canScheduleExact())
        val id="physical-reminder:${UUID.randomUUID()}"
        val nm=g.context.getSystemService(android.app.NotificationManager::class.java)
        try {
        val r=g.engine.submit(InvocationRequest(requestId=id,agentId="local-owner",capabilityId="reminders.create",arguments=buildJsonObject {
            put("title","SIConnect timing test");put("text","Actual exact reminder delivery verified");put("fireAtMs",System.currentTimeMillis()+3000);put("exact",true)
        }),Origin.LOCAL_OWNER)
        Assert.assertEquals(RequestState.SUCCEEDED,r.state)
        withTimeout(20000) {while(g.requests.get("reminder:$id")?.state!=RequestState.SUCCEEDED) delay(100)}
        Assert.assertEquals("SUCCEEDED",g.reminders.get(id)!!.state)
        Assert.assertTrue(nm.activeNotifications.any {it.id=="reminder:$id".hashCode() && it.notification.extras.getString(android.app.Notification.EXTRA_TITLE)=="SIConnect timing test"})
        } finally {
        nm.cancel("reminder:$id".hashCode())
        g.reminders.cancel(id)
        g.db.writableDatabase.delete("reminders","reminder_id=?",arrayOf(id))
        }
    }
    @Test fun durableNativeSkillAndPackageQuery()=runBlocking {
        val def=g.skills.get("diagnostics.snapshot")!!
        val id="physical-skill:${UUID.randomUUID()}"
        val result=g.runner.start(def,JsonObject(emptyMap()),ExecutionContext(id,id,"local-owner",g.settings.deviceId),Origin.LOCAL_OWNER)
        Assert.assertEquals(SkillState.SUCCEEDED,result.state)
        Assert.assertEquals(3,result.steps.size)
        Assert.assertEquals(result,g.runner.resume(id))
        val packages=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="apps.inspect",arguments=buildJsonObject { put("package","dev.siconnect.app") }),Origin.LOCAL_OWNER)
        Assert.assertEquals(RequestState.SUCCEEDED,packages.state)
    }
}
