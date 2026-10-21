package dev.siconnect.app

import dev.siconnect.app.capabilities.DocumentRules
import dev.siconnect.core.CapabilityException
import org.junit.Assert.*
import org.junit.Test

class DocumentRulesTest {
    @Test fun pathsCannotEscapeSelectedRootOrSupplyUris() {
        assertEquals(emptyList<String>(), DocumentRules.parts(""))
        assertEquals(listOf("notes", "plan.txt"), DocumentRules.parts("notes/plan.txt"))
        for (path in listOf("/etc", "../secret", "a/../b", "a//b", "a/", "a\\b", "content://provider/item", "file:///etc/passwd", "a/\u0000", (1..17).joinToString("/") { "a" })) {
            try { DocumentRules.parts(path); fail("Unsafe path accepted") } catch (e: CapabilityException) { assertEquals("INVALID_DOCUMENT_PATH", e.code) }
        }
    }
    @Test fun truncatedUtf8KeepsOnlyCompleteCodepoints() {
        val bytes = "A\uD83D\uDE80".toByteArray(Charsets.UTF_8)
        val result = DocumentRules.utf8(bytes.copyOf(3), true)
        assertEquals("A", result.first); assertEquals(2, result.second)
        assertEquals("A\uD83D\uDE80" to 0, DocumentRules.utf8(bytes, false))
    }
    @Test fun invalidUtf8IsNeverReplacedOrHidden() {
        for (truncated in listOf(false, true)) {
            try { DocumentRules.utf8(byteArrayOf(65, -1), truncated); fail("Invalid UTF-8 was accepted") }
            catch (e: CapabilityException) { assertEquals("INVALID_UTF8", e.code) }
        }
        try { DocumentRules.utf8(byteArrayOf(-16, -97), false); fail("Incomplete full document was accepted") }
        catch (e: CapabilityException) { assertEquals("INVALID_UTF8", e.code) }
    }
}
