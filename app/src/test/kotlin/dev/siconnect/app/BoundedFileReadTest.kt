package dev.siconnect.app

import dev.siconnect.app.capabilities.BoundedFileRead
import dev.siconnect.core.CapabilityException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class BoundedFileReadTest {
    @get:Rule val temporary=TemporaryFolder()

    @Test fun byteLimitsNeverInventReplacementCharactersAndCountsMatchReturnedText() {
        val file=temporary.newFile();val text="A\uD83D\uDE80Z";val bytes=text.toByteArray();file.writeBytes(bytes)
        for(limit in 1..bytes.size) {
            val read=BoundedFileRead.read(file,"fixture",limit,"utf8")
            val content=read.getValue("content").jsonPrimitive.content
            assertTrue(text.startsWith(content));assertFalse(content.contains('\uFFFD'))
            assertEquals(content.toByteArray().size,read.getValue("bytesReturned").jsonPrimitive.int)
            assertEquals(limit-content.toByteArray().size,read.getValue("utf8BoundaryBytesDropped").jsonPrimitive.int)
            assertEquals(limit<bytes.size,read.getValue("truncated").jsonPrimitive.boolean)
        }
    }

    @Test fun invalidSourceBytesAreRejectedInTextModeAndPreservedInBinaryMode() {
        val file=temporary.newFile();val bytes=byteArrayOf(65,-1,0,10);file.writeBytes(bytes)
        try {BoundedFileRead.read(file,"fixture",bytes.size,"utf8");fail("Invalid UTF-8 must not be replaced")}
        catch(e:CapabilityException) {assertEquals("INVALID_UTF8",e.code)}
        val read=BoundedFileRead.read(file,"fixture",bytes.size,"base64")
        assertArrayEquals(bytes,Base64.getDecoder().decode(read.getValue("content").jsonPrimitive.content))
        assertEquals(bytes.size,read.getValue("bytesReturned").jsonPrimitive.int)
    }

    @Test fun incompleteUtf8AtEndOfFileIsAnEncodingErrorRatherThanTruncation() {
        val file=temporary.newFile();file.writeBytes(byteArrayOf(-16,-97))
        try {BoundedFileRead.read(file,"fixture",2,"utf8");fail("Incomplete source text must be reported")}
        catch(e:CapabilityException) {assertEquals("INVALID_UTF8",e.code)}
    }
}
