package dev.siconnect.app.capabilities

import dev.siconnect.core.CapabilityException
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

internal object BoundedUtf8 {
    fun decode(bytes:ByteArray,truncated:Boolean):Pair<String,Int> {
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val input=ByteBuffer.wrap(bytes);val output=CharBuffer.allocate(bytes.size)
        val result=decoder.decode(input,output,!truncated)
        if(result.isError || result.isOverflow) throw CapabilityException("INVALID_UTF8","Content contains invalid UTF-8; read it as base64")
        output.flip()
        return output.toString() to input.remaining()
    }
}

/** Reads only a validated managed path supplied by the capability's confinement check. */
internal object BoundedFileRead {
    fun read(file:File,path:String,limit:Int,encoding:String):JsonObject {
        require(limit in 1..131072 && encoding in setOf("utf8","base64"))
        if(!file.isFile) throw CapabilityException("NOT_A_FILE","File does not exist")
        val before=file.length() to file.lastModified()
        val bytes=file.inputStream().use {input->
            val buffer=ByteArray(limit);var count=0
            while(count<limit) {val size=input.read(buffer,count,limit-count);if(size<0) break;count+=size}
            buffer.copyOf(count)
        }
        if(before!=(file.length() to file.lastModified())) throw CapabilityException("FILE_CHANGED","File changed while reading")
        val truncated=before.first>bytes.size
        val content=if(encoding=="utf8") BoundedUtf8.decode(bytes,truncated) else Base64.getEncoder().encodeToString(bytes) to 0
        return buildJsonObject {
            put("path",path);put("encoding",encoding);put("bytesReturned",bytes.size-content.second);put("totalBytes",before.first)
            put("truncated",truncated);put("utf8BoundaryBytesDropped",content.second);put("content",content.first)
        }
    }
}
