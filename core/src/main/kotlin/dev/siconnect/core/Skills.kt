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
    private val REF = Regex("""\$\{([^}]+)}""")

    /** Returns validation errors; capabilities must exist in [registry] at install time. */
    fun validate(def: SkillDefinition, registry: CapabilityRegistry): List<String> {
        val errors = mutableListOf<String>()
        if (!CapabilityRegistry.ID_PATTERN.matches(def.id)) errors += "invalid skill id"
        if (def.version < 1) errors += "version must be >= 1"
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

class SkillRunner(private val engine: ExecutionEngine, private val clock: Clock = Clock { System.currentTimeMillis() }) {

    suspend fun run(def: SkillDefinition, inputs: JsonObject, ctx: ExecutionContext, origin: Origin): SkillRunResult {
        val inputErrors = Schema.validate(def.inputs, inputs)
        if (inputErrors.isNotEmpty()) return SkillRunResult(def.id, def.version, false, emptyList(), JsonNull, "invalid inputs: $inputErrors")
        val scope = mutableMapOf<String, JsonElement>("input" to withDefaults(def.inputs, inputs),
            "now" to buildJsonObject { put("epoch_ms", clock.nowMs()) })
        val stepResults = linkedMapOf<String, JsonObject>()
        val outcomes = mutableListOf<StepOutcome>()
        for (step in def.steps) {
            scope["steps"] = JsonObject(stepResults)
            val proceed = step.`when`?.let { Conditions.eval(it, scope) } ?: true
            if (!proceed) {
                outcomes += StepOutcome(step.id, "SKIPPED", skipped = true)
                stepResults[step.id] = buildJsonObject { put("state", "SKIPPED"); put("skipped", true) }
                continue
            }
            val args = Templates.resolve(step.args, scope) as JsonObject
            var record = engine.submit(InvocationRequest(
                requestId = "${ctx.requestId}:${step.id}", traceId = ctx.traceId, agentId = ctx.agentId,
                capabilityId = step.capability, arguments = args, parentRequestId = ctx.requestId,
            ), origin)
            if (record.state == RequestState.AWAITING_APPROVAL && step.approvalWaitMs > 0)
                record = engine.await(record.requestId, step.approvalWaitMs) ?: record
            val output = record.result?.output ?: JsonNull
            val ok = record.state == RequestState.SUCCEEDED
            outcomes += StepOutcome(step.id, record.state.name, false, record.requestId, output,
                record.errorMessage?.let { "${record.errorCode}: $it" })
            stepResults[step.id] = buildJsonObject {
                put("state", record.state.name); put("skipped", false); put("output", output)
            }
            if (!ok && step.onError == "fail") {
                return SkillRunResult(def.id, def.version, false, outcomes, JsonNull,
                    "step '${step.id}' ended ${record.state}: ${record.errorMessage ?: ""}".trim())
            }
        }
        scope["steps"] = JsonObject(stepResults)
        return SkillRunResult(def.id, def.version, true, outcomes, Templates.resolve(def.output, scope))
    }

    private fun withDefaults(schema: JsonObject, inputs: JsonObject): JsonObject {
        val props = schema["properties"] as? JsonObject ?: return inputs
        val merged = inputs.toMutableMap()
        props.forEach { (k, v) -> (v as? JsonObject)?.get("default")?.let { d -> merged.putIfAbsent(k, d) } }
        return JsonObject(merged)
    }
}

object Templates {
    private val WHOLE = Regex("""^\$\{([^}]+)}$""")
    private val PART = Regex("""\$\{([^}]+)}""")

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
