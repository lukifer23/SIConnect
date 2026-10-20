package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
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
        Assert.assertEquals(record,SqlRequestStore(reopened).get(id)); reopened.close()
        Assert.assertEquals(record.requestId,g.engine.submit(req,Origin.LOCAL_OWNER).requestId)
        try { g.engine.submit(req.copy(capabilityId="device.battery"),Origin.LOCAL_OWNER); Assert.fail("collision accepted") }
        catch(e: CapabilityException) { Assert.assertEquals("IDEMPOTENCY_CONFLICT",e.code) }
        Assert.assertTrue(g.events.query(requestId=id).any { it.eventType=="execution.finished" })
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
        val token=g.createDevelopmentAgent()
        val id="physical:${UUID.randomUUID()}"
        fun body(requestId: String)=buildJsonObject { put("requestId",requestId); put("capabilityId","shell.execute"); putJsonObject("arguments") { put("command","id") } }
        val denied=g.remoteInvoke(body("$id:deny"),token)
        Assert.assertEquals(RequestState.AWAITING_APPROVAL,denied.state)
        Assert.assertEquals(RequestState.DENIED,g.engine.resolveApproval(denied.requestId,false,"instrumented-local-owner",denied.argsHash).state)
        val approved=g.remoteInvoke(body("$id:approve"),token)
        val executed=g.engine.resolveApproval(approved.requestId,true,"instrumented-local-owner",approved.argsHash)
        Assert.assertEquals(RequestState.SUCCEEDED,executed.state)
        Assert.assertTrue(executed.result!!.identity!!.startsWith("uid=2000(shell)"))
        val revoked=g.remoteInvoke(body("$id:revoked"),token)
        val agent=g.agents.get("development-client")!!
        g.agents.put(agent.copy(grant=agent.grant.copy(revoked=true)))
        Assert.assertEquals(RequestState.FAILED,g.engine.resolveApproval(revoked.requestId,true,"instrumented-local-owner",revoked.argsHash).state)
        Assert.assertNull(g.requests.get(revoked.requestId)!!.result)
        // Restore a fresh development credential for subsequent authenticated integration tests.
        g.createDevelopmentAgent()
        Unit
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
