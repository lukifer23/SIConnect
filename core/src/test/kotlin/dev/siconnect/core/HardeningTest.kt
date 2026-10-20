package dev.siconnect.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class HardeningTest {
    @Test fun durableExactApprovalsBindArgumentsVersionBackendAndCurrentAuthority() {
        val descriptor=CapabilityDescriptor("shell.execute",1,"Shell","test",objectSchema(),backend=BackendKind.ADB_SERVICE,risk=RiskClass.HIGH_IMPACT)
        val original=args("command" to "id", "cwd" to "/")
        val assessment=RiskAssessment(RiskClass.HIGH_IMPACT,"Shell",shellCommand="id")
        val saved=StandingGrant("shell.execute",maxRisk=RiskClass.HIGH_IMPACT,actionHash=Canonical.actionHash("shell.execute",1,original),backend=BackendKind.ADB_SERVICE)
        val grant=AgentGrant("a","Bot",scopes=listOf("shell.*"),standing=listOf(saved))
        fun decision(g:AgentGrant=grant,d:CapabilityDescriptor=descriptor,a:JsonObject=original,c:DeviceControls=DeviceControls(),risk:RiskClass=RiskClass.HIGH_IMPACT)=
            PolicyEngine().evaluate(g,d,a,assessment.copy(risk=risk),Availability.IMPLEMENTED,c,Origin.REMOTE,100)
        assertEquals(PolicyOutcome.ALLOW,decision().outcome)
        assertEquals(PolicyOutcome.REQUIRE_APPROVAL,decision(a=args("command" to "id", "cwd" to "/data/local/tmp")).outcome)
        assertEquals(PolicyOutcome.REQUIRE_APPROVAL,decision(d=descriptor.copy(version=2)).outcome)
        assertEquals(PolicyOutcome.REQUIRE_APPROVAL,decision(d=descriptor.copy(backend=BackendKind.SHIZUKU)).outcome)
        assertEquals(PolicyOutcome.DENY,decision(g=grant.copy(revoked=true)).outcome)
        assertEquals(PolicyOutcome.DENY,decision(g=grant.copy(scopes=emptyList())).outcome)
        assertEquals(PolicyOutcome.DENY,decision(c=DeviceControls(remoteExecutionEnabled=false)).outcome)
        assertEquals(PolicyOutcome.REQUIRE_APPROVAL,decision(g=grant.copy(shellMode=ShellMode.BROAD),risk=RiskClass.CRITICAL).outcome)
        val restored=SkillJson.json.decodeFromString(AgentGrant.serializer(),SkillJson.json.encodeToString(AgentGrant.serializer(),grant))
        assertEquals(PolicyOutcome.ALLOW,decision(g=restored).outcome)
    }
    @Test fun pendingApprovalRechecksEveryCurrentEligibilityRule() = runBlocking {
        for (change in listOf("revoked", "expired", "scope", "denylist", "stop", "privilege", "permission", "version")) {
            val store = MemoryStore(); val registry = CapabilityRegistry(); val clock = FakeClock()
            var grant = AgentGrant("a", "Agent", scopes = listOf("*"))
            var controls = DeviceControls(); var available = Availability.IMPLEMENTED; var version = 1; var executions = 0
            registry.register(object : Capability {
                override val descriptor get() = CapabilityDescriptor("apps.remove", version, "Remove", "test", objectSchema(), backend = BackendKind.SHIZUKU, risk = RiskClass.HIGH_IMPACT)
                override fun availability() = available
                override suspend fun execute(args: JsonObject, ctx: ExecutionContext): CapabilityResult { executions++; return CapabilityResult(JsonNull, BackendKind.SHIZUKU) }
            })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val engine = ExecutionEngine("d", registry, PolicyEngine(), store, {}, RecordingApprovals(), { grant }, { controls }, scope, clock)
            val r = engine.submit(InvocationRequest(agentId = "a", capabilityId = "apps.remove"))
            assertEquals(RequestState.AWAITING_APPROVAL, r.state)
            when (change) {
                "revoked" -> grant = grant.copy(revoked = true)
                "expired" -> grant = grant.copy(expiresAtMs = clock.now)
                "scope" -> grant = grant.copy(scopes = emptyList())
                "denylist" -> grant = grant.copy(denied = listOf("apps.*"))
                "stop" -> controls = controls.copy(remoteExecutionEnabled = false)
                "privilege" -> controls = controls.copy(privilegedEnabled = false)
                "permission" -> available = Availability.PERMISSION_REQUIRED
                "version" -> version++
            }
            assertEquals(RequestState.FAILED, engine.resolveApproval(r.requestId, true, "owner", r.argsHash).state, change)
            assertEquals(0, executions, change); scope.cancel()
        }
    }

    @Test fun concurrentDuplicatesAndApprovalsExecuteOnlyOnceAndConflictsAreExplicit() = runBlocking {
        val store = MemoryStore(); val registry = CapabilityRegistry()
        val cap = CountingCapability("device.status", RiskClass.READ_ONLY).also(registry::register)
        val write = CountingCapability("apps.remove", RiskClass.HIGH_IMPACT).also(registry::register)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ExecutionEngine("d", registry, PolicyEngine(), store, {}, RecordingApprovals(),
            { AgentGrant(it, it, scopes = listOf("*")) }, { DeviceControls() }, scope)
        val req = InvocationRequest(agentId = "a", capabilityId = "device.status", idempotencyKey = "key")
        (1..100).map { async(Dispatchers.Default) { engine.submit(req.copy(requestId = "r$it")) } }.awaitAll()
        assertEquals(1, cap.executions.get())
        val e = assertFailsWith<CapabilityException> { engine.submit(req.copy(arguments = args("value" to "changed"))) }
        assertEquals("IDEMPOTENCY_CONFLICT", e.code)
        val pending = engine.submit(InvocationRequest(agentId = "a", capabilityId = "apps.remove"))
        (1..100).map { async(Dispatchers.Default) { engine.resolveApproval(pending.requestId, true, "owner", pending.argsHash) } }.awaitAll()
        assertEquals(1, write.executions.get()); scope.cancel()
    }

    @Test fun malformedSchemasAndDeepUnknownPropertiesAreRejected() {
        for (s in listOf("""{"type":"object","required":"x"}""", """{"type":"wat"}""", """{"type":"string","minLength":-1}""", """{"type":"integer","minimum":10,"maximum":1}""", """{"type":"integer","default":"oops"}"""))
            assertFailsWith<IllegalArgumentException> { Schema.checkSupported(Json.parseToJsonElement(s).jsonObject) }
        var deep: JsonElement = JsonNull
        repeat(30) { deep = buildJsonObject { put("x", deep) } }
        assertTrue(Schema.validate(objectSchema(), deep).isNotEmpty())
    }

    @Test fun durableFourStepSkillResumesAfterApprovalAndRecreation() = runBlocking {
        val requestStore = MemoryStore(); val registry = CapabilityRegistry()
        val read = CountingCapability("device.status", RiskClass.READ_ONLY).also(registry::register)
        val write = CountingCapability("apps.remove", RiskClass.HIGH_IMPACT).also(registry::register)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ExecutionEngine("d", registry, PolicyEngine(), requestStore, {}, RecordingApprovals(),
            { AgentGrant(it, it, scopes = listOf("*")) }, { DeviceControls() }, scope)
        val runs = object : SkillRunStore {
            var r: SkillExecution? = null
            override fun insert(run: SkillExecution): Boolean { if (r != null) return false; r = run; return true }
            override fun get(runId: String) = r
            @Synchronized override fun update(run: SkillExecution, expectedRevision: Long): Boolean { if (r?.revision != expectedRevision) return false; r = run; return true }
            override fun active() = listOfNotNull(r)
        }
        val def = SkillDefinition("test.four", 1, "four", steps = listOf(SkillStep("one", "device.status"), SkillStep("two", "apps.remove"), SkillStep("three", "device.status"), SkillStep("four", "device.status")))
        val waiting = DurableSkillRunner(engine, runs).start(def, JsonObject(emptyMap()), ExecutionContext("run", "trace", "a", "d"), Origin.REMOTE)
        assertEquals(1, waiting.cursor); assertEquals(SkillState.AWAITING_APPROVAL, waiting.state)
        engine.recover()
        val p = requestStore.get(waiting.pendingRequestId!!)!!
        engine.resolveApproval(p.requestId, true, "owner", p.argsHash)
        val recreated = DurableSkillRunner(engine, runs)
        recreated.recover()
        assertEquals(SkillState.SUCCEEDED, runs.r!!.state)
        recreated.resume("run")
        assertEquals(3, read.executions.get()); assertEquals(1, write.executions.get()); scope.cancel()
    }
}
