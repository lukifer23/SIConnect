package dev.siconnect.core

import kotlinx.serialization.json.*
import kotlin.test.*

class SchemaBoundsTest {
    private fun schema(max: Long) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("n") { put("type", "integer"); put("minimum", 0); put("maximum", max) } }
    }
    @Test fun integerBoundsAreExactAbove2Pow53() {
        val max = 9_007_199_254_740_992L // 2^53
        assertTrue(Schema.validate(schema(max), buildJsonObject { put("n", max) }).isEmpty())
        assertTrue(Schema.validate(schema(max), buildJsonObject { put("n", max + 1) }).isNotEmpty(), "2^53+1 must exceed a 2^53 maximum")
        assertTrue(Schema.validate(schema(max), buildJsonObject { put("n", -1) }).isNotEmpty())
    }
    @Test fun decimalBoundsStillApply() {
        val s = buildJsonObject { put("type", "object"); putJsonObject("properties") { putJsonObject("x") { put("type", "number"); put("minimum", -90.0); put("maximum", 90.0) } } }
        assertTrue(Schema.validate(s, buildJsonObject { put("x", 89.9999) }).isEmpty())
        assertTrue(Schema.validate(s, buildJsonObject { put("x", 90.0001) }).isNotEmpty())
        assertTrue(Schema.validate(s, buildJsonObject { put("x", 1.5e1) }).isEmpty())
    }
}
