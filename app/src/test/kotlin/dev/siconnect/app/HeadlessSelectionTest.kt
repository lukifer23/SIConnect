package dev.siconnect.app

import dev.siconnect.app.capabilities.HeadlessSelection
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HeadlessSelectionTest {
    private val row=buildJsonObject {
        put("text","Earlier messages");put("description","Read conversation history")
        put("resourceId","example.app:id/message_list");put("className","android.widget.ScrollView")
        put("scrollable",true);put("enabled",true);put("editable",false)
    }
    @Test fun matchingCombinesEveryPredicateWithoutRelaxingIdentifiers() {
        assertTrue(HeadlessSelection.parse(buildJsonObject {put("text","messages");put("contains",true);put("scrollable",true)})!!.matches(row))
        assertFalse(HeadlessSelection.parse(buildJsonObject {put("text","messages");put("contains",true);put("editable",true)})!!.matches(row))
        assertFalse(HeadlessSelection.parse(buildJsonObject {put("resourceId","message_list");put("contains",true)})!!.matches(row))
        assertFalse(HeadlessSelection.parse(buildJsonObject {put("text","messages")})!!.matches(row))
        assertFalse(HeadlessSelection.parse(buildJsonObject {put("resourceId","example.app:id/message_list");put("text","Missing")})!!.matches(row))
    }
    @Test fun malformedSelectorsCannotSilentlyBroadenSelection() {
        for(raw in listOf(JsonNull,JsonPrimitive("all"),buildJsonObject {},buildJsonObject {put("contains",true)},
            buildJsonObject {put("unknown","match")},buildJsonObject {put("text",42)},buildJsonObject {put("scrollable","true")},
            buildJsonObject {put("text","x".repeat(501))})) {
            try {HeadlessSelection.parse(raw);fail("Invalid selector admitted")} catch(_:IllegalArgumentException) { }
        }
        assertNull(HeadlessSelection.parse(null))
    }
    @Test fun returnedFieldExactnessAndFalsePredicatesAreRespected() {
        assertTrue(HeadlessSelection.parse(buildJsonObject {put("className","android.widget.ScrollView");put("editable",false)})!!.matches(row))
        assertFalse(HeadlessSelection.parse(buildJsonObject {put("className","ScrollView");put("contains",true)})!!.matches(row))
        assertFalse(HeadlessSelection.parse(buildJsonObject {put("focused",false)})!!.matches(row))
    }
}
