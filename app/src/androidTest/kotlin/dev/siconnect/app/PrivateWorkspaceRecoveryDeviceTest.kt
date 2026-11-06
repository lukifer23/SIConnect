package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Provisions only a real, expiring test principal for the external process-death harness.
 * Actual requests run through the existing authenticated debug endpoint in a normal app process. */
class PrivateWorkspaceRecoveryDeviceTest {
    @Test fun manageDisposableRecoveryClient() = runBlocking {
        val mode=InstrumentationRegistry.getArguments().getString("privateRecoveryClient")
        assumeTrue("Use tools/private_workspace_recovery.py",mode in setOf("create","remove"))
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph;g.ready.await()
        val file=File(g.context.cacheDir,"private-workspace-recovery-client.json")
        if(mode=="create") {
            require(!file.exists()) {"Finish cleanup of the previous recovery test first"}
            val now=System.currentTimeMillis();val id="workspace-recovery-${UUID.randomUUID()}"
            val token=UUID.randomUUID().toString()+UUID.randomUUID().toString()
            g.agents.put(AgentRecord(AgentGrant(id,"Disposable private workspace recovery",scopes=listOf("headless.*","device.*"),
                autoApproveUpTo=RiskClass.HIGH_IMPACT,expiresAtMs=now+600_000,allowSharedScreen=false),Canonical.sha256Hex(token),now,
                tokenHint="recovery acceptance only"))
            file.writeText(buildJsonObject {put("agentId",id);put("token",token);put("gatewayEnabled",g.settings.gateway.value.enabled);put("phoneRecoveryEnabled",g.bootstrap.recoveryEnabled)}.toString())
        } else if(file.exists()) {
            val id=Json.parseToJsonElement(file.readText()).jsonObject.getValue("agentId").jsonPrimitive.content
            require(id.startsWith("workspace-recovery-"))
            val record=g.agents.get(id)
            require(record==null || record.tokenHint=="recovery acceptance only")
            try {
                if(record!=null) {
                    // Restore only this disposable principal long enough for its owned cleanup.
                    g.agents.put(record.copy(grant=record.grant.copy(revoked=false,expiresAtMs=System.currentTimeMillis()+30_000)))
                    val status=g.engine.submit(InvocationRequest(agentId=id,capabilityId="headless.status"),Origin.REMOTE)
                    if(status.state==RequestState.SUCCEEDED) for(session in status.result!!.output.jsonObject.getValue("sessions").jsonArray) {
                        val args=buildJsonObject {put("sessionId",session.jsonObject.getValue("sessionId"));put("packageName",session.jsonObject.getValue("packageName"))}
                        val closed=g.engine.submit(InvocationRequest(agentId=id,capabilityId="headless.close",arguments=args),Origin.REMOTE)
                        check(closed.state==RequestState.SUCCEEDED) {"Owned cleanup was not confirmed"}
                    }
                }
            } finally {g.agents.delete(id);file.delete()}
        }
    }
}
