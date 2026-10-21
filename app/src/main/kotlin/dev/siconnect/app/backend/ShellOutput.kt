package dev.siconnect.app.backend

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** JSON budgets are measured after escaping, not in Java characters or captured process bytes. */
internal object ShellOutput {
    const val MAX_RESULT_BYTES = 512 * 1024
    private const val METADATA_RESERVE = 16 * 1024
    data class Stream(val text:String,val base64:String,val textTruncated:Boolean,val validUtf8:Boolean)

    fun representation(bytes:ByteArray,mode:String,textJsonBudget:Int):Stream {
        val valid=runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)) }.isSuccess
        val text=String(bytes,Charsets.UTF_8)
        val bounded=if(mode=="base64") "" else boundedText(text,textJsonBudget)
        return Stream(bounded,if(mode=="text") "" else Base64.getEncoder().encodeToString(bytes),
            mode!="base64" && bounded.length<text.length,valid)
    }
    fun textBudget(stdoutBytes:Int,stderrBytes:Int,mode:String,envelopeBytes:Int=MAX_RESULT_BYTES):Int {
        val binary=if(mode=="text") 0 else 4*((stdoutBytes+2)/3)+4*((stderrBytes+2)/3)
        return (envelopeBytes-METADATA_RESERVE-binary)/2
    }
    /** Leaves a complete code point; escaped control characters cost six JSON bytes. */
    private fun boundedText(text:String,budget:Int):String {
        var i=0;var used=0
        while(i<text.length) {
            val cp=text.codePointAt(i)
            val cost=when {
                cp==34 || cp==92 || cp==8 || cp==9 || cp==10 || cp==12 || cp==13 -> 2
                cp<32 -> 6
                cp<128 -> 1
                cp<2048 -> 2
                cp<65536 -> 3
                else -> 4
            }
            if(used+cost>budget) break
            used+=cost;i+=Character.charCount(cp)
        }
        return if(i==text.length) text else text.substring(0,i)
    }
}
