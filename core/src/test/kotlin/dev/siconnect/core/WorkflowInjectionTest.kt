package dev.siconnect.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Untrusted earlier-step output must never become shell syntax in a later step. */
class WorkflowInjectionTest {
    private fun registry() = CapabilityRegistry().apply {
        register(CountingCapability("device.read", RiskClass.READ_ONLY))
        register(CountingCapability("shell.run", RiskClass.LOW_IMPACT, shell = true))
    }
    private fun step(id: String, cap: String, value: String) = SkillStep(id, cap, buildJsonObject { put("value", value) })

    @Test fun installRejectsStepOutputInterpolatedIntoACommand() {
        val registry = registry()
        val unsafe = SkillDefinition("test.unsafe", 1, "test", steps = listOf(
            step("read", "device.read", "x"), step("run", "shell.run", "echo \${steps.read.output.echo}")))
        assertTrue(SkillValidator.validate(unsafe, registry).any { "cannot be interpolated into a command" in it })
        val whole = unsafe.copy(steps = listOf(step("read", "device.read", "x"), step("run", "shell.run", "\${steps.read.output.echo}")))
        assertTrue(SkillValidator.validate(whole, registry).any { "cannot be interpolated into a command" in it })
        // Invoker input and typed (non-command) steps remain allowed.
        val safe = unsafe.copy(steps = listOf(step("read", "device.read", "x"), step("run", "shell.run", "echo \${input.name}"),
            step("echo", "device.read", "\${steps.read.output.echo}")))
        assertEquals(emptyList(), SkillValidator.validate(safe, registry))
    }

    @Test fun nestedWorkflowGuardResolvesToolNameAliases() {
        val registry = registry().apply { register(CountingCapability("skills.run", RiskClass.LOW_IMPACT)) }
        val nested = SkillDefinition("test.nested", 1, "test", steps = listOf(SkillStep("inner", "skills_run")))
        assertTrue(SkillValidator.validate(nested, registry).any { "nested skill execution" in it })
    }

    @Test fun runnerRefusesPreviouslyInstalledUnsafeDefinitionsWithoutExecuting() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = registry()
            val shell = registry.get("shell.run") as CountingCapability
            val engine = ExecutionEngine("device", registry, PolicyEngine(), MemoryStore(), {}, RecordingApprovals(),
                { AgentGrant(it, it, scopes = listOf("*"), autoApproveUpTo = RiskClass.LOW_IMPACT) }, { DeviceControls() }, scope)
            val legacy = SkillDefinition("test.legacy", 1, "test", steps = listOf(
                step("read", "device.read", "; rm -rf /"), step("run", "shell.run", "echo \${steps.read.output.echo}")))
            val result = SkillRunner(engine).run(legacy, JsonObject(emptyMap()), ExecutionContext("run", "trace", "a", "d"), Origin.REMOTE)
            assertFalse(result.succeeded)
            assertTrue(result.error!!.startsWith("UNSAFE_COMMAND_INTERPOLATION"))
            assertEquals(0, shell.executions.get())
        } finally { scope.cancel() }
    }
}
