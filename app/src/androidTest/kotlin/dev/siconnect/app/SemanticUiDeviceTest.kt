package dev.siconnect.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.accessibility.SemanticAccessibilityService
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicit opt-in only: launches a disposable debug fixture, never a real conversation or approval. */
@RunWith(AndroidJUnit4::class)
class SemanticUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val g get() = instrumentation.targetContext.graph
    @Before fun preserveOwnerEnabledServices() {
        // Default UiAutomation suppresses real accessibility services, invalidating this integration test.
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        runBlocking {
            g.ready.await()
            val expected = android.content.ComponentName(g.context, SemanticAccessibilityService::class.java)
            val enabled = android.provider.Settings.Secure.getString(g.context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').mapNotNull(android.content.ComponentName::unflattenFromString).any { it == expected }
            if (enabled) withTimeoutOrNull(15000) { while (SemanticAccessibilityService.instance == null) delay(50) }
        }
    }
    @Test fun nativeServiceStateAndSchemas() = runBlocking {
        g.ready.await()
        val status = g.engine.submit(InvocationRequest(agentId = "local-owner", capabilityId = "ui.status"), Origin.LOCAL_OWNER)
        assertEquals(status.errorMessage, RequestState.SUCCEEDED, status.state)
        assertEquals(SemanticAccessibilityService.instance != null, status.result!!.output.jsonObject.getValue("connected").jsonPrimitive.boolean)
        for (id in listOf("ui.inspect", "ui.find", "ui.wait_for", "ui.click", "ui.set_text", "ui.scroll", "ui.back", "ui.home")) {
            val capability = requireNotNull(g.registry.get(id)); Schema.checkSupported(capability.descriptor.inputSchema); Schema.checkSupported(requireNotNull(capability.descriptor.outputSchema))
            assertEquals(if (SemanticAccessibilityService.instance != null) Availability.IMPLEMENTED else Availability.PERMISSION_REQUIRED, capability.availability())
        }
    }

    @Test fun semanticFixtureActionsAndStaleReferenceRejection() = runBlocking {
        assumeTrue("Requires coordinated explicit semanticUiFixture=true", InstrumentationRegistry.getArguments().getString("semanticUiFixture") == "true")
        g.ready.await()
        assumeTrue("Owner-enabled accessibility service must actually bind after instrumentation startup", SemanticAccessibilityService.instance != null)
        assumeTrue("Do not disturb an existing screen lease", g.ui.status() == null)
        assumeTrue("Owner must have remote execution enabled", g.settings.controls.value.remoteExecutionEnabled)
        val target = g.context.packageName
        val agent = "semantic-fixture:${UUID.randomUUID()}"
        g.agents.put(AgentRecord(AgentGrant(agent, "Disposable semantic UI fixture", scopes = listOf("ui.*"), autoApproveUpTo = RiskClass.HIGH_IMPACT), "", System.currentTimeMillis(), tokenHint = "test-only identity; no credential"))
        val intent = Intent().setClassName(target, "dev.siconnect.app.accessibility.SemanticUiFixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var lease: String? = null
        suspend fun call(id: String, block: JsonObjectBuilder.() -> Unit = {}) = g.engine.submit(InvocationRequest(agentId = agent, capabilityId = id, arguments = buildJsonObject(block)), Origin.REMOTE)
        fun success(record: RequestRecord): JsonObject { assertEquals(record.errorMessage, RequestState.SUCCEEDED, record.state); return record.result!!.output.jsonObject }
        suspend fun find(block: JsonObjectBuilder.() -> Unit): JsonObject = success(call("ui.find") { put("packageName", target); block() })
        fun JsonObject.actionArgs(builder: JsonObjectBuilder, ref: JsonObject) = with(builder) {
            put("packageName", target); put("leaseId", lease!!); put("windowId", this@actionArgs.getValue("windowId")); put("snapshotId", this@actionArgs.getValue("snapshotId")); put("nodeRef", ref.getValue("nodeRef"))
        }
        try {
            ActivityScenario.launch<android.app.Activity>(intent).use { scenario ->
                withTimeout(10000) {
                    while (true) {
                        var focused = false; scenario.onActivity { focused = it.hasWindowFocus() }
                        if (focused) break
                        delay(50)
                    }
                }
                instrumentation.waitForIdleSync()
                lease = success(call("ui.lease_acquire") { put("targetPackage", target); put("ttlMs", 60000) }).getValue("leaseId").jsonPrimitive.content
                val inspected = success(call("ui.inspect") { put("packageName", target); put("limit", 200) })
                assertTrue(inspected.getValue("nodes").jsonArray.none { it.jsonObject["text"]?.jsonPrimitive?.content?.contains("fixture-secret-value") == true })
                assertTrue(inspected.getValue("nodes").jsonArray.none { it.jsonObject["text"]?.jsonPrimitive?.content?.contains("fixture-protected-descendant") == true })
                assertTrue(inspected.getValue("observedAtMs").jsonPrimitive.long > 0)
                assertTrue(inspected.getValue("scanDurationMs").jsonPrimitive.long >= 0)
                val bounded = success(call("ui.inspect") { put("packageName", target); put("maxScanNodes", 1) })
                assertEquals(1, bounded.getValue("scannedNodes").jsonPrimitive.int)
                assertTrue(bounded.getValue("scanIncomplete").jsonPrimitive.boolean)
                val field = find { put("description", "Fixture entry"); put("editable", true) }
                val node = field.getValue("nodes").jsonArray.single().jsonObject
                val edited = success(call("ui.set_text") { field.actionArgs(this, node); put("text", "Disposable semantic content") })
                assertTrue(edited.getValue("textReadbackVerified").jsonPrimitive.boolean)
                val stale = call("ui.set_text") { field.actionArgs(this, node); put("text", "Must never replace newer state") }
                assertEquals("STALE_NODE_REFERENCE", stale.errorCode)
                val readback = success(call("ui.wait_for") { put("packageName", target); put("text", "Disposable semantic content"); put("timeoutMs", 5000) })
                assertTrue(readback.getValue("matched").jsonPrimitive.boolean)
                val button = find { put("text", "Complete fixture"); put("clickable", true) }
                val clickable = button.getValue("nodes").jsonArray.single().jsonObject
                success(call("ui.click") { button.actionArgs(this, clickable) })
                val completed = success(call("ui.wait_for") { put("packageName", target); put("text", "Fixture complete"); put("timeoutMs", 5000) })
                assertTrue(completed.getValue("matched").jsonPrimitive.boolean)
                val checkbox = find { put("description", "Fixture checkbox"); put("checkable", true); put("checked", false) }
                val checkboxNode = checkbox.getValue("nodes").jsonArray.single().jsonObject
                assertTrue(checkboxNode.getValue("checkable").jsonPrimitive.boolean)
                success(call("ui.click") { checkbox.actionArgs(this, checkboxNode) })
                val checked = success(call("ui.wait_for") { put("packageName", target); put("description", "Fixture checkbox"); put("checked", true); put("timeoutMs", 5000) })
                assertTrue(checked.getValue("matched").jsonPrimitive.boolean)
                val initialRows = find { put("text", "Disposable fixture row "); put("contains", true); put("limit", 100) }.getValue("nodes").jsonArray
                val lastVisible = initialRows.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content?.removePrefix("Disposable fixture row ")?.toIntOrNull() }.maxOrNull()
                assertNotNull("The disposable scroll viewport must be visible", lastVisible)
                val scroll = find { put("description", "Fixture scroll region"); put("scrollable", true) }
                val container = scroll.getValue("nodes").jsonArray.single().jsonObject
                success(call("ui.scroll") { scroll.actionArgs(this, container); put("direction", "forward") })
                val nextRow = success(call("ui.wait_for") { put("packageName", target); put("text", "Disposable fixture row ${lastVisible!! + 1}"); put("timeoutMs", 5000) })
                assertTrue("Scroll must reveal the next fixture row", nextRow.getValue("matched").jsonPrimitive.boolean)
                val wrongPackage = call("ui.find") { put("packageName", "com.example.not.foreground"); put("text", "Fixture complete") }
                assertEquals("TARGET_NOT_FOREGROUND", wrongPackage.errorCode)
                val protected = call("ui.find") { put("packageName", "com.android.systemui"); put("text", "Not inspected") }
                assertEquals("PROTECTED_UI", protected.errorCode)
                val missing = success(call("ui.wait_for") { put("packageName", target); put("text", "Fixture impossible state"); put("timeoutMs", 200) })
                assertTrue(missing.getValue("timedOut").jsonPrimitive.boolean)
                val waitingId = "semantic-wait-fixture:${UUID.randomUUID()}"
                val pending = async { g.engine.submit(InvocationRequest(requestId = waitingId, agentId = agent, capabilityId = "ui.wait_for", arguments = buildJsonObject {
                    put("packageName", target); put("text", "Fixture state that never appears"); put("timeoutMs", 60000)
                }), Origin.REMOTE) }
                try {
                    withTimeout(5000) { while (g.requests.get(waitingId)?.state != RequestState.EXECUTING) delay(10) }
                    assertEquals(waitingId, g.engine.cancel(waitingId, agent)?.requestId)
                    assertEquals(RequestState.CANCELLED, g.engine.await(waitingId, 5000)?.state)
                    assertEquals(RequestState.CANCELLED, withTimeout(5000) { pending.await() }.state)
                    assertNotNull(SemanticAccessibilityService.instance)
                } finally {
                    if (!pending.isCompleted) { g.engine.cancel(waitingId, agent); pending.cancelAndJoin() }
                }
            }
        } finally {
            try { lease?.let { g.ui.release(ExecutionContext("semantic-fixture-cleanup", "semantic-fixture-cleanup", agent, g.settings.deviceId), it) } }
            finally { g.agents.delete(agent) }
        }
    }
}
