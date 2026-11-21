package dev.siconnect.app.transport

import dev.siconnect.app.data.AppJson
import dev.siconnect.core.CapabilityStatus
import kotlinx.serialization.Serializable

@Serializable
internal data class GatewayHello(
    val deviceId: String,
    val capabilities: List<CapabilityStatus>,
    val revokedAgents: List<String>,
    val type: String = "hello",
    val connectionProbe: Boolean = true,
)

/** Each socket owns its own snapshot. Only a successful enqueue suppresses an identical update. */
internal class GatewayHelloPublisher {
    private var last: GatewayHello? = null

    @Synchronized
    fun publish(snapshot: () -> GatewayHello, send: (String) -> Boolean) {
        val next = snapshot()
        if (last == next) return
        if (send(AppJson.encodeToString(GatewayHello.serializer(), next))) last = next
    }
}
