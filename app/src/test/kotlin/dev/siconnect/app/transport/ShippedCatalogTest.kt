package dev.siconnect.app.transport

import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.registerPhoneCatalog
import dev.siconnect.core.*
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File

class ShippedCatalogTest {
    @Test fun exportRegisteredPhoneCatalogForWorkerHandshake() {
        val graph = Mockito.mock(RuntimeGraph::class.java, Mockito.RETURNS_DEEP_STUBS)
        val output = File(System.getProperty("siconnect.catalog.output"))
        Mockito.`when`(graph.context.filesDir).thenReturn(output.parentFile)
        val registry = CapabilityRegistry()
        Mockito.`when`(graph.registry).thenReturn(registry)
        Mockito.`when`(graph.privileged.kind).thenReturn(BackendKind.SHIZUKU)
        registerPhoneCatalog(graph)
        val statuses = registry.all().map { CapabilityStatus(it.descriptor, Availability.UNKNOWN) }
        assertTrue(statuses.any { it.descriptor.id == "device.battery" })
        output.parentFile.mkdirs()
        GatewayHelloPublisher().publish({ GatewayHello("test-device", statuses, emptyList()) }) { frame ->
            output.writeText(frame)
            true
        }
    }
}
