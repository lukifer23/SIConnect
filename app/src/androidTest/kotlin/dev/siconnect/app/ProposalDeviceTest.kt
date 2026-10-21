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
                assertEquals(SIConnectDb.VERSION,db.version)
                assertEquals("ok",db.rawQuery("PRAGMA quick_check",null).use {it.moveToFirst();it.getString(0)})
                assertEquals("fixture-preserved",db.rawQuery("SELECT json FROM agents WHERE agent_id='disposable'",null).use {it.moveToFirst();it.getString(0)})
                assertEquals(0,db.rawQuery("SELECT count(*) FROM proposals",null).use {it.moveToFirst();it.getInt(0)})
                assertTrue(db.rawQuery("SELECT name FROM sqlite_master WHERE type='index' AND name='proposals_idem'",null).use {it.moveToFirst()})
            }
        } finally {context.deleteDatabase(name)}
    }
    @Test fun schemaSevenUpgradePreservesProposalAndMetadataListsAvoidPrivatePayloadDecoding() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="proposal-v7-${UUID.randomUUID()}.db"
        val proposal=ActionProposal(proposalId="fixture-proposal",agentId="fixture-agent",deviceId="fixture-device",
            createdAtMs=1000,expiresAtMs=5000,title="Disposable proposal",summary="Bounded summary",reason="Private fixture reason",
            sourceType="fixture",sourceReference=null,capabilityId="device.status",capabilityVersion=1,
            arguments=buildJsonObject {},assessment=RiskAssessment(RiskClass.READ_ONLY,"Fixture"),actionHash="fixture",idempotencyKey="fixture-key")
        try {
            SIConnectDb(context,name).use {helper->
                val db=helper.writableDatabase
                db.execSQL("DROP TABLE proposals")
                db.execSQL("CREATE TABLE proposals (proposal_id TEXT PRIMARY KEY,agent_id TEXT NOT NULL,idempotency_key TEXT NOT NULL,created_at INTEGER NOT NULL,state TEXT NOT NULL,revision INTEGER NOT NULL,request_id TEXT,json TEXT NOT NULL)")
                db.execSQL("INSERT INTO proposals VALUES(?,?,?,?,?,?,?,?)",arrayOf<Any?>(proposal.proposalId,proposal.agentId,proposal.idempotencyKey,proposal.createdAtMs,proposal.state.name,0,null,AppJson.encodeToString(proposal)))
                db.version=7
            }
            SIConnectDb(context,name).use {helper->
                val store=SqlProposalStore(helper)
                assertEquals(proposal,store.get(proposal.proposalId))
                assertEquals(proposal.summary(),store.summaries(ProposalQuery(),2000).single())
                // List rendering must remain independent of potentially large/invalid private arguments.
                helper.writableDatabase.execSQL("UPDATE proposals SET json='invalid-private-payload' WHERE proposal_id=?",arrayOf(proposal.proposalId))
                assertEquals(proposal.title,store.summaries(ProposalQuery(),2000).single().title)
                assertEquals(ProposalState.EXPIRED,store.summaries(ProposalQuery(states=setOf(ProposalState.EXPIRED)),6000).single().state)
                assertEquals(0,store.pendingCount(6000));assertEquals(0,store.activeCount(proposal.agentId,6000))
                assertEquals("ok",helper.readableDatabase.rawQuery("PRAGMA quick_check",null).use {it.moveToFirst();it.getString(0)})
            }
        } finally {context.deleteDatabase(name)}
    }

    @Test fun metadataPaginationHandlesEqualTimestampsBothDirectionsAndAgentFilters() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="proposal-pages-${UUID.randomUUID()}.db"
        try {
            SIConnectDb(context,name).use {helper->
                val store=SqlProposalStore(helper)
                repeat(6) {i->store.insert(ActionProposal(proposalId="p$i",agentId=if(i%2==0) "a" else "b",deviceId="test",
                    createdAtMs=1000,expiresAtMs=5000,title="Fixture $i",summary="Summary",reason="Reason",sourceType="fixture",sourceReference=null,
                    capabilityId="device.status",capabilityVersion=1,arguments=buildJsonObject {},assessment=RiskAssessment(RiskClass.READ_ONLY,"Fixture"),actionHash="fixture",idempotencyKey="key$i"))}
                for(oldest in listOf(false,true)) {
                    val first=store.summaries(ProposalQuery(limit=3,oldestFirst=oldest),2000)
                    val second=store.summaries(ProposalQuery(limit=3,cursorMs=first.last().createdAtMs,cursorId=first.last().proposalId,oldestFirst=oldest),2000)
                    val expected=(0..5).map {"p$it"}.let {if(oldest) it else it.reversed()}
                    assertEquals(expected,(first+second).map {it.proposalId})
                }
                assertEquals(listOf("p4","p2","p0"),store.summaries(ProposalQuery(agentId="a"),2000).map {it.proposalId})
                assertEquals(6,store.pendingCount(2000));assertEquals(0,store.pendingCount(6000))
                assertTrue(store.summaries(ProposalQuery(states=setOf(ProposalState.PROPOSED)),6000).isEmpty())
                assertEquals(6,store.summaries(ProposalQuery(states=setOf(ProposalState.EXPIRED)),6000).size)
            }
        } finally {context.deleteDatabase(name)}
    }

}
