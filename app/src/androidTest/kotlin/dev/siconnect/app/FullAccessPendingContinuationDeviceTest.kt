package dev.siconnect.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.backend.ShellResult
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.app.data.AppJson
import dev.siconnect.app.data.Settings
import dev.siconnect.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real approval records, SQLite transitions and authorized shell UID; no files, app launches, or Clock changes. */
@RunWith(AndroidJUnit4::class)
class FullAccessPendingContinuationDeviceTest {
    @Test fun explicitContinuationRunsOriginalHarmlessShellExactlyOnceAndPreservesOtherOutcomes()=runBlocking {
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph
        g.ready.await()
        // Instrumentation restarts the app process. The already-authorized daemon
        // reattaches asynchronously after its previous owner dies; observe that real
        // recovery before deciding the physical backend is unavailable.
        val backendReady=withTimeoutOrNull(10_000) {
            while(!g.privileged.available()) delay(50)
            true
        } ?: false
        org.junit.Assume.assumeTrue("Actual authorized shell backend remained unavailable after 10 seconds",backendReady)
        org.junit.Assume.assumeTrue("Owner must already allow remote and privileged execution",
            g.settings.controls.value.remoteExecutionEnabled && g.settings.controls.value.privilegedEnabled)
        val backend=g.privileged.kind
        assertTrue(backend in setOf(BackendKind.ADB_SERVICE,BackendKind.SHIZUKU))
        fun originalRecords()=g.agents.all().associate {it.grant.agentId to it.copy(lastSeenAtMs=null)}
        val original=originalRecords()
        val id="pending-profile-test-${UUID.randomUUID()}"
        val marker="pending-profile-"+UUID.randomUUID().toString()
        val fixture=AgentRecord(AgentGrant(id,"Disposable pending full-profile continuation",scopes=listOf("shell.execute"),
            autoApproveUpTo=RiskClass.READ_ONLY,shellMode=ShellMode.APPROVAL_REQUIRED,allowSharedScreen=true,
            expiresAtMs=System.currentTimeMillis()+120_000),Settings.sha256Hex(Settings.randomToken("fixture_")),
            System.currentTimeMillis(),tokenHint="device test only")
        val requests=mutableListOf<String>()
        suspend fun waiting(suffix:String):RequestRecord {
            val record=g.engine.submit(InvocationRequest(agentId=id,capabilityId="shell.execute",expiresAtMs=System.currentTimeMillis()+60_000,
                arguments=buildJsonObject {
                    // UUID markers contain only safe fixed ASCII; these commands print and
                    // read identity. They do not write files or change device/user state.
                    put("command","printf '%s\\n' '$marker-$suffix'; /system/bin/id -u")
                    put("timeoutMs",5000);put("maxOutputBytes",4096);put("outputEncoding","text")
                }),Origin.REMOTE)
            requests+=record.requestId
            assertEquals("Expected a real default shell approval: ${record.errorCode}: ${record.errorMessage}",RequestState.AWAITING_APPROVAL,record.state)
            assertEquals(backend,record.selectedBackend)
            return record
        }
        fun executions(requestId:String)=g.db.readableDatabase.rawQuery(
            "SELECT count(*) FROM request_transitions WHERE request_id=? AND to_state='EXECUTING'",arrayOf(requestId)).use {it.moveToFirst();it.getInt(0)}
        try {
            g.agents.put(fixture)
            val pending=waiting("continue")
            val cancelled=waiting("cancelled")
            assertEquals(RequestState.CANCELLED,g.engine.cancel(cancelled.requestId,"local-owner")?.state)
            val higherRisk=waiting("classified-critical")
            // Exercise explicit full-control continuation of a persisted higher-risk
            // classification using only this harmless real request. No synthetic
            // capability or executor is registered, and no dangerous command is run.
            val critical=higherRisk.copy(assessment=requireNotNull(higherRisk.assessment).copy(risk=RiskClass.CRITICAL))
            assertTrue(g.requests.update(critical,RequestState.AWAITING_APPROVAL))
            val expired=waiting("expired")
            assertTrue(g.requests.update(expired.copy(approvalExpiresAtMs=System.currentTimeMillis()-1),RequestState.AWAITING_APPROVAL))
            try {
                g.resumeEligiblePendingForFullAccessFromOwner(id)
                fail("A mere continuation call must not enable full control")
            } catch(_:IllegalArgumentException) { }
            g.setFullAccessFromOwner(id,true)
            assertEquals("Saving a profile must not silently continue waiting work",RequestState.AWAITING_APPROVAL,g.requests.get(pending.requestId)?.state)
            assertEquals(0,executions(pending.requestId))
            val continued=g.resumeEligiblePendingForFullAccessFromOwner(id)
            assertEquals(setOf(pending.requestId,higherRisk.requestId),continued.records.map {it.requestId}.toSet())
            assertFalse(continued.morePending)
            assertTrue("The eligible and higher-risk waiting records must both be reviewed",continued.examined>=2)
            suspend fun verifiedExecution(originalRequest:RequestRecord,suffix:String):RequestRecord {
                val completed=requireNotNull(g.engine.await(originalRequest.requestId,15_000,untilTerminal=true))
                assertEquals(completed.errorMessage,RequestState.SUCCEEDED,completed.state)
                assertEquals(originalRequest.requestId,completed.requestId)
                assertEquals(originalRequest.argsHash,completed.argsHash)
                assertEquals(originalRequest.arguments,completed.arguments)
                assertEquals("local-owner",completed.approvedBy)
                assertEquals(backend,completed.result?.backend)
                val output=AppJson.decodeFromJsonElement<ShellResult>(requireNotNull(completed.result).output)
                assertEquals(originalRequest.requestId,output.execId)
                assertEquals(0,output.exitCode)
                assertEquals(listOf("$marker-$suffix","2000"),output.stdout.lineSequence().filter(String::isNotBlank).toList())
                assertTrue(output.identity.contains("uid=2000"))
                assertFalse(output.timedOut);assertFalse(output.cancelled)
                assertFalse(output.stdoutTruncated);assertFalse(output.stderrTruncated)
                assertNull(output.error)
                assertEquals(1,executions(originalRequest.requestId))
                assertEquals(1,g.events.query(requestId=originalRequest.requestId).count {it.eventType=="execution.started"})
                return completed
            }
            val completed=verifiedExecution(pending,"continue")
            val completedCritical=verifiedExecution(higherRisk,"classified-critical")
            assertEquals(RiskClass.CRITICAL,completedCritical.assessment?.risk)
            assertEquals(RequestState.CANCELLED,g.requests.get(cancelled.requestId)?.state)
            assertEquals(0,executions(cancelled.requestId))
            assertTrue(g.requests.get(expired.requestId)?.state in setOf(RequestState.AWAITING_APPROVAL,RequestState.EXPIRED))
            assertEquals(0,executions(expired.requestId))
            val again=g.resumeEligiblePendingForFullAccessFromOwner(id)
            assertTrue("Completed or cancelled requests must never be replayed",again.records.isEmpty())
            assertEquals(completed,g.requests.get(pending.requestId))
            assertEquals(1,executions(pending.requestId))
            assertEquals(completedCritical,g.requests.get(higherRisk.requestId))
            assertEquals(1,executions(higherRisk.requestId))
            assertEquals(0,executions(cancelled.requestId));assertEquals(0,executions(expired.requestId))
        } finally {
            // Resolve only this fixture's remaining waiting/running work. Unknown or
            // unreconciled outcomes are retained for inspection instead of being erased.
            val ownRequestIds=(requests+g.requests.historyPage(limit=100,agentId=id).map {it.requestId}).distinct()
            for(request in ownRequestIds) {
                val record=g.requests.get(request)
                if(record!=null && !record.state.isTerminal) {
                    g.engine.cancel(request,"local-owner")
                    g.engine.await(request,6000,untilTerminal=true)
                }
            }
            val unresolved=ownRequestIds.mapNotNull(g.requests::get).filter { !it.state.isTerminal || it.state==RequestState.UNKNOWN_OUTCOME }
            if(unresolved.isEmpty()) {
                val db=g.db.writableDatabase
                db.beginTransaction()
                try {
                    db.delete("events","agent_id=?",arrayOf(id))
                    ownRequestIds.forEach {request->db.delete("request_transitions","request_id=?",arrayOf(request))}
                    db.delete("requests","agent_id=?",arrayOf(id))
                    db.delete("request_tombstones","agent_id=?",arrayOf(id))
                    g.agents.delete(id)
                    db.setTransactionSuccessful()
                } finally {db.endTransaction()}
                assertEquals(original,originalRecords())
            } else {
                runCatching {g.revokeAgentFromOwner(id)}
                fail("Disposable continuation records were retained for outcome reconciliation; no unknown outcome was deleted")
            }
        }
    }
}
