package dev.siconnect.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Validator for the JSON Schema subset SIConnect emits: type, properties, required,
 * additionalProperties=false, enum, minimum/maximum, minLength/maxLength, items, maxItems.
 * Schemas using other keywords are rejected at registration so nothing is silently unchecked.
 */
object Schema {
    private val SUPPORTED = setOf(
        "type", "properties", "required", "additionalProperties", "enum", "minimum", "maximum",
        "minLength", "maxLength", "items", "minItems", "maxItems", "description", "default",
    )

    /** Global budgets also apply to unconstrained objects and unknown properties. */
    fun checkPayload(value: JsonElement) {
        var nodes = 0
        var chars = 0L
        fun walk(v: JsonElement, depth: Int) {
            require(depth <= 24 && ++nodes <= 10_000) { "JSON complexity limit exceeded" }
            when (v) {
                is JsonObject -> v.forEach { (k, x) -> chars += k.length; walk(x, depth + 1) }
                is JsonArray -> v.forEach { walk(it, depth + 1) }
                is JsonPrimitive -> chars += v.content.length
            }
            require(chars <= 512_000) { "JSON size limit exceeded" }
        }
        walk(value, 0)
    }

    fun checkSupported(schema: JsonObject, path: String = "$") {
        checkPayload(schema)
        fun check(s: JsonObject, p: String) {
            for (k in s.keys) require(k in SUPPORTED) { "Unsupported schema keyword '$k' at $p" }
            val type = (s["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(type in setOf("object", "array", "string", "number", "integer", "boolean", "null")) { "Invalid/missing schema type at $p" }
            s["description"]?.let { require(it is JsonPrimitive && it.isString) }
            s["properties"]?.let { props ->
                require(type == "object" && props is JsonObject) { "properties must be an object" }
                props.forEach { (k, v) -> require(v is JsonObject); check(v, "$p.$k") }
            }
            s["required"]?.let { req ->
                require(type == "object" && req is JsonArray)
                val names = req.map { require(it is JsonPrimitive && it.isString); it.content }
                require(names.distinct().size == names.size && names.all { it in (s["properties"] as? JsonObject ?: JsonObject(emptyMap())) }) { "Invalid required properties" }
            }
            s["additionalProperties"]?.let { require(type == "object" && it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "additionalProperties must be boolean" } }
            s["items"]?.let { require(type == "array" && it is JsonObject); check(it, "$p[]") }
            for ((lo, hi, types) in listOf(Triple("minimum", "maximum", setOf("number", "integer")), Triple("minLength", "maxLength", setOf("string")), Triple("minItems", "maxItems", setOf("array")))) {
                for (key in listOf(lo, hi)) s[key]?.let {
                    require(type in types && it is JsonPrimitive && !it.isString && it.doubleOrNull?.isFinite() == true) { "Invalid $key" }
                    if (key !in setOf("minimum", "maximum")) require(it.longOrNull != null && it.longOrNull!! >= 0)
                }
                val l = (s[lo] as? JsonPrimitive)?.doubleOrNull
                val h = (s[hi] as? JsonPrimitive)?.doubleOrNull
                require(l == null || h == null || l <= h) { "Invalid bounds" }
            }
            s["enum"]?.let { require(it is JsonArray && it.isNotEmpty() && it.distinct().size == it.size && it.all { v -> matchesType(type!!, v) }) { "Invalid enum" } }
            s["default"]?.let { require(validate(JsonObject(s - "default"), it).isEmpty()) { "Invalid default at $p" } }
        }
        check(schema, path)
    }

    /** Returns a list of violations; empty means valid. */
    fun validate(schema: JsonObject, value: JsonElement, path: String = "$"): List<String> {
        if (path == "$") {
            try { checkPayload(value) } catch (e: IllegalArgumentException) { return listOf(e.message ?: "Payload too large") }
        }
        val errors = mutableListOf<String>()
        val type = (schema["type"] as? JsonPrimitive)?.content
        if (type != null && !matchesType(type, value)) {
            errors += "$path: expected $type"
            return errors
        }
        schema["enum"]?.jsonArray?.let { allowed -> if (value !in allowed) errors += "$path: not one of $allowed" }
        if (value is JsonPrimitive && !value.isString) {
            value.doubleOrNull?.let { n ->
                schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { if (n < it) errors += "$path: < minimum $it" }
                schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { if (n > it) errors += "$path: > maximum $it" }
            }
        }
        if (value is JsonPrimitive && value.isString) {
            val len = value.content.codePointCount(0, value.content.length)
            schema["minLength"]?.jsonPrimitive?.longOrNull?.let { if (len < it) errors += "$path: shorter than $it" }
            schema["maxLength"]?.jsonPrimitive?.longOrNull?.let { if (len > it) errors += "$path: longer than $it" }
        }
        if (value is JsonArray) {
            schema["minItems"]?.jsonPrimitive?.longOrNull?.let { if (value.size < it) errors += "$path: fewer than $it items" }
            schema["maxItems"]?.jsonPrimitive?.longOrNull?.let { if (value.size > it) errors += "$path: more than $it items" }
            schema["items"]?.jsonObject?.let { items ->
                value.forEachIndexed { i, v -> errors += validate(items, v, "$path[$i]") }
            }
        }
        if (value is JsonObject) {
            val props = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            schema["required"]?.jsonArray?.forEach { r ->
                val name = r.jsonPrimitive.content
                if (name !in value) errors += "$path.$name: required"
            }
            val closed = schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false
            for ((k, v) in value) {
                val sub = props[k]?.jsonObject
                if (sub != null) errors += validate(sub, v, "$path.$k")
                else if (closed) errors += "$path.$k: unexpected property"
            }
        }
        return errors
    }

    private fun matchesType(type: String, v: JsonElement): Boolean = when (type) {
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> v is JsonPrimitive && v.isString
        "boolean" -> v is JsonPrimitive && !v.isString && v.booleanOrNull != null
        "integer" -> v is JsonPrimitive && !v.isString && v.longOrNull != null
        "number" -> v is JsonPrimitive && !v.isString && v.doubleOrNull?.isFinite() == true
        "null" -> v is JsonNull
        else -> false
    }
}

/** Small DSL so capability schemas stay compact and always inside the supported subset. */
class ObjectSchemaBuilder {
    private val props = linkedMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(name: String, desc: String, required: Boolean = false, enum: List<String>? = null, maxLength: Int? = null) =
        prop(name, required, buildJsonObject {
            put("type", "string"); put("description", desc)
            enum?.let { e -> putJsonArray("enum") { e.forEach { add(JsonPrimitive(it)) } } }
            maxLength?.let { put("maxLength", it) }
        })

    fun integer(name: String, desc: String, required: Boolean = false, min: Long? = null, max: Long? = null) =
        prop(name, required, buildJsonObject {
            put("type", "integer"); put("description", desc)
            min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
        })

    fun number(name: String, desc: String, required: Boolean = false, min: Double? = null, max: Double? = null) =
        prop(name, required, buildJsonObject {
            put("type", "number"); put("description", desc)
            min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
        })

    fun boolean(name: String, desc: String, required: Boolean = false) =
        prop(name, required, buildJsonObject { put("type", "boolean"); put("description", desc) })

    fun stringArray(name: String, desc: String, required: Boolean = false, maxItems: Int = 64) =
        prop(name, required, buildJsonObject {
            put("type", "array"); put("description", desc); put("maxItems", maxItems)
            putJsonObject("items") { put("type", "string") }
        })

    fun obj(name: String, desc: String, required: Boolean = false) =
        prop(name, required, buildJsonObject { put("type", "object"); put("description", desc) })

    fun prop(name: String, required: Boolean, schema: JsonObject) {
        props[name] = schema
        if (required) this.required += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        put("additionalProperties", false)
    }
}

fun objectSchema(block: ObjectSchemaBuilder.() -> Unit = {}): JsonObject = ObjectSchemaBuilder().apply(block).build()
