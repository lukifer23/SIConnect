package dev.siconnect.app

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Actual SQLite + registered production capabilities. No Messages window is opened/read. */
class MessagesAuthorizationDeviceTest {
    private suspend fun fixture(scopes:List<String>,body:suspend (RuntimeGraph,String)->Unit) {
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph;g.ready.await()
        val id="messages-policy-${UUID.randomUUID()}";val now=System.currentTimeMillis()
        g.agents.put(AgentRecord(AgentGrant(id,"Disposable Messages authorization",scopes=scopes,
            autoApproveUpTo=RiskClass.HIGH_IMPACT,expiresAtMs=now+60000),Canonical.sha256Hex(UUID.randomUUID().toString()),now,tokenHint="Messages authorization fixture"))
        try {body(g,id)} finally {
            val records=g.db.readableDatabase.query("requests",arrayOf("request_id","state"),"agent_id=?",arrayOf(id),null,null,null)
                .use {c->buildList {while(c.moveToNext()) add(c.getString(0) to RequestState.valueOf(c.getString(1)))}}
            if(records.any {!it.second.isTerminal || it.second==RequestState.UNKNOWN_OUTCOME}) {
                g.revokeAgentFromOwner(id) // Preserve any unexpected uncertain/running fixture, never replay it.
            } else {
                records.forEach {(requestId,_)->g.db.writableDatabase.delete("request_transitions","request_id=?",arrayOf(requestId))}
                g.db.writableDatabase.delete("requests","agent_id=?",arrayOf(id))
                g.db.writableDatabase.delete("events","agent_id=?",arrayOf(id))
                g.agents.delete(id)
            }
        }
    }
    @Test fun messagesOnlyGrantStopsAtRealDeniedHeadlessChildBeforeAnyAppDispatch()=runBlocking {
        fixture(listOf("messages.recent")) {g,id->
            assumeTrue("Real private backend must be available",Build.VERSION.SDK_INT>=34 && g.privileged.available())
            assumeTrue("Installed Google Messages is required",runCatching {g.context.packageManager.getApplicationInfo("com.google.android.apps.messaging",0)}.isSuccess)
            val before=g.agents.grant(id)
            val parent=g.engine.submit(InvocationRequest(agentId=id,capabilityId="messages.recent",arguments=buildJsonObject {
                put("conversationLabel","Disposable nonexistent conversation");put("count",1)
            }),Origin.REMOTE)
            assertEquals(parent.errorMessage,RequestState.SUCCEEDED,parent.state)
            val output=requireNotNull(parent.result).output.jsonObject
            assertFalse(output.getValue("countFulfilled").jsonPrimitive.boolean)
            assertEquals("CHILD_POLICY_DENIED",output.getValue("stopReason").jsonPrimitive.content)
            assertFalse(output.getValue("workspaceCreated").jsonPrimitive.boolean)
            val childId=output.getValue("blockingChildRequestId").jsonPrimitive.content
            val child=requireNotNull(g.requests.get(childId))
            assertEquals(parent.requestId,child.parentRequestId);assertEquals(id,child.agentId)
            assertEquals("headless.open",child.capabilityId);assertEquals(RequestState.DENIED,child.state)
            assertEquals(0,g.events.query(requestId=childId).count {it.eventType=="execution.started"})
            assertEquals(before,g.agents.grant(id))
        }
    }
    @Test fun registeredHeadlessBodyRejectsOriginalBackendMismatchBeforeRealIpc()=runBlocking {
        fixture(listOf("headless.status")) {g,id->
            val capability=requireNotNull(g.registry.get("headless.status"));val args=buildJsonObject {}
            val other=if(g.privileged.kind==BackendKind.ADB_SERVICE) BackendKind.SHIZUKU else BackendKind.ADB_SERVICE
            val now=System.currentTimeMillis();val requestId=UUID.randomUUID().toString()
            val record=RequestRecord(requestId,requestId,id,g.settings.deviceId,"headless.status",capability.descriptor.version,args,
                Canonical.actionHash("headless.status",capability.descriptor.version,args),origin=Origin.REMOTE.name,state=RequestState.EXECUTING,
                createdAtMs=now,updatedAtMs=now,expiresAtMs=now+10000,selectedBackend=other)
            assertTrue(g.requests.insert(record))
            try {
                capability.execute(args,ExecutionContext(requestId,requestId,id,g.settings.deviceId,now+10000))
                fail("Original backend mismatch must reject before workspace IPC")
            } catch(e:CapabilityException) {
                assertEquals("CAPABILITY_CHANGED",e.code);assertTrue(e.noSideEffects)
            } finally {
                // This isolated metadata fixture was never submitted to an execution worker.
                assertTrue(g.requests.update(record.copy(state=RequestState.FAILED,updatedAtMs=System.currentTimeMillis()),RequestState.EXECUTING))
            }
        }
    }
}
