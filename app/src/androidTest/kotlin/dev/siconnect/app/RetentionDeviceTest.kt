package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.*
import dev.siconnect.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Disposable SQLite databases only; does not prune the owner's execution history. */
class RetentionDeviceTest {
    @Test fun inboxLinksRemainAuthoritativeAndPrunedOutcomesCannotBeInsertedAgain() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="retention-${UUID.randomUUID()}.db"
        val now=System.currentTimeMillis()
        try {SIConnectDb(context,name).use {helper->
            val requests=SqlRequestStore(helper);val proposals=SqlProposalStore(helper)
            val r=RequestRecord("fixture-request","fixture-trace","fixture-agent","fixture-device","device.status",1,
                buildJsonObject {put("private","fixture")},"hash","fixture-key","REMOTE",RequestState.SUCCEEDED,
                createdAtMs=now-1000,updatedAtMs=now-500,expiresAtMs=now+1000,operationFingerprint="immutable-fixture-fingerprint",
                result=CapabilityResult(buildJsonObject {put("privateResult","fixture")},BackendKind.NATIVE))
            assertTrue(requests.insert(r))
            val p=ActionProposal(proposalId="fixture-proposal",agentId=r.agentId,deviceId=r.deviceId,
                createdAtMs=now-1000,expiresAtMs=now+1000,title="Disposable",summary="Retention fixture",reason="Fixture",
                sourceType="fixture",sourceReference=null,capabilityId=r.capabilityId,capabilityVersion=1,
                arguments=buildJsonObject {},assessment=RiskAssessment(RiskClass.READ_ONLY,"Fixture"),actionHash="hash",idempotencyKey="fixture-proposal-key",
                state=ProposalState.ACCEPTED,linkedRequestId=r.requestId)
            proposals.insert(p)
            assertEquals(0,requests.prune(now)) // Previously deleted the authoritative linked record.
            assertEquals(r,requests.get(r.requestId))
            assertTrue(proposals.update(p.copy(state=ProposalState.COMPLETED,requestState=RequestState.SUCCEEDED,revision=1),0))
            assertEquals(1,requests.prune(now))
            val compact=requireNotNull(requests.get(r.requestId))
            assertEquals(r.state,compact.state);assertEquals("RESULT_EXPIRED",compact.errorCode)
            assertEquals(r.operationFingerprint,compact.operationFingerprint);assertTrue(compact.arguments.isEmpty());assertNull(compact.result)
            assertEquals(compact,requests.findByIdempotencyKey(r.agentId,requireNotNull(r.idempotencyKey)))
            assertFalse(requests.insert(r));assertFalse(requests.insert(r.copy(requestId="another-fixture-id")))
            assertEquals(0,requests.prune(now))
            assertEquals("ok",helper.readableDatabase.rawQuery("PRAGMA quick_check",null).use {it.moveToFirst();it.getString(0)})
        }} finally {context.deleteDatabase(name)}
    }
    @Test fun unresolvedAuditEvidenceSurvivesAgeAndCountTrimming() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="events-retention-${UUID.randomUUID()}.db"
        try {SIConnectDb(context,name).use {helper->
            val requests=SqlRequestStore(helper);val events=SqlEventLog(helper,1)
            requests.insert(RequestRecord("unresolved","trace","fixture-agent","fixture-device","device.status",arguments=buildJsonObject {},
                argsHash="fixture",origin="REMOTE",state=RequestState.UNKNOWN_OUTCOME,createdAtMs=1,updatedAtMs=2,expiresAtMs=3))
            events.emit(Event(1,eventType="execution.started",requestId="unresolved"))
            events.emit(Event(2,eventType="irrelevant.fixture"));events.emit(Event(3,eventType="irrelevant.fixture"))
            events.trim(4)
            assertEquals("execution.started",events.query(requestId="unresolved").single().eventType)
            assertEquals(1,events.count())
        }} finally {context.deleteDatabase(name)}
    }
    @Test fun versionEightUpgradeAddsRetentionWithoutChangingOwnerRows() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="retention-migration-${UUID.randomUUID()}.db"
        try {
            SIConnectDb(context,name).use {helper->
                helper.writableDatabase.apply {
                    execSQL("INSERT INTO agents VALUES('fixture','fixture',1,NULL,'preserved')")
                    execSQL("DROP TABLE request_tombstones");execSQL("DROP INDEX requests_parent");version=8
                }
            }
            repeat(2) {SIConnectDb(context,name).use {helper->
                assertEquals(9,helper.readableDatabase.version)
                assertEquals("preserved",helper.readableDatabase.rawQuery("SELECT json FROM agents WHERE agent_id='fixture'",null).use {it.moveToFirst();it.getString(0)})
                assertEquals(0,helper.readableDatabase.rawQuery("SELECT count(*) FROM request_tombstones",null).use {it.moveToFirst();it.getInt(0)})
            }}
        } finally {context.deleteDatabase(name)}
    }
}
