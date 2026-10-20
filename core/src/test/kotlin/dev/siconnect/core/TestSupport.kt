package dev.siconnect.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** In-memory store with the same CAS + uniqueness semantics as the SQLite store on the device. */
class MemoryStore : RequestStore {
    val records = ConcurrentHashMap<String, RequestRecord>()
    @Synchronized override fun insert(record: RequestRecord): Boolean {
        if (records.containsKey(record.requestId)) return false
        if (record.idempotencyKey != null && findByIdempotencyKey(record.agentId, record.idempotencyKey) != null) return false
        records[record.requestId] = record; return true
    }
    @Synchronized override fun update(record: RequestRecord, expected: RequestState): Boolean {
        if (records[record.requestId]?.state != expected) return false
        records[record.requestId] = record; return true
    }
    override fun get(requestId: String) = records[requestId]
    override fun findByIdempotencyKey(agentId: String, key: String) =
        records.values.firstOrNull { it.agentId == agentId && it.idempotencyKey == key }
    override fun listByStates(states: Set<RequestState>) = records.values.filter { it.state in states }
}

class RecordingApprovals : ApprovalGate {
    val requested = CopyOnWriteArrayList<String>()
    val closed = CopyOnWriteArrayList<String>()
    override fun approvalRequested(record: RequestRecord) { requested += record.requestId }
    override fun approvalClosed(record: RequestRecord) { closed += record.requestId }
}

class FakeClock(var now: Long = 1_000_000) : Clock { override fun nowMs() = now }

/** Test-only capability: counts executions so tests can assert nothing ran. Never registered in the app. */
class CountingCapability(
    id: String, risk: RiskClass, var available: Availability = Availability.IMPLEMENTED,
    private val shell: Boolean = false, sideEffects: List<String> = emptyList(),
) : Capability {
    val executions = AtomicInteger()
    override val descriptor = CapabilityDescriptor(
        id = id, title = id, description = "test", backend = BackendKind.NATIVE, risk = risk, sideEffects = sideEffects,
        inputSchema = objectSchema { string("value", "a value"); integer("n", "number", min = 0, max = 100) },
    )
    override fun availability() = available
    override fun assess(args: JsonObject) = RiskAssessment(
        risk = descriptor.risk, summary = descriptor.id,
        shellCommand = if (shell) args["value"]?.jsonPrimitive?.content else null,
    )
    override suspend fun execute(args: JsonObject, ctx: ExecutionContext): CapabilityResult {
        executions.incrementAndGet()
        return CapabilityResult(buildJsonObject { put("echo", args["value"] ?: JsonPrimitive("")); put("n", executions.get()) }, BackendKind.NATIVE)
    }
}

fun args(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })
