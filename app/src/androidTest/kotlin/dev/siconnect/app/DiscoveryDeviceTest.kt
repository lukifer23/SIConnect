package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.capabilities.DocumentOperations
import dev.siconnect.app.data.DocumentRoots
import dev.siconnect.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Measures the production discovery path against actual saved grants/providers, without mutations. */
class DiscoveryDeviceTest {
    @Test fun documentCatalogSharesProviderProbesAndUiMetadataCache()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val g=context.graph;g.ready.await()
        val roots=DocumentRoots(context,g.db);val rootCount=roots.all().size;var probes=0
        val operations=DocumentOperations(context.contentResolver,roots) {root->probes++;roots.access(root)}
        val registry=CapabilityRegistry();operations.capabilities().forEach(registry::register)
        val first=registry.statuses(5_000)
        assertEquals(9,first.size)
        assertTrue("At most one access probe per selected root across all nine tools",probes<=rootCount)
        if(rootCount>0) assertTrue(probes>0)
        val measured=probes
        repeat(20) {assertEquals(first,registry.statuses(5_000))}
        assertEquals("Ordinary metadata refreshes must reuse the measurement",measured,probes)
        registry.invalidateDiscovery();registry.statuses(5_000)
        assertEquals("Explicit access/backend refresh remeasures the root snapshot",measured*2,probes)
        // Fresh execution availability must use current grants rather than the UI cache.
        val capability=operations.capabilities().first {it.descriptor.id=="documents.list"}
        val actual=capability.availability()
        assertEquals(first.first {it.descriptor.id=="documents.list"}.availability,actual)
    }
    @Test fun nativeRuntimeWorksWithoutTheDisposableCompanion()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val g=context.graph;g.ready.await()
        assertFalse("Disposable companion must be absent",runCatching {context.packageManager.getPackageInfo("dev.siconnect.notificationfixture",0)}.isSuccess)
        val r=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="device.status"),Origin.LOCAL_OWNER)
        assertEquals(r.errorMessage,RequestState.SUCCEEDED,r.state)
        assertEquals(85,g.registry.all().size)
    }
}
