package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.app.termux.CodexLaunch
import dev.siconnect.app.termux.TermuxBridge
import dev.siconnect.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real registered capabilities/core SQLite/policy. Both cases stop before any model execution. */
class CodexPolicyDeviceTest {
    private fun args()=buildJsonObject {
        put("nodeEntrypoint","/data/data/com.termux/files/usr/lib/node_modules/@openai/codex/bin/codex.js")
        put("cwd","/data/data/com.termux/files/home")
        put("prompt","This run must remain blocked by the actual Termux child policy; never execute a model request")
        put("timeoutMs",10000)
    }
    private suspend fun checkChild(needsApproval:Boolean) {
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph;g.ready.await()
        assertTrue("Termux readiness must be configured for the real parent capability",requireNotNull(g.registry.get("termux.execute")).availability().isExecutable)
        assertTrue(g.settings.controls.value.remoteExecutionEnabled)
        val id="codex-policy-test-${UUID.randomUUID()}";val input=args()
        val standing=if(needsApproval) listOf(StandingGrant("codex.execute",actionHash=Canonical.actionHash("codex.execute",1,input),
            maxRisk=RiskClass.HIGH_IMPACT,backend=BackendKind.NATIVE)) else emptyList()
        g.agents.put(AgentRecord(AgentGrant(id,"Disposable Codex child policy acceptance",
            scopes=if(needsApproval) listOf("codex.execute","termux.execute") else listOf("codex.execute"),
            autoApproveUpTo=RiskClass.HIGH_IMPACT,shellMode=if(needsApproval) ShellMode.APPROVAL_REQUIRED else ShellMode.BROAD,
            standing=standing,allowSharedScreen=false,expiresAtMs=System.currentTimeMillis()+60000),"",System.currentTimeMillis(),tokenHint="Codex policy acceptance only"))
        val parent=InvocationRequest(agentId=id,capabilityId="codex.execute",arguments=input,expiresAtMs=System.currentTimeMillis()+30000)
        val childId=CodexLaunch.childRequestId(parent.requestId)
        try {
            val result=g.engine.submit(parent,Origin.REMOTE)
            assertEquals(result.errorMessage,RequestState.SUCCEEDED,result.state)
            val child=requireNotNull(g.requests.get(childId))
            assertEquals(if(needsApproval) RequestState.AWAITING_APPROVAL else RequestState.DENIED,child.state)
            assertEquals(parent.requestId,child.parentRequestId);assertEquals(id,child.agentId);assertEquals("REMOTE",child.origin)
            assertEquals(result.traceId,child.traceId);assertNull(child.approvedBy)
            assertTrue(child.expiresAtMs<=result.expiresAtMs)
            assertEquals(childId,result.result?.output?.jsonObject?.get("executionId")?.jsonPrimitive?.content)
            assertEquals(needsApproval,result.result?.output?.jsonObject?.get("awaitingApproval")?.jsonPrimitive?.booleanOrNull)
            val executions=g.db.readableDatabase.rawQuery("SELECT count(*) FROM request_transitions WHERE request_id=? AND to_state='EXECUTING'",arrayOf(childId)).use {it.moveToFirst();it.getInt(0)}
            assertEquals(0,executions)
            try {TermuxBridge.get(g.context).result(id,childId);fail("Blocked child must not have a native dispatch journal")}
            catch(error:CapabilityException) {assertEquals("TERMUX_RESULT_NOT_FOUND",error.code)}
            assertEquals(result,g.engine.submit(parent,Origin.REMOTE))
        } finally {
            val child=g.requests.get(childId)
            if(child?.state==RequestState.AWAITING_APPROVAL) g.engine.cancel(childId,"local-owner")
            val final=g.requests.get(childId)
            if(final==null || final.state in setOf(RequestState.DENIED,RequestState.CANCELLED,RequestState.FAILED)) g.agents.delete(id)
            else {g.revokeAgentFromOwner(id);fail("Unexpected native-capable child state retained/revoked: ${final.state}; no fake approval or model request was authorized")}
        }
    }
    @Test fun codexParentScopeCannotBypassMissingActualTermuxScope()=runBlocking {checkChild(false)}
    @Test fun exactParentStandingGrantDoesNotApproveTheTermuxChild()=runBlocking {checkChild(true)}
}
