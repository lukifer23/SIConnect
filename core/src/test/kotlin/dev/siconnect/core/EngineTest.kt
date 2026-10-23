package dev.siconnect.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngineTest {
    private val store = MemoryStore()
    private val approvals = RecordingApprovals()
    private val clock = FakeClock()
    private val events = mutableListOf<Event>()
    private val registry = CapabilityRegistry()
    private var controls = DeviceControls()
    private val grants = mutableMapOf(
        "reader" to AgentGrant("reader", "Reader"),
        "writer" to AgentGrant("writer", "Writer", scopes = listOf("*"), autoApproveUpTo = RiskClass.MODERATE_IMPACT),
    )
    private val read = CountingCapability("device.status", RiskClass.READ_ONLY).also(registry::register)
    private val write = CountingCapability("apps.uninstall", RiskClass.HIGH_IMPACT, sideEffects = listOf("removes package")).also(registry::register)
    private val shell = CountingCapability("shell.execute", RiskClass.HIGH_IMPACT, shell = true).also(registry::register)

    private val engine = ExecutionEngine(
        "dev1", registry, PolicyEngine(), store, { synchronized(events) { events += it } }, approvals,
        { grants[it] }, { controls }, CoroutineScope(SupervisorJob() + Dispatchers.Default), clock,
    )

    private fun submit(agent: String, cap: String, a: kotlinx.serialization.json.JsonObject = args(), key: String? = null) =
        runBlocking { engine.submit(InvocationRequest(agentId = agent, capabilityId = cap, arguments = a, idempotencyKey = key)) }

    @Test fun readOnlyRunsWithoutApproval() {
        val r = submit("reader", "device.status")
        assertEquals(RequestState.SUCCEEDED, r.state)
        assertEquals(1, read.executions.get())
        assertTrue(approvals.requested.isEmpty())
    }

    @Test fun toolNameResolvesToCapability() {
        assertEquals(RequestState.SUCCEEDED, submit("reader", "device_status").state)
    }

    @Test fun highImpactWaitsForApprovalAndDoesNotExecute() {
        val r = submit("writer", "apps.uninstall", args("value" to "com.example.test"))
        assertEquals(RequestState.AWAITING_APPROVAL, r.state)
        assertEquals(0, write.executions.get())
        assertEquals(listOf(r.requestId), approvals.requested)
    }

    @Test fun approvalExecutesExactlyOnceWithStoredArgs() = runBlocking {
        val r = submit("writer", "apps.uninstall", args("value" to "com.example.test"))
        val done = engine.resolveApproval(r.requestId, true, "owner", r.argsHash)
        assertEquals(RequestState.SUCCEEDED, done.state)
        assertEquals("com.example.test", done.result!!.output.toString().substringAfter("\"echo\":\"").substringBefore('"'))
        // A second approval click is a no-op.
        engine.resolveApproval(r.requestId, true, "owner", r.argsHash)
        assertEquals(1, write.executions.get())
    }

    @Test fun approvalBoundToArgumentHash() = runBlocking {
        val r = submit("writer", "apps.uninstall", args("value" to "a"))
        val other = Canonical.actionHash("apps.uninstall", 1, args("value" to "b"))
        assertFailsWith<IllegalStateException> { engine.resolveApproval(r.requestId, true, "owner", other) }
        assertEquals(0, write.executions.get())
    }

    @Test fun deniedApprovalNeverExecutes() = runBlocking {
        val r = submit("writer", "apps.uninstall", args("value" to "x"))
        assertEquals(RequestState.DENIED, engine.resolveApproval(r.requestId, false, "owner", r.argsHash).state)
        assertEquals(0, write.executions.get())
    }

    @Test fun expiredApprovalNeverExecutes() = runBlocking {
        val r = submit("writer", "apps.uninstall", args("value" to "x"))
        clock.now += 11 * 60_000
        assertEquals(RequestState.EXPIRED, engine.resolveApproval(r.requestId, true, "owner", r.argsHash).state)
        assertEquals(0, write.executions.get())
    }

    @Test fun expiredRequestIsRejectedOnArrival() = runBlocking {
        val r = engine.submit(InvocationRequest(agentId = "reader", capabilityId = "device.status", expiresAtMs = clock.now - 1))
        assertEquals(RequestState.EXPIRED, r.state)
        assertEquals(0, read.executions.get())
    }

    @Test fun idempotencyKeyPreventsDuplicateExecution() {
        val a = submit("reader", "device.status", key = "k1")
        val b = submit("reader", "device.status", key = "k1")
        assertEquals(a.requestId, b.requestId)
        assertEquals(1, read.executions.get())
    }

    @Test fun revokedAgentDenied() {
        grants["reader"] = grants.getValue("reader").copy(revoked = true)
        val r = submit("reader", "device.status")
        assertEquals(RequestState.DENIED, r.state)
        assertEquals("agent_revoked", r.policy!!.rule)
    }

    @Test fun emergencyStopDeniesEverythingRemote() {
        controls = DeviceControls(remoteExecutionEnabled = false)
        assertEquals(RequestState.DENIED, submit("reader", "device.status").state)
        assertEquals(0, read.executions.get())
    }

    @Test fun blockBetweenPolicyEvaluationAndDispatchPreventsExecution() = runBlocking {
        val gated=ExecutionEngine("dev1",registry,PolicyEngine(),store,{event->
            if(event.eventType=="policy.evaluated") controls=controls.copy(remoteExecutionEnabled=false)
        },approvals,{grants[it]},{controls},CoroutineScope(SupervisorJob()+Dispatchers.Default),clock)
        val r=gated.submit(InvocationRequest(agentId="reader",capabilityId="device.status"))
        assertEquals(RequestState.FAILED,r.state)
        assertEquals("POLICY_DENIED",r.errorCode)
        assertEquals(0,read.executions.get())
    }

    @Test fun emergencyStopAfterApprovalBlocksExecution() = runBlocking {
        val r = submit("writer", "apps.uninstall", args("value" to "x"))
        controls = DeviceControls(remoteExecutionEnabled = false)
        assertEquals(RequestState.FAILED, engine.resolveApproval(r.requestId, true, "owner", r.argsHash).state)
        assertEquals(0, write.executions.get())
    }

    @Test fun unavailableCapabilityReportsTruthfully() {
        read.available = Availability.PERMISSION_REQUIRED
        val r = submit("reader", "device.status")
        assertEquals(RequestState.FAILED, r.state)
        assertEquals("UNAVAILABLE", r.errorCode)
    }

    @Test fun invalidArgumentsRejected() {
        val r = submit("reader", "device.status", args("bogus" to "1"))
        assertEquals("INVALID_ARGUMENTS", r.errorCode)
    }

    @Test fun shellRequiresApprovalByDefaultAndRunsUnderBroadGrant() {
        assertEquals(RequestState.AWAITING_APPROVAL, submit("writer", "shell.execute", args("value" to "id")).state)
        grants["writer"] = grants.getValue("writer").copy(shellMode = ShellMode.BROAD)
        assertEquals(RequestState.SUCCEEDED, submit("writer", "shell.execute", args("value" to "id")).state)
    }

    @Test fun shellStandingCommandIsExactMatchOnly() {
        grants["writer"] = grants.getValue("writer").copy(shellMode = ShellMode.STANDING_COMMANDS, shellStandingCommands = listOf("uptime"))
        assertEquals(RequestState.SUCCEEDED, submit("writer", "shell.execute", args("value" to "uptime")).state)
        assertEquals(RequestState.AWAITING_APPROVAL, submit("writer", "shell.execute", args("value" to "uptime; rm -rf /sdcard")).state)
    }

    @Test fun recoveryMarksInterruptedWorkUnknownAndNeverReruns() {
        val now = clock.now
        store.insert(RequestRecord("r1", "r1", "writer", "dev1", "apps.uninstall", 1, args(), "h", null, "REMOTE",
            RequestState.EXECUTING, createdAtMs = now, updatedAtMs = now, expiresAtMs = now + 1000))
        engine.recover()
        assertEquals(RequestState.UNKNOWN_OUTCOME, store.get("r1")!!.state)
        assertEquals(0, write.executions.get())
    }

    @Test fun everyTransitionIsLogged() {
        val r = submit("reader", "device.status")
        val types = events.filter { it.requestId == r.requestId }.map { it.eventType }
        assertEquals(listOf("request.received", "policy.evaluated", "execution.started", "execution.finished"), types)
    }
}
