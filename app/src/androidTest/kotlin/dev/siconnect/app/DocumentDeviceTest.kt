package dev.siconnect.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.DocumentRoots
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.app.data.Settings
import dev.siconnect.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Never grants access or chooses a folder. Mutation requires an explicitly selected disposable test root. */
@RunWith(AndroidJUnit4::class)
class DocumentDeviceTest {
    private val g get() = InstrumentationRegistry.getInstrumentation().targetContext.graph
    @Test fun rootsCatalogAndMissingHandleUseRealStore() = runBlocking {
        g.ready.await()
        val record = g.engine.submit(InvocationRequest(agentId = "local-owner", capabilityId = "documents.roots"), Origin.LOCAL_OWNER)
        assertEquals(record.errorMessage, RequestState.SUCCEEDED, record.state)
        val returned = record.result!!.output.jsonObject.getValue("roots").jsonArray.map { it.jsonObject.getValue("rootId").jsonPrimitive.content }.toSet()
        assertEquals(DocumentRoots(g.context, g.db).all().map { it.id }.toSet(), returned)
        assertTrue(g.registry.statuses().filter { it.descriptor.id.startsWith("documents.") }.size == 9)
        for (status in g.registry.statuses().filter { it.descriptor.id.startsWith("documents.") }) {
            Schema.checkSupported(status.descriptor.inputSchema); Schema.checkSupported(requireNotNull(status.descriptor.outputSchema))
        }
    }

    @Test fun selectedTestRootRoundTrip() = runBlocking {
        g.ready.await()
        val id = InstrumentationRegistry.getArguments().getString("documentTestRootId")
        assumeTrue("Requires an owner-selected disposable document test root", id != null)
        val root = DocumentRoots(g.context, g.db).get(id!!)
        assumeTrue("Test root must be explicitly named SIConnect test...", root != null && root.label.startsWith("SIConnect test", true))
        val access = DocumentRoots(g.context, g.db).access(root!!)
        assumeTrue("Test root needs actual persisted read/write access", access.read && access.write && access.providerAvailable)
        val agent="document-fixture:${UUID.randomUUID()}"
        val writes=listOf("documents.create","documents.write","documents.rename","documents.delete")
        // Authorization is limited to this explicitly selected disposable root; no owner grant changes.
        g.agents.put(AgentRecord(AgentGrant(agent,"Disposable document fixture",scopes=listOf("documents.*"),
            standing=writes.map {StandingGrant(it,mapOf("rootId" to JsonPrimitive(id)),RiskClass.MODERATE_IMPACT)},
            expiresAtMs=System.currentTimeMillis()+300_000),Settings.sha256Hex(Settings.randomToken("test_")),System.currentTimeMillis(),tokenHint="test only"))
        val original = "fixture-${UUID.randomUUID()}.txt"; val renamed = "renamed-${UUID.randomUUID()}.txt"
        var current: String? = null
        suspend fun call(cap: String, path: String = "", extra: JsonObjectBuilder.() -> Unit = {}): RequestRecord = g.engine.submit(InvocationRequest(agentId = agent, capabilityId = cap,
            arguments = buildJsonObject { put("rootId", id); put("path", path); extra() }), Origin.REMOTE)
        fun success(record: RequestRecord): JsonObject { assertEquals(record.errorMessage, RequestState.SUCCEEDED, record.state); return record.result!!.output.jsonObject }
        try {
            val made = success(call("documents.create") { put("name", original); put("mimeType", "text/plain") })
            current = made.getValue("document").jsonObject.getValue("path").jsonPrimitive.content
            val written = success(call("documents.write", current!!) { put("content", "Disposable SAF fixture"); put("overwrite", true) })
            assertEquals("Disposable SAF fixture", success(call("documents.read", current!!) { put("encoding", "utf8") }).getValue("content").jsonPrimitive.content)
            assertEquals(written["sha256"], success(call("documents.hash", current!!))["sha256"])
            val moved = success(call("documents.rename", current!!) { put("name", renamed) })
            current = moved.getValue("document").jsonObject.getValue("path").jsonPrimitive.content
            success(call("documents.metadata", current!!))
            success(call("documents.delete", current!!)); current = null
            assertEquals("DOCUMENT_NOT_FOUND", call("documents.metadata", renamed).errorCode)
            assertEquals("INVALID_DOCUMENT_PATH", call("documents.read", "../outside").errorCode)
        } finally {
            // Confined to the exact file created by this test; no recursive cleanup or owner files.
            try {current?.let { call("documents.delete", it) }} finally {g.agents.delete(agent)}
        }
    }
}
