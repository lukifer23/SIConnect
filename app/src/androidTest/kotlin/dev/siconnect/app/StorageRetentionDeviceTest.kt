package dev.siconnect.app

import android.content.ContentValues
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.*
import dev.siconnect.app.automation.Automation
import dev.siconnect.app.capabilities.Reminder
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** All compaction targets disposable databases. Owner retention preferences/history are untouched. */
class StorageRetentionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(block: (SIConnectDb) -> Unit) {
        val name="storage-retention-${UUID.randomUUID()}.db"
        try { SIConnectDb(context,name).use(block) } finally { context.deleteDatabase(name) }
    }
    private val now get()=System.currentTimeMillis()
    private fun record(id:String,state:RequestState=RequestState.SUCCEEDED,parent:String?=null,output:JsonElement=JsonNull)=
        RequestRecord(id,id,"fixture-agent","fixture-device","device.status",1,buildJsonObject {put("private","fixture")},
            "fixture-hash",id,"REMOTE",state,createdAtMs=now-1000,updatedAtMs=now-500,expiresAtMs=now+1000,
            parentRequestId=parent,operationFingerprint="fixture-fingerprint-$id",result=CapabilityResult(output,BackendKind.NATIVE))
    private fun workflow(id:String,state:SkillState=SkillState.SUCCEEDED,children:List<String> = emptyList(),output:JsonElement=JsonNull)=
        SkillExecution(id,SkillDefinition("fixture.workflow",1,"Private definition",steps=listOf(SkillStep("probe","device.status"))),
            buildJsonObject {put("private","input")},"fixture-agent","fixture-device",id,"REMOTE",now-1000,now+1000,
            state=state,cursor=1,steps=children.mapIndexed {i,child->StepOutcome("step$i","SUCCEEDED",false,child,output)},output=output)
    private fun retention(db:SIConnectDb,days:Int?=7)=StorageRetention(db,{RetentionPolicy(days)},{})
    private fun automation(db:SIConnectDb,id:String,state:String="SUCCEEDED",payload:String="private-snapshot") {
        db.writableDatabase.insertOrThrow("automation_runs",null,ContentValues().apply {
            put("run_id",id);put("automation_id","fixture");put("slot_at",id.hashCode().toLong());put("started_at",now-1000)
            put("finished_at",now-500);put("updated_at",now-500);put("state",state)
            put("json",payload);put("automation_json",payload);put("skill_json",payload)
        })
    }

    @Test fun defaultPolicyKeepsPayloadsAndEachEnabledPassIsBounded() = isolated { db ->
        val requests=SqlRequestStore(db)
        repeat(4) {assertTrue(requests.insert(record("r$it")))}
        val later=now+8*86_400_000L
        assertEquals(0,retention(db,null).maintain(later,1).total)
        assertEquals(4L,retention(db).summary().fullRequests)
        assertEquals(1,retention(db).maintain(later,1).requests)
        assertEquals(3L,retention(db).summary().fullRequests)
        assertEquals(1L,retention(db).summary().compactRequests)
        assertEquals(0,retention(db,null).maintain(later,1).total)
    }

    @Test fun workflowChildrenAndSnapshotsExpireWhileActiveAndUncertainEvidenceSurvives() = isolated { db ->
        val requests=SqlRequestStore(db);val runs=SqlSkillRunStore(db)
        requests.insert(record("completed-child",parent="completed"));requests.insert(record("completed"))
        runs.insert(workflow("completed",children=listOf("completed-child")))
        automation(db,"completed")
        requests.insert(record("active-child",parent="active"));runs.insert(workflow("active",SkillState.RUNNING,listOf("active-child")))
        requests.insert(record("uncertain-child",RequestState.UNKNOWN_OUTCOME,"uncertain"))
        runs.insert(workflow("uncertain",children=listOf("uncertain-child")))
        runs.insert(workflow("unknown-run",SkillState.UNKNOWN_OUTCOME));automation(db,"unknown-run","UNKNOWN_OUTCOME")
        val maintenance=retention(db)
        repeat(3) { maintenance.maintain(now+8*86_400_000L,1) }
        assertTrue(runs.get("completed")!!.payloadExpired)
        assertTrue(requests.get("completed-child")!!.payloadExpired);assertTrue(requests.get("completed")!!.payloadExpired)
        assertFalse(runs.get("active")!!.payloadExpired);assertFalse(requests.get("active-child")!!.payloadExpired)
        assertFalse(runs.get("uncertain")!!.payloadExpired);assertFalse(requests.get("uncertain-child")!!.payloadExpired)
        assertFalse(runs.get("unknown-run")!!.payloadExpired)
        db.readableDatabase.rawQuery("SELECT json,automation_json,skill_json,payload_expired FROM automation_runs WHERE run_id='completed'",null).use {
            assertTrue(it.moveToFirst());assertTrue(it.isNull(0));assertTrue(it.isNull(1));assertTrue(it.isNull(2));assertEquals(1,it.getInt(3))
        }
        assertEquals(0,db.readableDatabase.rawQuery("SELECT count(*) FROM step_results WHERE run_id='completed'",null).use {it.moveToFirst();it.getInt(0)})
    }

    @Test fun compactHistoryKeepsKeysetPaginationAndAgentStateFilters() = isolated {db->
        val requests=SqlRequestStore(db);val timestamp=now-500
        repeat(6) {i->requests.insert(record("history-$i",if(i%2==0) RequestState.FAILED else RequestState.SUCCEEDED)
            .copy(updatedAtMs=timestamp,agentId=if(i%2==0) "a" else "b"))}
        assertEquals(2,requests.prune(now,2))
        for(newest in listOf(false,true)) {
            val page1=requests.historyPage(2,newest=newest)
            val page2=requests.historyPage(2,cursor=page1.last(),newest=newest)
            val page3=requests.historyPage(2,cursor=page2.last(),newest=newest)
            val expected=(0..5).map {"history-$it"}.let {if(newest) it.reversed() else it}
            assertEquals(expected,(page1+page2+page3).map {it.requestId})
            assertEquals(2,(page1+page2+page3).count {it.payloadExpired})
        }
        val filtered=requests.historyPage(agentId="a",states=setOf(RequestState.FAILED))
        assertEquals(listOf("history-4","history-2","history-0"),filtered.map {it.requestId})
        assertTrue(filtered.last().payloadExpired)
    }

    @Test fun incompleteLegacyLinksDoNotStarveOtherWorkflowCompaction() = isolated {db->
        val runs=SqlSkillRunStore(db)
        runs.insert(workflow("a-missing",children=listOf("absent")))
        runs.insert(workflow("b-safe"))
        db.writableDatabase.execSQL("UPDATE skill_runs SET updated_at=1")
        val maintenance=retention(db)
        assertEquals(0,maintenance.maintain(now,1).workflows)
        assertEquals(1,maintenance.maintain(now,1).workflows)
        assertFalse(runs.get("a-missing")!!.payloadExpired);assertTrue(runs.get("b-safe")!!.payloadExpired)
    }

    @Test fun ordinaryCompletedRecipeCompactsLeavesBeforeParentsWithoutLosingRetryIdentities() = isolated {db->
        val requests=SqlRequestStore(db)
        val original=listOf(record("recipe"),record("recipe:open",parent="recipe"),record("recipe:inspect",parent="recipe:open"))
        original.forEach {assertTrue(requests.insert(it))}
        assertEquals(1,requests.prune(now,1))
        assertTrue(requests.get("recipe:inspect")!!.payloadExpired)
        assertFalse(requests.get("recipe:open")!!.payloadExpired);assertFalse(requests.get("recipe")!!.payloadExpired)
        assertEquals(1,requests.prune(now,1));assertTrue(requests.get("recipe:open")!!.payloadExpired)
        assertFalse(requests.get("recipe")!!.payloadExpired)
        assertEquals(1,requests.prune(now,1));assertEquals(0,requests.prune(now,1))
        original.forEach {old->
            val retained=requireNotNull(requests.get(old.requestId))
            assertTrue(retained.payloadExpired);assertNull(retained.result)
            assertEquals(old.state,retained.state);assertEquals(old.argsHash,retained.argsHash)
            assertEquals(old.operationFingerprint,retained.operationFingerprint);assertEquals(old.parentRequestId,retained.parentRequestId)
            assertEquals(retained,requests.findByIdempotencyKey(old.agentId,requireNotNull(old.idempotencyKey)))
            assertFalse(requests.insert(old));assertFalse(requests.insert(old.copy(requestId="different-${old.requestId}")))
        }
    }

    @Test fun completedChildCannotExpireThroughActiveUnknownProposalOrUnresolvedAncestry() = isolated {db->
        val requests=SqlRequestStore(db)
        fun chain(root:String,state:RequestState) {
            assertTrue(requests.insert(record(root,state)))
            assertTrue(requests.insert(record("$root:middle",parent=root)))
            assertTrue(requests.insert(record("$root:leaf",parent="$root:middle")))
        }
        chain("active-root",RequestState.EXECUTING);chain("unknown-root",RequestState.UNKNOWN_OUTCOME)
        chain("proposal-root",RequestState.SUCCEEDED)
        SqlProposalStore(db).insert(ActionProposal(proposalId="active-ancestor-proposal",agentId="fixture-agent",deviceId="fixture-device",
            createdAtMs=now-1000,expiresAtMs=now+60000,title="Ancestry fixture",summary="Protect active link",reason="Fixture",
            sourceType="fixture",sourceReference=null,capabilityId="device.status",capabilityVersion=1,arguments=buildJsonObject {},
            assessment=RiskAssessment(RiskClass.READ_ONLY,"Fixture"),actionHash="fixture",idempotencyKey="ancestor-key",
            state=ProposalState.ACCEPTED,linkedRequestId="proposal-root"))
        assertTrue(requests.insert(record("missing-middle",parent="missing-parent")))
        assertTrue(requests.insert(record("missing-leaf",parent="missing-middle")))
        SqlSkillRunStore(db).insert(workflow("unexpired-workflow"))
        assertTrue(requests.insert(record("workflow-middle",parent="unexpired-workflow")))
        assertTrue(requests.insert(record("workflow-leaf",parent="workflow-middle")))
        assertEquals(0,requests.prune(now,100))
        for(id in listOf("active-root:leaf","unknown-root:leaf","proposal-root:leaf","missing-leaf","workflow-leaf"))
            assertFalse(id,requests.get(id)!!.payloadExpired)
        // A protected old leaf cannot prevent an unrelated, proven completed recipe expiring.
        assertTrue(requests.insert(record("safe-parent")));assertTrue(requests.insert(record("safe-leaf",parent="safe-parent")))
        assertEquals(1,requests.prune(now,1));assertTrue(requests.get("safe-leaf")!!.payloadExpired)
    }

    @Test fun reminderReconciliationProtectsDeliveryAndOldIdentitiesNeverExpire() = isolated { db ->
        val requests=SqlRequestStore(db)
        val r=record("reminder:fixture",RequestState.FAILED).copy(errorCode="ORIGINAL_FAILURE",errorMessage="Original evidence",
            createdAtMs=now-800*86_400_000L,updatedAtMs=now-799*86_400_000L)
        requests.insert(r)
        db.writableDatabase.execSQL("INSERT INTO reminders(reminder_id,fire_at,state,created_by,json,revision) VALUES('fixture',1,'FIRING','fixture','{}',0)")
        db.writableDatabase.execSQL("INSERT INTO notification_dedup VALUES('fixture','key','reminder:fixture')")
        val maintenance=retention(db)
        assertEquals(0,maintenance.maintain(now+8*86_400_000L).requests)
        db.writableDatabase.execSQL("UPDATE reminders SET state='FAILED' WHERE reminder_id='fixture'")
        assertEquals(1,maintenance.maintain(now+8*86_400_000L).requests)
        maintenance.maintain(now+800*86_400_000L)
        val compact=requests.get(r.requestId)!!
        assertTrue(compact.payloadExpired);assertEquals("ORIGINAL_FAILURE",compact.errorCode)
        assertEquals("Original evidence",compact.errorMessage);assertEquals(r.argsHash,compact.argsHash)
        assertFalse(requests.insert(r));assertFalse(requests.insert(r.copy(requestId="new-id")))
        assertEquals(1L,maintenance.summary().notificationClaims)
    }

    @Test fun largeUnicodeRecordsAndNestedWorkflowResultsCrossCursorWindows() = isolated { db ->
        val text="🙂é漢".repeat(400_000)
        val output=buildJsonObject {put("large",text)}
        val request=record("large",output=output)
        val requests=SqlRequestStore(db);requests.insert(request)
        assertEquals(request,requests.get(request.requestId))
        assertEquals(request,requests.findByIdempotencyKey(request.agentId,request.idempotencyKey!!))
        assertEquals(request,requests.recent(1).single())
        val execution=workflow("large-workflow",children=listOf(request.requestId),output=output)
        val runs=SqlSkillRunStore(db);runs.insert(execution)
        assertEquals(execution,runs.get(execution.runId))
        // Active recovery reads the same oversized record without selecting its full JSON row.
        assertTrue(runs.update(execution.copy(state=SkillState.READY,revision=1),0))
        assertEquals(execution.copy(state=SkillState.READY,revision=1),runs.active().single())
        assertTrue(runs.update(execution.copy(revision=2),1))
        assertEquals(1,retention(db).maintain(now+8*86_400_000L).workflows)
        assertTrue(runs.get(execution.runId)!!.payloadExpired)
    }

    @Test fun engineRetriesAfterCompactionCannotReexecuteOrChangeTheOriginalAction() = isolated { db -> runBlocking {
        val registry=CapabilityRegistry();var executions=0
        registry.register(object:Capability {
            override val descriptor=CapabilityDescriptor("fixture.write",title="Fixture",description="Disposable counter",inputSchema=objectSchema {string("value","input")},backend=BackendKind.NATIVE,risk=RiskClass.LOW_IMPACT)
            override fun availability()=Availability.IMPLEMENTED
            override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {executions++;return CapabilityResult(buildJsonObject {put("count",executions)},BackendKind.NATIVE)}
        })
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val engine=ExecutionEngine("fixture-device",registry,PolicyEngine(),SqlRequestStore(db),{},object:ApprovalGate {
                override fun approvalRequested(record:RequestRecord) {}
                override fun approvalClosed(record:RequestRecord) {}
            },{AgentGrant(it,it,scopes=listOf("*"),autoApproveUpTo=RiskClass.LOW_IMPACT)},{DeviceControls()},scope)
            val request=InvocationRequest(requestId="retry",agentId="fixture",capabilityId="fixture.write",idempotencyKey="retry-key",arguments=buildJsonObject {put("value","original")})
            assertEquals(RequestState.SUCCEEDED,engine.submit(request).state)
            assertEquals(1,retention(db).maintain(now+8*86_400_000L).requests)
            assertTrue(engine.submit(request.copy(requestId="another-id")).payloadExpired)
            try {engine.submit(request.copy(arguments=buildJsonObject {put("value","different")}));fail("Changed operation accepted")}
            catch(e:CapabilityException) {assertEquals("IDEMPOTENCY_CONFLICT",e.code)}
            assertEquals(1,executions)
        } finally {scope.cancel()}
    } }

    @Test fun versionTenUpgradePreservesOwnerRowsAndGivesLegacyRunsAFreshInterval() {
        val name="retention-v10-${UUID.randomUUID()}.db"
        val schedule=Automation("legacy-schedule","fixture.workflow",now+60000,enabled=false,label="Original schedule",revision=3,
            inputs=buildJsonObject {put("private","original action input")})
        val reminder=Reminder("legacy-reminder",now+60000,"Original reminder","Original body","fixture-agent",state="SUCCEEDED",revision=4)
        val scheduleJson=AppJson.encodeToString(Automation.serializer(),schedule)
        val reminderJson=AppJson.encodeToString(Reminder.serializer(),reminder)
        try {
            SIConnectDb(context,name).use { db ->
                SqlSkillRunStore(db).insert(workflow("legacy"))
                SqlRequestStore(db).insert(record("legacy-tombstone"));SqlRequestStore(db).prune(now)
                db.writableDatabase.execSQL("INSERT INTO agents VALUES('fixture','hash',1,NULL,'preserved')")
                db.writableDatabase.execSQL("INSERT INTO automations(automation_id,enabled,next_run_at,owner,json,revision) VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>(schedule.id,0,schedule.nextAtMs,"local-automation",scheduleJson,schedule.revision))
                db.writableDatabase.execSQL("INSERT INTO reminders(reminder_id,fire_at,state,created_by,json,revision) VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>(reminder.id,reminder.fireAtMs,reminder.state,reminder.agentId,reminderJson,reminder.revision))
                db.writableDatabase.removeExecutionRetentionSchema();db.writableDatabase.version=10
                assertFalse(db.readableDatabase.rawQuery("PRAGMA table_info(automations)",null).use {c->buildList {while(c.moveToNext()) add(c.getString(1))}}.contains("label"))
                assertFalse(db.readableDatabase.rawQuery("PRAGMA table_info(reminders)",null).use {c->buildList {while(c.moveToNext()) add(c.getString(1))}}.contains("title"))
            }
            val before=now
            repeat(2) {SIConnectDb(context,name).use {db->
                assertEquals(SIConnectDb.VERSION,db.readableDatabase.version)
                assertEquals("preserved",db.readableDatabase.rawQuery("SELECT json FROM agents",null).use {it.moveToFirst();it.getString(0)})
                assertEquals(scheduleJson,db.readableDatabase.rawQuery("SELECT json FROM automations WHERE automation_id=?",arrayOf(schedule.id)).use {it.moveToFirst();it.getString(0)})
                assertEquals(reminderJson,db.readableDatabase.rawQuery("SELECT json FROM reminders WHERE reminder_id=?",arrayOf(reminder.id)).use {it.moveToFirst();it.getString(0)})
                assertEquals(schedule.label,ScheduleProjectionStore(db).automationPage().single().label)
                assertEquals(reminder.title,ScheduleProjectionStore(db).reminderPage(states=null).single().title)
                assertTrue(db.readableDatabase.rawQuery("SELECT updated_at FROM skill_runs",null).use {it.moveToFirst();it.getLong(0)>=before})
                assertEquals(0,retention(db).maintain(now).total)
                assertFalse(SqlSkillRunStore(db).get("legacy")!!.payloadExpired)
                assertTrue(SqlRequestStore(db).historyPage().single().payloadExpired)
                assertEquals("legacy-tombstone",SqlRequestStore(db).historyPage().single().requestId)
            }}
        } finally {context.deleteDatabase(name)}
    }

    @Test fun lateAlarmReturnsTheCompactedSlotWithoutRestoringPayloadOrExecuting() = runBlocking {
        val g=context.graph;g.ready.await()
        val id="retention-fixture-${UUID.randomUUID()}";val slot=now-1000
        val runId=dev.siconnect.app.automation.Scheduler.runId(id,slot)
        try {
            g.db.writableDatabase.insertOrThrow("automation_runs",null,ContentValues().apply {
                put("run_id",runId);put("automation_id",id);put("slot_at",slot);put("started_at",slot)
                put("finished_at",slot+1);put("updated_at",slot+1);put("state","SUCCEEDED");put("payload_expired",1)
            })
            assertEquals(dev.siconnect.app.automation.AutomationRunState.SUCCEEDED,g.scheduler.fire(id,slot))
            assertNull(g.skillRuns.get(runId));assertNull(g.requests.get(runId))
            g.db.readableDatabase.rawQuery("SELECT json,automation_json,skill_json,updated_at FROM automation_runs WHERE run_id=?",arrayOf(runId)).use {
                assertTrue(it.moveToFirst());assertTrue(it.isNull(0));assertTrue(it.isNull(1));assertTrue(it.isNull(2));assertEquals(slot+1,it.getLong(3))
            }
        } finally {g.db.writableDatabase.delete("automation_runs","run_id=?",arrayOf(runId))}
    }

    @Test fun repeatedPayloadsBecomeReusablePagesWithoutLosingIdentities() = isolated { db ->
        val requests=SqlRequestStore(db);val runs=SqlSkillRunStore(db)
        val output=buildJsonObject {put("fixture","x".repeat(64*1024))}
        repeat(48) {requests.insert(record("growth-$it",output=output))}
        repeat(12) {i->val id="workflow-growth-$i";runs.insert(workflow(id,output=output));automation(db,id, payload=output.toString())}
        val maintenance=retention(db);val before=maintenance.summary()
        repeat(8) {maintenance.maintain(now+8*86_400_000L,10)}
        val after=maintenance.summary()
        assertEquals(48L,after.compactRequests);assertEquals(0L,after.fullRequests)
        assertEquals(12L,after.compactWorkflows)
        assertTrue(requests.get("growth-0")!!.payloadExpired)
        assertEquals("ok",db.readableDatabase.rawQuery("PRAGMA quick_check",null).use {it.moveToFirst();it.getString(0)})
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
            putString("retentionGrowth","fixture only: allocatedBefore=${before.allocatedBytes}, allocatedAfter=${after.allocatedBytes}, reusableBefore=${before.reusableBytes}, reusableAfter=${after.reusableBytes}, requests=48, workflows=12, autoVacuum="+
                db.readableDatabase.rawQuery("PRAGMA auto_vacuum",null).use {it.moveToFirst();it.getInt(0)})
        })
        // Android may enable automatic page reclamation. Either file shrinkage or a larger
        // freelist is valid; verify live pages decreased rather than assuming one SQLite mode.
        assertTrue(after.allocatedBytes-after.reusableBytes < before.allocatedBytes-before.reusableBytes)
    }
}
