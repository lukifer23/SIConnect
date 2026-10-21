package dev.siconnect.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.coordination.SqlUiLeaseStore
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.app.data.SIConnectDb
import dev.siconnect.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real registry, policy, engine and SQLite; deliberately no screen navigation or owner approvals. */
@RunWith(AndroidJUnit4::class)
class UiLeaseDeviceTest {
    @Test fun twoDisposableAgentsCoordinateScreenWhileBackgroundRemainsAvailable() = runBlocking {
        val g = InstrumentationRegistry.getInstrumentation().targetContext.graph
        g.ready.await()
        assumeTrue("Do not disturb an owner's current UI workflow", g.ui.status() == null)
        val ids = listOf("lease-test-a-${UUID.randomUUID()}", "lease-test-b-${UUID.randomUUID()}")
        var leaseId: String? = null
        fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { put(it.first, it.second) } }
        suspend fun invoke(agent: String, capability: String, arguments: JsonObject = buildJsonObject {}) =
            g.engine.submit(InvocationRequest(agentId = agent, capabilityId = capability, arguments = arguments), Origin.REMOTE)
        try {
            ids.forEach { id -> g.agents.put(AgentRecord(
                AgentGrant(id, "Disposable lease test", scopes = listOf("ui.*", "apps.launch", "device.status"), autoApproveUpTo = RiskClass.LOW_IMPACT),
                "", System.currentTimeMillis(), tokenHint = "test only; no credential")) }
            val acquired = invoke(ids[0], "ui.lease_acquire", args("targetPackage" to g.context.packageName))
            assertEquals(RequestState.SUCCEEDED, acquired.state)
            leaseId = acquired.result!!.output.jsonObject.getValue("leaseId").jsonPrimitive.content
            SIConnectDb(g.context).use { reopened ->
                assertEquals(leaseId, SqlUiLeaseStore(reopened).get()?.leaseId)
            }
            val blocked = invoke(ids[1], "apps.launch", args("package" to g.context.packageName))
            assertEquals("RESOURCE_BUSY", blocked.errorCode)
            assertNotEquals(RequestState.SUCCEEDED, blocked.state)
            val background = invoke(ids[1], "device.status")
            assertEquals(RequestState.SUCCEEDED, background.state)
            val forged = invoke(ids[1], "ui.lease_release", args("leaseId" to leaseId!!))
            assertEquals("LEASE_NOT_OWNED", forged.errorCode)
            assertEquals(leaseId, g.ui.status()?.leaseId)
            val released = invoke(ids[0], "ui.lease_release", args("leaseId" to leaseId!!))
            assertEquals(RequestState.SUCCEEDED, released.state)
            assertNull(g.ui.status())
            assertTrue(g.events.query(requestId = acquired.requestId).any { it.eventType == "ui.lease.acquired" })
            assertTrue(g.events.query(requestId = blocked.requestId).any { it.errorCode == "RESOURCE_BUSY" })
        } finally {
            leaseId?.let { id -> runCatching { g.ui.release(ExecutionContext("test-cleanup", "test-cleanup", ids[0], g.settings.deviceId), id) } }
            ids.forEach(g.agents::delete)
        }
    }
}
