package dev.siconnect.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.*
import dev.siconnect.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual SQLite/registry/remote authentication/policy/native execution; trusted API resolution is NOT a biometric test. */
@RunWith(AndroidJUnit4::class)
class ProposalDeviceTest {
    @Test fun authenticatedProposalPersistsAndExecutesNativeActionOnlyAfterLocalResolution()=runBlocking {
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph;g.ready.await()
        val id="proposal-fixture:${UUID.randomUUID()}";val token=Settings.randomToken("test_")
        val key=UUID.randomUUID().toString();var proposalId:String?=null
        val grant=AgentGrant(id,"Disposable proposal fixture",scopes=listOf("proposals.*","device.status"),autoApproveUpTo=RiskClass.LOW_IMPACT,expiresAtMs=System.currentTimeMillis()+120000)
        g.agents.put(AgentRecord(grant,Settings.sha256Hex(token),System.currentTimeMillis(),tokenHint="test only"))
        suspend fun invoke(cap:String,args:JsonObject)=g.remoteInvoke(buildJsonObject {put("capabilityId",cap);put("arguments",args)},token)
        val args=buildJsonObject {
            put("title","Inspect disposable device state");put("summary","Read native diagnostics without changing the phone")
            put("reason","Disposable integration test");put("sourceType","fixture")
            put("capabilityId","device.status");put("arguments",buildJsonObject {})
            put("idempotencyKey",key);put("expiresAtMs",System.currentTimeMillis()+60000)
        }
        try {
            val created=invoke("proposals.create",args)
            assertEquals(created.errorMessage,RequestState.SUCCEEDED,created.state)
            val p=AppJson.decodeFromJsonElement<ActionProposal>(created.result!!.output.jsonObject.getValue("proposal"));proposalId=p.proposalId
            assertNull(p.linkedRequestId);assertEquals(ProposalState.PROPOSED,p.state)
            assertEquals(grant,g.agents.grant(id))
            SIConnectDb(g.context).use {assertEquals(p,SqlProposalStore(it).get(p.proposalId))}
            val duplicate=invoke("proposals.create",args)
            assertEquals(p.proposalId,duplicate.result!!.output.jsonObject.getValue("proposal").jsonObject.getValue("proposalId").jsonPrimitive.content)
            try {g.proposals.getForAgent("another-fixture",p.proposalId);fail("Cross-agent proposal exposed")} catch(e:CapabilityException) {assertEquals("NOT_FOUND",e.code)}
            val reviewed=g.proposals.reviewFromOwner(p.proposalId)
            val accepted=g.proposals.acceptFromOwner(p.proposalId,reviewed.revision)
            g.proposals.dispatchAccepted(p.proposalId);g.proposals.dispatchAccepted(p.proposalId)
            val done=g.proposals.getForAgent(id,p.proposalId)
            assertEquals(ProposalState.COMPLETED,done.state);assertEquals(RequestState.SUCCEEDED,done.requestState)
            val record=requireNotNull(g.requests.get(requireNotNull(accepted.linkedRequestId)))
            assertEquals(Origin.REMOTE.name,record.origin);assertEquals(id,record.agentId)
            assertEquals(android.os.Build.MODEL,record.result!!.output.jsonObject.getValue("model").jsonPrimitive.content)
            assertEquals(1,g.events.query(requestId=record.requestId).count {it.eventType=="execution.started"})
            assertEquals(grant,g.agents.grant(id))
            SIConnectDb(g.context).use {assertEquals(done,SqlProposalStore(it).get(p.proposalId))}
        } finally {
            proposalId?.let {g.db.writableDatabase.delete("proposals","proposal_id=?",arrayOf(it))}
            g.agents.delete(id)
        }
    }

    @Test fun schemaSixUpgradePreservesExistingRowsAndAddsIndexedInbox() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="proposal-migration-${UUID.randomUUID()}.db"
        try {
            SIConnectDb(context,name).use {helper->
                val db=helper.writableDatabase
                db.execSQL("INSERT INTO agents(agent_id,token_sha256,created_at,json) VALUES('disposable','fixture',1,'fixture-preserved')")
                db.execSQL("DROP TABLE proposals");db.version=6
            }
            SIConnectDb(context,name).use {helper->
                val db=helper.writableDatabase
                assertEquals(7,db.version)
                assertEquals("ok",db.rawQuery("PRAGMA quick_check",null).use {it.moveToFirst();it.getString(0)})
                assertEquals("fixture-preserved",db.rawQuery("SELECT json FROM agents WHERE agent_id='disposable'",null).use {it.moveToFirst();it.getString(0)})
                assertEquals(0,db.rawQuery("SELECT count(*) FROM proposals",null).use {it.moveToFirst();it.getInt(0)})
                assertTrue(db.rawQuery("SELECT name FROM sqlite_master WHERE type='index' AND name='proposals_idem'",null).use {it.moveToFirst()})
            }
        } finally {context.deleteDatabase(name)}
    }
}
