package dev.siconnect.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class SkillValidationTest {
    private val registry=CapabilityRegistry()
    private val read=CountingCapability("device.read",RiskClass.READ_ONLY).also(registry::register)
    private val definition=SkillDefinition("test.validated",1,"Host fixture",steps=listOf(SkillStep("read","device.read")))

    @Test fun malformedConditionsAreRejectedBeforeInstallation() {
        val invalid=listOf("{}","""{"op":"typo","value":true}""","""{"op":"and","args":[]}""",
            """{"op":"not","args":[{"op":"truthy","value":true},{"op":"truthy","value":false}]}""",
            """{"op":"or","args":[true]}""","""{"op":"eq","left":1}""",
            """{"op":"truthy","value":true,"unexpected":false}""")
        for(text in invalid) {
            val step=definition.steps.single().copy(`when`=Json.parseToJsonElement(text).jsonObject)
            assertTrue(SkillValidator.validate(definition.copy(steps=listOf(step)),registry).isNotEmpty(),text)
        }
        val nested=Json.parseToJsonElement("""{"op":"and","args":[{"op":"exists","value":null},{"op":"not","args":[{"op":"eq","left":1,"right":2}]}]}""").jsonObject
        assertEquals(emptyList(),Conditions.validate(nested))
    }

    @Test fun unknownOutputRootsAndStepReferencesAreRejected() {
        for(ref in listOf("unknown.value","steps.missing.output")) {
            val output=buildJsonObject {put("result","\${$ref}")}
            assertTrue(SkillValidator.validate(definition.copy(output=output),registry).any {it.startsWith("output:")})
        }
        val output=buildJsonObject {put("time","\${now.epoch_ms}");put("result","\${steps.read.output}")}
        assertEquals(emptyList(),SkillValidator.validate(definition.copy(output=output),registry))
    }

    @Test fun approvalWaitMustFitTheWorkflowBudget() {
        for(wait in listOf(-1L,definition.timeoutMs+1))
            assertTrue(SkillValidator.validate(definition.copy(steps=listOf(definition.steps.single().copy(approvalWaitMs=wait))),registry).any {"approval wait" in it})
    }

    @Test fun deeplyNestedTemplatesAreRejectedBeforeRecursiveReferenceScanning() {
        var value:JsonElement=JsonPrimitive("\${input.value}")
        repeat(30) {value=buildJsonObject {put("nested",value)}}
        val deep=value.jsonObject
        assertTrue(SkillValidator.validate(definition.copy(output=deep),registry).any {"payload exceeds limits" in it})
        assertTrue(SkillValidator.validate(definition.copy(steps=listOf(definition.steps.single().copy(args=deep))),registry).any {"payload exceeds limits" in it})
        assertTrue(SkillValidator.validate(definition.copy(steps=listOf(definition.steps.single().copy(`when`=deep))),registry).isNotEmpty())
    }

    @Test fun outputTimeUsesTheSameFrozenStartTimeAsStepTemplates() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val clock=FakeClock()
            val engine=ExecutionEngine("d",registry,PolicyEngine(),MemoryStore(),{},RecordingApprovals(),
                {AgentGrant(it,it,scopes=listOf("*"))},{DeviceControls()},scope,clock)
            val output=buildJsonObject {put("started","\${now.epoch_ms}");put("result","\${steps.read.output.n}")}
            val result=SkillRunner(engine,clock).run(definition.copy(output=output),JsonObject(emptyMap()),ExecutionContext("run","trace","a","d"),Origin.REMOTE)
            assertTrue(result.succeeded);assertEquals(JsonPrimitive(clock.now),result.output.jsonObject["started"])
            assertEquals(JsonPrimitive(1),result.output.jsonObject["result"])
        } finally {scope.cancel()}
    }

    @Test fun legacyInvalidConditionsStopWithoutExecutingOrRetryingSteps() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val engine=ExecutionEngine("d",registry,PolicyEngine(),MemoryStore(),{},RecordingApprovals(),
                {AgentGrant(it,it,scopes=listOf("*"))},{DeviceControls()},scope)
            val invalid=definition.copy(steps=listOf(definition.steps.single().copy(`when`=buildJsonObject {put("op","typo")})))
            val result=SkillRunner(engine).run(invalid,JsonObject(emptyMap()),ExecutionContext("run","trace","a","d"),Origin.REMOTE)
            assertFalse(result.succeeded);assertEquals("INVALID_WORKFLOW_CONDITION",result.error);assertEquals(0,read.executions.get())
        } finally {scope.cancel()}
    }
}
