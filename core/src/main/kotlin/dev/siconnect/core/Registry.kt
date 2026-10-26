package dev.siconnect.core

import kotlinx.serialization.Serializable

@Serializable
data class CapabilityStatus(
    val descriptor: CapabilityDescriptor,
    val availability: Availability,
    val detail: String? = null,
)

/** The single authoritative capability catalog. MCP tool lists are derived from it. */
class CapabilityRegistry(private val nanoTime: () -> Long = System::nanoTime) {
    private val capabilities = linkedMapOf<String, Capability>()
    private data class Measurement(val revision: Long, val measuredAt: Long, val statuses: List<CapabilityStatus>)
    private var revision = 0L
    @Volatile private var measurement: Measurement? = null

    @Synchronized
    fun register(capability: Capability) {
        val d = capability.descriptor
        require(ID_PATTERN.matches(d.id)) { "Invalid capability id '${d.id}'" }
        require(d.id !in capabilities) { "Duplicate capability id '${d.id}'" }
        require(capabilities.values.none { it.descriptor.toolName == d.toolName }) { "Duplicate tool name '${d.toolName}'" }
        require(d.version > 0 && d.timeoutMs in 1..300_000) { "Invalid version or timeout" }
        Schema.checkSupported(d.inputSchema)
        d.outputSchema?.let(Schema::checkSupported)
        capabilities[d.id] = capability
        invalidateDiscovery()
    }

    @Synchronized
    fun get(id: String): Capability? = capabilities[id] ?: capabilities.values.firstOrNull { it.descriptor.toolName == id }

    @Synchronized
    fun all(): List<Capability> = capabilities.values.toList()

    @Synchronized
    fun invalidateDiscovery() { revision++; measurement = null }

    /** A bounded metadata cache, never consulted by execution or authorization. Default is fresh. */
    fun statuses(maxAgeMs: Long = 0): List<CapabilityStatus> {
        require(maxAgeMs in 0..60_000)
        val (entries, epoch) = synchronized(this) {
            val saved = measurement
            if (maxAgeMs > 0 && saved != null && saved.revision == revision &&
                nanoTime() - saved.measuredAt in 0 until maxAgeMs * 1_000_000) return saved.statuses
            capabilities.values.toList() to revision
        }
        // Provider/Binder calls must not hold the registry monitor.
        val snapshot = DiscoverySnapshot()
        val statuses = entries.map {
            val availability = runCatching { it.discoveryAvailability(snapshot) }.getOrDefault(Availability.UNKNOWN)
            CapabilityStatus(it.descriptor, availability, runCatching { it.availabilityDetail(availability) }.getOrNull())
        }
        synchronized(this) {
            // A permission/backend change during the probe must not republish stale cached data.
            if (revision == epoch) measurement = Measurement(epoch, nanoTime(), statuses)
        }
        return statuses
    }

    companion object {
        val ID_PATTERN = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+$")
    }
}

/** One refresh owns this map. Failures are also shared, avoiding repeated unavailable-provider IO. */
class DiscoverySnapshot {
    private val probes = mutableMapOf<Any, Result<Any>>()
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> once(key: Any, probe: () -> T): T =
        probes.getOrPut(key) { runCatching(probe) }.getOrThrow() as T
}
