package dev.siconnect.app.transport

import dev.siconnect.app.data.AppJson
import dev.siconnect.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GatewayHelloTest {
    private val descriptor = CapabilityDescriptor("device.status", title = "Device status", description = "Read state",
        inputSchema = JsonObject(emptyMap()), backend = BackendKind.NATIVE, risk = RiskClass.READ_ONLY)
    private val status = CapabilityStatus(descriptor, Availability.VERIFIED)
    private val hello = GatewayHello("phone", listOf(status), emptyList())

    @Test fun onlyChangedCatalogsAreEnqueuedAndEveryNewSocketAnnounces() {
        val sent = mutableListOf<String>()
        val publisher = GatewayHelloPublisher()
        publisher.publish({ hello }, sent::add)
        publisher.publish({ hello.copy(capabilities = listOf(status.copy())) }, sent::add)
        val denied = hello.copy(capabilities = listOf(status.copy(availability = Availability.PERMISSION_REQUIRED)))
        publisher.publish({ denied }, sent::add)
        val revoked = denied.copy(revokedAgents = listOf("agent"))
        publisher.publish({ revoked }, sent::add)
        GatewayHelloPublisher().publish({ revoked }, sent::add)

        assertEquals(4, sent.size)
        val expected = buildJsonObject {
            put("type", "hello")
            put("deviceId", "phone")
            put("connectionProbe", true)
            put("scopedDiscovery", true)
            put("revocationReplay", true)
            put("capabilities", JsonArray(listOf(AppJson.encodeToJsonElement(CapabilityStatus.serializer(), status))))
            put("revokedAgents", JsonArray(emptyList()))
        }
        assertEquals(expected, AppJson.parseToJsonElement(sent.first()))
        assertEquals(revoked, AppJson.decodeFromString(GatewayHello.serializer(), sent[2]))
        assertEquals(sent[2], sent[3])
    }

    @Test fun rejectedEnqueueCanRetryOnSameSocket() {
        var attempts = 0
        val publisher = GatewayHelloPublisher()
        repeat(3) { publisher.publish({ hello }) { ++attempts >= 2 } }
        assertEquals(2, attempts)
    }
}
