package io.github.muntashirakon.adb;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.*;

public class AdbProtocolTest {
    @Test public void roundTripAndMalformedPacketsAreBounded() throws Exception {
        byte[] packet=AdbProtocol.generateMessage(AdbProtocol.A_WRTE,1,2,new byte[]{1,2,3});
        AdbProtocol.Message message=AdbProtocol.Message.parse(new ByteArrayInputStream(packet),AdbProtocol.A_VERSION_MIN,4096);
        assertEquals(3,message.dataLength);assertArrayEquals(new byte[]{1,2,3},message.payload);
        byte[] bad=packet.clone();bad[20]^=1;reject(bad,4096);
        bad=packet.clone();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(12,Integer.MAX_VALUE);reject(bad,4096);
        bad=packet.clone();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(12,-1);reject(bad,4096);
        reject(java.util.Arrays.copyOf(packet,25),4096);
        reject(packet,2);
        bad=packet.clone();bad[24]^=1;reject(bad,4096);
    }
    @Test public void unknownCommandsNeverReachDispatch() throws Exception {
        byte[] packet=AdbProtocol.generateMessage(0x12345678,0,0,null);reject(packet,4096);
    }
    private static void reject(byte[] packet,int max) throws Exception {
        try {AdbProtocol.Message.parse(new ByteArrayInputStream(packet),AdbProtocol.A_VERSION_MIN,max);fail("Malformed packet accepted");}
        catch(IOException expected) {}
    }
}
