package dev.siconnect.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillsTest {
    private val registry = CapabilityRegistry()
    private val read = CountingCapability("device.status", RiskClass.READ_ONLY).also(registry::register)
    private val notify = CountingCapability("notifications.post", RiskClass.LOW_IMPACT).also(registry::register)
    private val shell = CountingCapability("shell.execute", RiskClass.HIGH_IMPACT, shell = true).also(registry::register)
    private val store = MemoryStore()
    private val grants = mapOf("a" to AgentGrant("a", "A", autoApproveUpTo = RiskClass.LOW_IMPACT))
    private val engine = ExecutionEngine("d", registry, PolicyEngine(), store, {}, RecordingApprovals(), { grants[it] },
        { DeviceControls() }, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    private val ctx = ExecutionContext("skillreq", "trace", "a", "d")

    private val def = SkillJson.parse("""
        {"id":"test.threshold","version":1,"description":"notify when n >= threshold",
         "inputs":{"type":"object","properties":{"threshold":{"type":"integer","default":2}},"additionalProperties":false},
         "steps":[
           {"id":"probe","capability":"device.status","args":{"value":"x"}},
           {"id":"warn","capability":"notifications.post","args":{"value":"n=${'$'}{steps.probe.output.n}"},
            "when":{"op":"gte","left":"${'$'}{steps.probe.output.n}","right":"${'$'}{input.threshold}"}}
         ],
         "output":{"warned":"${'$'}{steps.warn.state}"}}
    """.trimIndent())

    @Test fun validDefinitionPasses() = assertEquals(emptyList(), SkillValidator.validate(def, registry))

    @Test fun conditionSkipsStepBelowThreshold() = runBlocking {
        val r = SkillRunner(engine).run(def, JsonObject(emptyMap()), ctx, Origin.REMOTE)
        assertTrue(r.succeeded)
        assertEquals("SKIPPED", r.steps[1].state)
        assertEquals(0, notify.executions.get())
    }

    @Test fun conditionRunsStepAtThresholdWithInterpolatedArgs() = runBlocking {
        val r = SkillRunner(engine).run(def, buildJsonObject { put("threshold", 1) }, ctx, Origin.REMOTE)
        assertEquals("SUCCEEDED", r.steps[1].state)
        assertEquals(JsonPrimitive("SUCCEEDED"), (r.output as JsonObject)["warned"])
        assertEquals("\"n=1\"", (r.steps[1].output as JsonObject)["echo"].toString())
    }

    @Test fun skillStepsCannotEscalate() = runBlocking {
        val s = SkillDefinition("test.shell", 1, "shell", steps = listOf(SkillStep("run", "shell.execute", buildJsonObject { put("value", "id") })))
        val r = SkillRunner(engine).run(s, JsonObject(emptyMap()), ctx, Origin.REMOTE)
        assertFalse(r.succeeded)
        assertEquals("AWAITING_APPROVAL", r.steps[0].state)
        assertEquals(0, shell.executions.get())
    }

    @Test fun validatorRejectsForwardReferencesAndUnknownCapabilities() {
        val bad = SkillDefinition("test.bad", 1, "bad", steps = listOf(
            SkillStep("a", "device.status", buildJsonObject { put("value", "${'$'}{steps.b.output}") }),
            SkillStep("b", "nope.missing"),
        ))
        val errors = SkillValidator.validate(bad, registry)
        assertTrue(errors.any { "later/unknown step 'b'" in it })
        assertTrue(errors.any { "unknown capability" in it })
    }

    @Test fun missingDataNeverSatisfiesThreshold() {
        val c = buildJsonObject { put("op", "gte"); put("left", "${'$'}{input.missing}"); put("right", 0) }
        assertFalse(Conditions.eval(c, mapOf("input" to JsonObject(emptyMap()))))
    }

    @Test fun canonicalEncodingIsOrderIndependent() {
        val a = buildJsonObject { put("b", 1); put("a", "x") }
        val b = buildJsonObject { put("a", "x"); put("b", 1) }
        assertEquals(Canonical.encode(a), Canonical.encode(b))
        assertEquals("{\"a\":\"x\",\"b\":1}", Canonical.encode(a))
    }
}
