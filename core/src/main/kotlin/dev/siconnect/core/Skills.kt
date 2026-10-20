package dev.siconnect.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * A skill is a declarative, versioned workflow over existing capabilities. Every step is submitted to
 * the [ExecutionEngine] under the invoking agent's identity, so a skill can never do more than the
 * agent could do by calling the capabilities directly.
 */
@Serializable
data class SkillDefinition(
    val id: String,
    val version: Int,
    val description: String,
    val inputs: JsonObject = objectSchema(),
    val steps: List<SkillStep>,
    /** Output template; values may reference inputs and step results. */
    val output: JsonObject = JsonObject(emptyMap()),
    val timeoutMs: Long = 120_000,
)

@Serializable
data class SkillStep(
    val id: String,
    val capability: String,
    val args: JsonObject = JsonObject(emptyMap()),
    /** Optional condition; the step is skipped when it evaluates false. */
    val `when`: JsonObject? = null,
    /** "fail" (default) stops the skill on a non-success state; "continue" records and proceeds. */
    val onError: String = "fail",
    /** How long to wait if this step needs owner approval. */
    val approvalWaitMs: Long = 0,
)

@Serializable
data class StepOutcome(val id: String, val state: String, val skipped: Boolean, val requestId: String? = null,
                       val output: JsonElement = JsonNull, val error: String? = null)

@Serializable
data class SkillRunResult(val skillId: String, val version: Int, val succeeded: Boolean,
                          val steps: List<StepOutcome>, val output: JsonElement, val error: String? = null)

object SkillJson {
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    fun parse(text: String): SkillDefinition = json.decodeFromString(SkillDefinition.serializer(), text)
}

object SkillValidator {
    private val STEP_ID = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val REF = Regex("""\$\{([^}]+)\}""")

    /** Returns validation errors; capabilities must exist in [registry] at install time. */
    fun validate(def: SkillDefinition, registry: CapabilityRegistry): List<String> {
        val errors = mutableListOf<String>()
        if (!CapabilityRegistry.ID_PATTERN.matches(def.id)) errors += "invalid skill id"
        if (def.version < 1) errors += "version must be >= 1"
        if (def.timeoutMs !in 1..86_400_000) errors += "invalid skill timeout"
        if (def.steps.isEmpty()) errors += "skill has no steps"
        if (def.steps.size > 32) errors += "too many steps (max 32)"
        runCatching { Schema.checkSupported(def.inputs) }.onFailure { errors += "inputs: ${it.message}" }
        val seen = mutableSetOf<String>()
        for (s in def.steps) {
            if (!STEP_ID.matches(s.id)) errors += "step '${s.id}': invalid id"
            if (!seen.add(s.id)) errors += "step '${s.id}': duplicate id"
            if (s.capability == "skills.run") errors += "step '${s.id}': nested skill execution is not allowed"
            if (registry.get(s.capability) == null) errors += "step '${s.id}': unknown capability '${s.capability}'"
            if (s.onError !in setOf("fail", "continue")) errors += "step '${s.id}': onError must be fail|continue"
            refs(s.args).plus(s.`when`?.let(::refs) ?: emptyList()).forEach { ref ->
                val root = ref.substringBefore('.')
                if (root == "steps") {
                    val target = ref.removePrefix("steps.").substringBefore('.')
                    if (target !in seen || target == s.id) errors += "step '${s.id}': reference to later/unknown step '$target'"
                } else if (root !in setOf("input", "now")) errors += "step '${s.id}': unknown reference root '$root'"
            }
        }
        return errors
    }

    /** Highest baseline risk among steps; used to set the install-time risk of a skill. */
    fun maxRisk(def: SkillDefinition, registry: CapabilityRegistry): RiskClass =
        def.steps.mapNotNull { registry.get(it.capability)?.descriptor?.risk }.maxOrNull() ?: RiskClass.READ_ONLY

    internal fun refs(e: JsonElement): List<String> = when (e) {
        is JsonPrimitive -> if (e.isString) REF.findAll(e.content).map { it.groupValues[1].trim() }.toList() else emptyList()
        is JsonArray -> e.flatMap(::refs)
        is JsonObject -> e.values.flatMap(::refs)
        else -> emptyList()
    }
}

@Serializable
enum class SkillState { READY, RUNNING, SUCCEEDED, PARTIALLY_SUCCEEDED, FAILED, AWAITING_APPROVAL, CANCELLED, UNKNOWN_OUTCOME }

@Serializable
data class SkillExecution(
    val runId: String, val definition: SkillDefinition, val inputs: JsonObject,
    val agentId: String, val deviceId: String, val traceId: String, val origin: String,
    val startedAtMs: Long, val deadlineMs: Long,
    val state: SkillState = SkillState.READY, val cursor: Int = 0,
    val steps: List<StepOutcome> = emptyList(), val pendingRequestId: String? = null,
    val output: JsonElement = JsonNull, val error: String? = null, val revision: Long = 0,
)

interface SkillRunStore {
    fun insert(run: SkillExecution): Boolean
    fun get(runId: String): SkillExecution?
    fun update(run: SkillExecution, expectedRevision: Long): Boolean
    fun active(): List<SkillExecution>
}

/** Persisted cursor plus stable child request IDs: completed steps are never resubmitted. */
class DurableSkillRunner(private val engine: ExecutionEngine, private val store: SkillRunStore,
                         private val clock: Clock = Clock { System.currentTimeMillis() }) {
    suspend fun start(def: SkillDefinition, inputs: JsonObject, ctx: ExecutionContext, origin: Origin): SkillExecution {
        require(def.timeoutMs in 1..86_400_000) { "Invalid skill timeout" }
        require(Schema.validate(def.inputs, inputs).isEmpty()) { "Invalid skill inputs" }
        val r = SkillExecution(ctx.requestId, def, inputs, ctx.agentId, ctx.deviceId, ctx.traceId, origin.name,
            clock.nowMs(), clock.nowMs() + def.timeoutMs)
        if (!store.insert(r)) {
            val old = requireNotNull(store.get(r.runId))
            require(old.definition == def && old.inputs == inputs && old.agentId == ctx.agentId && old.origin == origin.name) { "Skill run identity conflict" }
        }
        return resume(r.runId)
    }

    private fun save(r: SkillExecution, next: SkillExecution): SkillExecution {
        val n = next.copy(revision = r.revision + 1)
        check(store.update(n, r.revision)) { "Skill cursor changed concurrently" }
        return n
    }

    suspend fun resume(runId: String): SkillExecution {
        var r = requireNotNull(store.get(runId))
        if (r.state !in setOf(SkillState.READY, SkillState.AWAITING_APPROVAL)) return r
        val claim = r.copy(state = SkillState.RUNNING, revision = r.revision + 1)
        if (!store.update(claim, r.revision)) return requireNotNull(store.get(runId))
        r = claim
        try {
            while (r.cursor < r.definition.steps.size) {
                // Cancellation wins over an in-flight cursor update.
                if (store.get(runId)?.state == SkillState.CANCELLED) return requireNotNull(store.get(runId))
                if (clock.nowMs() >= r.deadlineMs) {
                    r.pendingRequestId?.let { engine.cancel(it, "skill timeout") }
                    val unknown = r.pendingRequestId?.let { engine.request(it)?.state } == RequestState.UNKNOWN_OUTCOME
                    return save(r, r.copy(state = if (unknown) SkillState.UNKNOWN_OUTCOME else SkillState.FAILED, error = "SKILL_TIMEOUT"))
                }
                val step = r.definition.steps[r.cursor]
                val values = mutableMapOf<String, JsonElement>("input" to withDefaults(r.definition.inputs, r.inputs),
                    "now" to buildJsonObject { put("epoch_ms", r.startedAtMs) },
                    "steps" to JsonObject(r.steps.associate { it.id to buildJsonObject {
                        put("state", it.state); put("skipped", it.skipped); put("output", it.output)
                    } }))
                if (r.pendingRequestId == null && step.`when`?.let { !Conditions.eval(it, values) } == true) {
                    r = save(r, r.copy(cursor = r.cursor + 1, steps = r.steps + StepOutcome(step.id, "SKIPPED", true)))
                    continue
                }
                val childId = "${r.runId}:${step.id}"
                if (r.pendingRequestId == null) r = save(r, r.copy(pendingRequestId = childId))
                var record = engine.request(childId) ?: engine.submit(InvocationRequest(requestId = childId,
                    traceId = r.traceId, agentId = r.agentId, capabilityId = step.capability,
                    arguments = Templates.resolve(step.args, values) as JsonObject,
                    expiresAtMs = r.deadlineMs, parentRequestId = r.runId), Origin.valueOf(r.origin))
                if (!record.state.isTerminal) {
                    if (step.approvalWaitMs > 0) record = engine.await(childId, minOf(step.approvalWaitMs, r.deadlineMs - clock.nowMs())) ?: record
                    if (!record.state.isTerminal)
                        return save(r, r.copy(state = SkillState.AWAITING_APPROVAL))
                }
                val outcome = StepOutcome(step.id, record.state.name, false, childId, record.result?.output ?: JsonNull, record.errorCode)
                r = save(r, r.copy(cursor = r.cursor + 1, steps = r.steps + outcome, pendingRequestId = null))
                if (record.state == RequestState.UNKNOWN_OUTCOME)
                    return save(r, r.copy(state = SkillState.UNKNOWN_OUTCOME, error = "Step ${step.id} has an uncertain outcome"))
                if (record.state == RequestState.CANCELLED)
                    return save(r, r.copy(state = SkillState.CANCELLED, error = "Step ${step.id} cancelled"))
                if (record.state != RequestState.SUCCEEDED && step.onError == "fail")
                    return save(r, r.copy(state = SkillState.FAILED, error = "Step ${step.id}: ${record.state}"))
            }
            val values = mapOf("input" to withDefaults(r.definition.inputs, r.inputs), "steps" to JsonObject(r.steps.associate {
                it.id to buildJsonObject { put("state", it.state); put("output", it.output); put("skipped", it.skipped) }
            }))
            val partial = r.steps.any { !it.skipped && it.state != RequestState.SUCCEEDED.name }
            return save(r, r.copy(state = if (partial) SkillState.PARTIALLY_SUCCEEDED else SkillState.SUCCEEDED,
                output = Templates.resolve(r.definition.output, values)))
        } catch (e: Exception) {
            if (store.get(runId)?.state == SkillState.CANCELLED) return requireNotNull(store.get(runId))
            // A cursor persistence failure is never retried here: startup recovery inspects child requests.
            throw e
        }
    }

    suspend fun cancel(runId: String): SkillExecution {
        while (true) {
            val r = requireNotNull(store.get(runId))
            if (r.state !in setOf(SkillState.READY, SkillState.RUNNING, SkillState.AWAITING_APPROVAL)) return r
            val n = r.copy(state = SkillState.CANCELLED, revision = r.revision + 1)
            if (store.update(n, r.revision)) {
                r.pendingRequestId?.let { engine.cancel(it, "workflow cancelled") }
                return n
            }
        }
    }

    /** Call only during startup, after engine.recover(), before accepting new work. */
    suspend fun recover() {
        for (r in store.active()) {
            if (r.state == SkillState.RUNNING) save(r, r.copy(state = SkillState.READY))
            resume(r.runId)
        }
    }

    private fun withDefaults(schema: JsonObject, inputs: JsonObject): JsonObject {
        val merged = inputs.toMutableMap()
        (schema["properties"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonObject)?.get("default")?.let { merged.putIfAbsent(k, it) } }
        return JsonObject(merged)
    }
}

/** Compatibility API for callers that don't need continuation. Production uses DurableSkillRunner. */
class SkillRunner(private val engine: ExecutionEngine, private val clock: Clock = Clock { System.currentTimeMillis() }) {
    suspend fun run(def: SkillDefinition, inputs: JsonObject, ctx: ExecutionContext, origin: Origin): SkillRunResult {
        val errors = Schema.validate(def.inputs, inputs)
        if (errors.isNotEmpty()) return SkillRunResult(def.id, def.version, false, emptyList(), JsonNull, "invalid inputs: $errors")
        val transient = object : SkillRunStore {
            private var r: SkillExecution? = null
            override fun insert(run: SkillExecution): Boolean { if (r != null) return false; r = run; return true }
            override fun get(runId: String) = r
            override fun update(run: SkillExecution, expectedRevision: Long): Boolean { if (r?.revision != expectedRevision) return false; r = run; return true }
            override fun active() = listOfNotNull(r)
        }
        val r = DurableSkillRunner(engine, transient, clock).start(def, inputs, ctx, origin)
        return SkillRunResult(def.id, def.version, r.state == SkillState.SUCCEEDED, r.steps +
            listOfNotNull(r.pendingRequestId?.let { StepOutcome(def.steps[r.cursor].id, "AWAITING_APPROVAL", false, it) }), r.output, r.error)
    }
}

object Templates {
    private val WHOLE = Regex("""^\$\{([^}]+)\}$""")
    private val PART = Regex("""\$\{([^}]+)\}""")

    fun resolve(e: JsonElement, scope: Map<String, JsonElement>): JsonElement = when (e) {
        is JsonPrimitive -> if (!e.isString) e else {
            val whole = WHOLE.find(e.content)
            if (whole != null) lookup(whole.groupValues[1].trim(), scope)
            else JsonPrimitive(PART.replace(e.content) { m -> lookup(m.groupValues[1].trim(), scope).asText() })
        }
        is JsonArray -> JsonArray(e.map { resolve(it, scope) })
        is JsonObject -> JsonObject(e.mapValues { resolve(it.value, scope) })
        else -> e
    }

    fun lookup(path: String, scope: Map<String, JsonElement>): JsonElement {
        val parts = path.split('.')
        var cur: JsonElement = scope[parts[0]] ?: return JsonNull
        for (p in parts.drop(1)) {
            cur = when (cur) {
                is JsonObject -> cur[p] ?: return JsonNull
                is JsonArray -> p.toIntOrNull()?.let { cur.getOrNull(it) } ?: return JsonNull
                else -> return JsonNull
            }
        }
        return cur
    }

    private fun JsonElement.asText(): String = when (this) {
        is JsonPrimitive -> content
        is JsonNull -> ""
        else -> toString()
    }
}

/** Condition language: {"op": "gte", "left": ..., "right": ...}, and/or/not with "args", exists with "value". */
object Conditions {
    fun eval(cond: JsonObject, scope: Map<String, JsonElement>): Boolean {
        val op = (cond["op"] as? JsonPrimitive)?.content ?: throw IllegalArgumentException("condition missing op")
        fun v(key: String) = Templates.resolve(cond[key] ?: JsonNull, scope)
        fun subs() = (cond["args"] as? JsonArray)?.map { it as JsonObject } ?: throw IllegalArgumentException("$op needs args")
        return when (op) {
            "and" -> subs().all { eval(it, scope) }
            "or" -> subs().any { eval(it, scope) }
            "not" -> !eval(subs().single(), scope)
            "exists" -> v("value") !is JsonNull
            "truthy" -> v("value").let { it is JsonPrimitive && (it.booleanOrNull == true || (it.doubleOrNull ?: 0.0) != 0.0) }
            "eq" -> v("left") == v("right")
            "ne" -> v("left") != v("right")
            "gt", "gte", "lt", "lte" -> {
                val l = (v("left") as? JsonPrimitive)?.doubleOrNull ?: return false // missing data never satisfies a threshold
                val r = (v("right") as? JsonPrimitive)?.doubleOrNull ?: return false
                when (op) { "gt" -> l > r; "gte" -> l >= r; "lt" -> l < r; else -> l <= r }
            }
            "in" -> (v("right") as? JsonArray)?.contains(v("left")) ?: false
            else -> throw IllegalArgumentException("unknown condition op '$op'")
        }
    }
}
