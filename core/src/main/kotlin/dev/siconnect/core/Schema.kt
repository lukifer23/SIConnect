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
        "minLength", "maxLength", "items", "maxItems", "description", "default",
    )

    fun checkSupported(schema: JsonObject, path: String = "$") {
        for (k in schema.keys) require(k in SUPPORTED) { "Unsupported schema keyword '$k' at $path" }
        schema["properties"]?.jsonObject?.forEach { (name, sub) -> checkSupported(sub.jsonObject, "$path.$name") }
        schema["items"]?.let { checkSupported(it.jsonObject, "$path[]") }
    }

    /** Returns a list of violations; empty means valid. */
    fun validate(schema: JsonObject, value: JsonElement, path: String = "$"): List<String> {
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
            val len = value.content.length
            schema["minLength"]?.jsonPrimitive?.longOrNull?.let { if (len < it) errors += "$path: shorter than $it" }
            schema["maxLength"]?.jsonPrimitive?.longOrNull?.let { if (len > it) errors += "$path: longer than $it" }
        }
        if (value is JsonArray) {
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
        "number" -> v is JsonPrimitive && !v.isString && v.doubleOrNull != null
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
