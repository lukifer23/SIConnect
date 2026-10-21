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
        val r=g.engine.submit(InvocationRequest(requestId=id,agentId="local-owner",capabilityId="reminders.create",arguments=buildJsonObject {
            put("title","SIConnect timing test");put("text","Actual exact reminder delivery verified");put("fireAtMs",System.currentTimeMillis()+3000);put("exact",true)
        }),Origin.LOCAL_OWNER)
        Assert.assertEquals(RequestState.SUCCEEDED,r.state)
        withTimeout(20000) {while(g.requests.get("reminder:$id")?.state!=RequestState.SUCCEEDED) delay(100)}
        Assert.assertEquals("SUCCEEDED",g.reminders.get(id)!!.state)
        val nm=g.context.getSystemService(android.app.NotificationManager::class.java)
        Assert.assertTrue(nm.activeNotifications.any {it.id=="reminder:$id".hashCode() && it.notification.extras.getString(android.app.Notification.EXTRA_TITLE)=="SIConnect timing test"})
        nm.cancel("reminder:$id".hashCode())
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
