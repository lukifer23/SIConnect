package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.app.termux.TermuxBridge
import dev.siconnect.app.termux.TermuxContract
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real official Termux service, app-UID process, one-shot callback and persistent journals. */
class TermuxNativeDeviceTest {
    private val prefix="/data/data/com.termux/files/usr/bin/"
    private suspend fun fixture(body:suspend (RuntimeGraph,String,Int,MutableList<String>)->Unit) {
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph;g.ready.await()
        val status=withContext(Dispatchers.IO) {TermuxBridge.get(g.context).status()}
        assertTrue("Install and initialize real Termux before this acceptance test",status.installed)
        assertTrue("Enable the real Termux app and its official RUN_COMMAND service",status.appEnabled && status.serviceAvailable)
        assertTrue("Grant SIConnect the owner-controlled Run commands in Termux Android permission",status.runCommandPermissionGranted)
        assertFalse("Termux>=0.109 callback API is required",status.resultApiSupported==false)
        assertTrue("Owner must enable remote execution for the disposable remote agent",g.settings.controls.value.remoteExecutionEnabled)
        val uid=requireNotNull(status.uid)
        assertNotEquals("Termux must execute in its own app UID",android.os.Process.myUid(),uid)
        assertNotEquals("Termux is not the privileged shell backend",2000,uid)
        val id="termux-native-test-${UUID.randomUUID()}";val requests=mutableListOf<String>()
        g.agents.put(AgentRecord(AgentGrant(id,"Disposable real Termux acceptance",scopes=listOf("termux.*"),
            autoApproveUpTo=RiskClass.HIGH_IMPACT,shellMode=ShellMode.BROAD,allowSharedScreen=false,
            expiresAtMs=System.currentTimeMillis()+300_000),"",System.currentTimeMillis(),tokenHint="Termux native test only"))
        try {body(g,id,uid,requests)} finally {
            val all=(requests+g.requests.historyPage(limit=500,agentId=id).map {it.requestId}).distinct().mapNotNull(g.requests::get)
            val unresolved=all.filter {record->
                if(!record.state.isTerminal) true
                else if(record.state!=RequestState.UNKNOWN_OUTCOME) false
                else if(record.capabilityId!="termux.execute") true
                else runCatching {
                    val result=TermuxBridge.get(g.context).result(id,record.requestId)
                    result["callbackReceived"]?.jsonPrimitive?.booleanOrNull!=true || result["commandSucceeded"]?.jsonPrimitive?.booleanOrNull!=true
                }.getOrDefault(true)
            }
            if(unresolved.isEmpty()) g.agents.delete(id)
            else {
                g.revokeAgentFromOwner(id)
                fail("Retained and revoked disposable Termux identity $id: ${unresolved.map {it.state to it.errorCode}}; no uncertain outcome was deleted")
            }
            // Core request and callback journals intentionally remain as actual evidence.
        }
    }
    private suspend fun read(g:RuntimeGraph,id:String,capability:String,args:JsonObject=buildJsonObject {}):JsonObject {
        val record=g.engine.submit(InvocationRequest(agentId=id,capabilityId="termux.$capability",arguments=args),Origin.REMOTE)
        assertEquals("$capability: ${record.errorCode}: ${record.errorMessage}",RequestState.SUCCEEDED,record.state)
        return requireNotNull(record.result).output.jsonObject
    }
    private fun command(marker:String,timeout:Long,delay:Boolean=false)=buildJsonObject {
        put("executable",prefix+"sh")
        val script=(if(delay) "${prefix}sleep 2; " else "")+"printf '%s\\n' '$marker'; ${prefix}id -u"
        put("arguments",JsonArray(listOf(JsonPrimitive("-c"),JsonPrimitive(script))))
        put("timeoutMs",timeout);put("maxOutputBytes",4096)
    }
    private fun assertNativeResult(result:JsonObject,marker:String,uid:Int) {
        assertEquals(true,result["callbackReceived"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true,result["commandSucceeded"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(TermuxContract.RESULT_ERR_SUCCESS,result["executionErrorCode"]?.jsonPrimitive?.int)
        assertEquals(0,result["exitCode"]?.jsonPrimitive?.int)
        assertEquals(uid,result["packageUid"]?.jsonPrimitive?.int)
        assertEquals(listOf(marker,uid.toString()),result.getValue("stdout").jsonPrimitive.content.lineSequence().filter(String::isNotBlank).toList())
        assertTrue(result.getValue("stderr").jsonPrimitive.content.isEmpty())
        assertFalse(result.getValue("stdoutTruncated").jsonPrimitive.boolean)
    }
    private fun executingCount(g:RuntimeGraph,requestId:String)=g.db.readableDatabase.rawQuery(
        "SELECT count(*) FROM request_transitions WHERE request_id=? AND to_state='EXECUTING'",arrayOf(requestId)).use {it.moveToFirst();it.getInt(0)}

    @Test fun actualTermuxUidOutputAndOwnHistoryCannotReplayOrLeakAcrossAgents()=runBlocking {
        fixture {g,id,uid,requests->
            val marker="termux-native-${UUID.randomUUID()}"
            val invocation=InvocationRequest(agentId=id,capabilityId="termux.execute",arguments=command(marker,15000),expiresAtMs=System.currentTimeMillis()+30_000)
            requests+=invocation.requestId
            val completed=g.engine.submit(invocation,Origin.REMOTE)
            assertEquals("Check Termux allow-external-apps=true and initialized environment: ${completed.errorCode}: ${completed.errorMessage}",RequestState.SUCCEEDED,completed.state)
            assertEquals(BackendKind.NATIVE,completed.result?.backend)
            assertNativeResult(requireNotNull(completed.result).output.jsonObject,marker,uid)
            val journal=read(g,id,"result",buildJsonObject {put("executionId",invocation.requestId)})
            assertNativeResult(journal,marker,uid)
            val before=journal.getValue("callbackAtMs")
            assertEquals(completed,g.engine.submit(invocation,Origin.REMOTE))
            assertEquals(1,executingCount(g,invocation.requestId))
            assertEquals(before,TermuxBridge.get(g.context).result(id,invocation.requestId).getValue("callbackAtMs"))
            val recent=read(g,id,"recent",buildJsonObject {put("limit",50)})
            val item=recent.getValue("executions").jsonArray.single {it.jsonObject["executionId"]?.jsonPrimitive?.content==invocation.requestId}.jsonObject
            assertFalse(item.containsKey("stdout"));assertFalse(item.containsKey("nonceHash"));assertFalse(item.containsKey("arguments"))
            assertEquals(true,item["callbackReceived"]?.jsonPrimitive?.booleanOrNull)
            val other="termux-isolation-test-${UUID.randomUUID()}"
            g.agents.put(AgentRecord(AgentGrant(other,"Disposable Termux read isolation",scopes=listOf("termux.result","termux.recent"),
                autoApproveUpTo=RiskClass.READ_ONLY,allowSharedScreen=false,expiresAtMs=System.currentTimeMillis()+60_000),"",System.currentTimeMillis(),tokenHint="Termux read isolation only"))
            try {
                val denied=g.engine.submit(InvocationRequest(agentId=other,capabilityId="termux.result",arguments=buildJsonObject {put("executionId",invocation.requestId)}),Origin.REMOTE)
                assertEquals(RequestState.FAILED,denied.state);assertEquals("TERMUX_RESULT_NOT_FOUND",denied.errorCode)
                assertTrue(read(g,other,"recent").getValue("executions").jsonArray.isEmpty())
            } finally {g.agents.delete(other)}
        }
    }

    @Test fun realTimeoutRetainsLateCallbackWithoutChangingOrReplayingUnknownCoreRequest()=runBlocking {
        fixture {g,id,uid,requests->
            val marker="termux-late-${UUID.randomUUID()}"
            val invocation=InvocationRequest(agentId=id,capabilityId="termux.execute",arguments=command(marker,250,delay=true),expiresAtMs=System.currentTimeMillis()+30_000)
            requests+=invocation.requestId
            val timedOut=g.engine.submit(invocation,Origin.REMOTE)
            assertEquals("A real dispatched sleep2 command must outlast the250ms wait: ${timedOut.errorCode}: ${timedOut.errorMessage}",RequestState.UNKNOWN_OUTCOME,timedOut.state)
            assertEquals("TERMUX_OUTCOME_PENDING",timedOut.errorCode)
            assertEquals(1,executingCount(g,invocation.requestId))
            val late=withTimeout(15000) {
                while(true) {
                    val result=withContext(Dispatchers.IO) {TermuxBridge.get(g.context).result(id,invocation.requestId)}
                    if(result["callbackReceived"]?.jsonPrimitive?.booleanOrNull==true) return@withTimeout result
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE") error("No Termux callback")
            }
            assertNativeResult(late,marker,uid)
            assertEquals("TIMED_OUT",late["waitStatus"]?.jsonPrimitive?.content)
            assertEquals(timedOut,g.requests.get(invocation.requestId))
            assertEquals(timedOut,g.engine.submit(invocation,Origin.REMOTE))
            assertEquals(1,executingCount(g,invocation.requestId))
            assertNativeResult(read(g,id,"result",buildJsonObject {put("executionId",invocation.requestId)}),marker,uid)
            val unresolved=read(g,id,"recent",buildJsonObject {put("unresolvedOnly",true)})
            assertTrue(unresolved.getValue("executions").jsonArray.isEmpty())
            assertEquals(RequestState.UNKNOWN_OUTCOME,g.requests.get(invocation.requestId)?.state)
        }
    }
}
