package dev.siconnect.app.backend

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ShellOutputTest {
    @Test fun legacySmallOutputIsUnchangedAndBinaryBytesRemainExact() {
        val bytes=byteArrayOf(0,0xff.toByte())
        val view=ShellOutput.representation(bytes,"legacy",1024)
        assertEquals("AP8=",view.base64);assertFalse(view.validUtf8);assertFalse(view.textTruncated)
        assertArrayEquals(bytes,Base64.getDecoder().decode(view.base64))
        assertEquals("hello",ShellOutput.representation("hello".toByteArray(),"legacy",1024).text)
    }
    @Test fun worstEscapingAndSimultaneousMaximumStreamsStayInsideSerializedBudget() {
        for(mode in listOf("legacy","text","base64")) {
            val bytes=ByteArray(131072) {if(it%2==0) 0 else 0xff.toByte()}
            val budget=ShellOutput.textBudget(bytes.size,bytes.size,mode)
            val view=ShellOutput.representation(bytes,mode,budget)
            val result=ShellResult("fixture",0,view.text,view.text,true,true,false,false,1,"uid=2000(shell)",
                stdoutBase64=view.base64,stderrBase64=view.base64,outputEncoding=mode,stdoutTextTruncated=view.textTruncated,stderrTextTruncated=view.textTruncated)
            val encoded=Json {encodeDefaults=true}.encodeToString(ShellResult.serializer(),result).toByteArray()
            assertTrue("$mode used ${encoded.size} bytes",encoded.size<=ShellOutput.MAX_RESULT_BYTES)
            if(mode!="text") assertArrayEquals(bytes,Base64.getDecoder().decode(view.base64))
            if(mode=="text") assertEquals("",view.base64)
        }
    }
}
