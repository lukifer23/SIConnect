package dev.siconnect.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SkillRetentionTest {
    @Test fun compactWorkflowAcceptsOnlyItsOriginalIdentityAndNeverRunsAgain() = runTest {
        val registry = CapabilityRegistry()
        val capability = CountingCapability("device.read", RiskClass.READ_ONLY).also(registry::register)
        val engine = ExecutionEngine("d",registry,PolicyEngine(),MemoryStore(),{},RecordingApprovals(),
            {AgentGrant(it,it,scopes=listOf("*"))},{DeviceControls()},backgroundScope)
        var saved: SkillExecution? = null
        val store = object : SkillRunStore {
            override fun insert(run: SkillExecution): Boolean { if(saved!=null) return false; saved=run; return true }
            override fun get(runId: String) = saved?.takeIf { it.runId==runId }
            override fun update(run: SkillExecution, expectedRevision: Long): Boolean {
                if(saved?.revision!=expectedRevision) return false
                saved=run; return true
            }
            override fun active() = emptyList<SkillExecution>()
        }
        val runner = DurableSkillRunner(engine,store)
        val def = SkillDefinition("test.retention",1,"private workflow",inputs=objectSchema {string("secret","input")},
            steps=listOf(SkillStep("read","device.read",buildJsonObject {put("value","private argument")})),
            output=buildJsonObject {put("secret","private output")})
        val input = buildJsonObject {put("secret","private input")}
        val ctx = ExecutionContext("run","trace","a","d")
        val finished = runner.start(def,input,ctx,Origin.REMOTE)
        assertEquals(SkillState.SUCCEEDED,finished.state)
        // Also migrate a legacy record without a saved fingerprint.
        saved=finished.copy(operationFingerprint="").compactPayload()
        assertTrue(saved!!.payloadExpired); assertTrue(saved!!.inputs.isEmpty())
        assertTrue(saved!!.definition.steps.isEmpty()); assertEquals(JsonNull,saved!!.output)
        assertEquals(JsonNull,saved!!.steps.single().output)
        assertEquals(saved,runner.start(def,input,ctx,Origin.REMOTE))
        assertEquals(1,capability.executions.get())
        assertFailsWith<IllegalArgumentException> {runner.start(def.copy(description="different"),input,ctx,Origin.REMOTE)}
        assertFailsWith<IllegalArgumentException> {runner.start(def,buildJsonObject {put("secret","changed")},ctx,Origin.REMOTE)}
        assertFailsWith<IllegalArgumentException> {runner.start(def,input,ctx.copy(deviceId="another-device"),Origin.REMOTE)}
        assertEquals(1,capability.executions.get())
    }
}
