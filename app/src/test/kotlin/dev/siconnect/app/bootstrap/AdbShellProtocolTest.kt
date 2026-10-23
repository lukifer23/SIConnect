package dev.siconnect.app.bootstrap

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class AdbShellProtocolTest {
    private fun frame(channel:Int,payload:ByteArray):ByteArray=ByteArrayOutputStream().apply {
        DataOutputStream(this).apply {writeByte(channel);writeInt(Integer.reverseBytes(payload.size));write(payload)}
    }.toByteArray()
    private fun read(bytes:ByteArray,max:Int=16384)=AdbShellProtocol.read(ByteArrayInputStream(bytes),max)
    private fun rejected(bytes:ByteArray,max:Int=16384) {try {read(bytes,max);fail("Malformed output accepted")} catch(_:IOException) {}}
    @Test fun interleavedOutputUsesActualExitStatus() {
        val r=read(frame(1,"20".toByteArray())+frame(2,"error".toByteArray())+frame(1,"00\n".toByteArray())+frame(3,byteArrayOf(7)))
        assertEquals("2000\n",r.stdout);assertEquals("error",r.stderr);assertEquals(7,r.exitCode)
    }
    @Test fun eofNeverInventsSuccessfulExit() {rejected(byteArrayOf());rejected(frame(1,"2000\n".toByteArray()));rejected(byteArrayOf(1,2))}
    @Test fun outputAndFrameLengthsAreBounded() {
        rejected(frame(1,byteArrayOf(1,2))+frame(2,byteArrayOf(3,4)),3)
        rejected(byteArrayOf(1,-1,-1,-1,-1));rejected(frame(1,byteArrayOf(1,2,3)),2)
    }
    @Test fun malformedExitAndUnknownChannelsAreRejected() {rejected(frame(3,byteArrayOf()));rejected(frame(3,byteArrayOf(0,0)));rejected(frame(4,byteArrayOf()))}
    @Test fun zeroOutputCommandCanSucceed() {val r=read(frame(3,byteArrayOf(0)));assertEquals(0,r.exitCode);assertEquals("",r.stdout)}
}
