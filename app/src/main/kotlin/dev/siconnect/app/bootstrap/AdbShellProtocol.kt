package dev.siconnect.app.bootstrap

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.IOException

/** AOSP shell v2 framing: channel byte, little-endian uint32 length, payload.
 * Raw v2 is required: legacy shell creates a PTY whose closure sends SIGHUP.
 */
internal object AdbShellProtocol {
    data class Result(val stdout:String,val stderr:String,val exitCode:Int)
    fun read(input:InputStream,maxBytes:Int=16_384):Result {
        val stream=DataInputStream(input)
        val stdout=ByteArrayOutputStream();val stderr=ByteArrayOutputStream()
        var captured=0
        while(true) {
            val channel=stream.read()
            if(channel<0) throw IOException("ADB shell closed without an exit status")
            val length=Integer.reverseBytes(stream.readInt())
            if(length<0 || length>maxBytes) throw IOException("Invalid ADB shell frame length")
            when(channel) {
                1,2 -> {
                    if(length>maxBytes-captured) throw IOException("ADB shell output exceeded its bound")
                    val data=ByteArray(length);stream.readFully(data);captured+=length
                    (if(channel==1) stdout else stderr).write(data)
                }
                3 -> {
                    if(length!=1) throw IOException("Invalid ADB shell exit status")
                    return Result(stdout.toString("UTF-8"),stderr.toString("UTF-8"),stream.readUnsignedByte())
                }
                else -> throw IOException("Unexpected ADB shell channel")
            }
        }
    }
}
