package dev.siconnect.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * Canonical JSON (sorted keys, no whitespace) used to bind approvals to exact arguments.
 * Two argument objects with the same canonical form are the same action.
 */
object Canonical {
    fun encode(element: JsonElement): String = buildString { write(element) }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Hash binding an approval: capability + version + canonical args. */
    fun actionHash(capabilityId: String, version: Int, args: JsonObject): String =
        sha256Hex("$capabilityId@$version\n${encode(args)}")

    private fun StringBuilder.write(e: JsonElement) {
        when (e) {
            is JsonNull -> append("null")
            is JsonPrimitive -> append(e.toString()) // kotlinx emits quoted+escaped strings, raw numbers/bools
            is JsonArray -> {
                append('[')
                e.forEachIndexed { i, v -> if (i > 0) append(','); write(v) }
                append(']')
            }
            is JsonObject -> {
                append('{')
                e.keys.sorted().forEachIndexed { i, k ->
                    if (i > 0) append(',')
                    append(JsonPrimitive(k).toString()).append(':')
                    write(e.getValue(k))
                }
                append('}')
            }
        }
    }
}
