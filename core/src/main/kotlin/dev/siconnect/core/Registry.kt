package dev.siconnect.core

import kotlinx.serialization.Serializable

@Serializable
data class CapabilityStatus(
    val descriptor: CapabilityDescriptor,
    val availability: Availability,
    val detail: String? = null,
)

/** The single authoritative capability catalog. MCP tool lists are derived from it. */
class CapabilityRegistry {
    private val capabilities = linkedMapOf<String, Capability>()

    @Synchronized
    fun register(capability: Capability) {
        val d = capability.descriptor
        require(ID_PATTERN.matches(d.id)) { "Invalid capability id '${d.id}'" }
        require(d.id !in capabilities) { "Duplicate capability id '${d.id}'" }
        Schema.checkSupported(d.inputSchema)
        capabilities[d.id] = capability
    }

    @Synchronized
    fun get(id: String): Capability? = capabilities[id] ?: capabilities.values.firstOrNull { it.descriptor.toolName == id }

    @Synchronized
    fun all(): List<Capability> = capabilities.values.toList()

    fun statuses(): List<CapabilityStatus> = all().map {
        val availability = runCatching { it.availability() }.getOrDefault(Availability.UNKNOWN)
        CapabilityStatus(it.descriptor, availability, runCatching { it.availabilityDetail() }.getOrNull())
    }

    companion object {
        val ID_PATTERN = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+$")
    }
}
