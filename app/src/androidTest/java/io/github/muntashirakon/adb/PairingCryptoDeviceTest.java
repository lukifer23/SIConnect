package io.github.muntashirakon.adb;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.*;

/** Isolated real SPAKE2/AES tests. Does NOT establish physical Android Wireless debugging pairing. */
public class PairingCryptoDeviceTest {
    @Test public void matchingCodeAuthenticatesAndTamperingFails() {
        PairingAuthCtx alice=PairingAuthCtx.createAlice("123456-tls-export-fixture".getBytes());
        PairingAuthCtx bob=PairingAuthCtx.createBob("123456-tls-export-fixture".getBytes());
        assertNotNull(alice);assertNotNull(bob);
        try {
            assertTrue(alice.initCipher(bob.getMsg()));assertTrue(bob.initCipher(alice.getMsg()));
            byte[] encrypted=alice.encrypt("disposable-peer".getBytes());
            assertArrayEquals("disposable-peer".getBytes(),bob.decrypt(encrypted));
            encrypted=alice.encrypt("tampered".getBytes());encrypted[0]^=1;assertNull(bob.decrypt(encrypted));
        } finally {alice.destroy();bob.destroy();}
    }
    @Test public void wrongCodeDoesNotAuthenticatePeerInfo() {
        PairingAuthCtx alice=PairingAuthCtx.createAlice("123456".getBytes());
        PairingAuthCtx bob=PairingAuthCtx.createBob("654321".getBytes());
        try {assertTrue(alice.initCipher(bob.getMsg()));assertTrue(bob.initCipher(alice.getMsg()));assertNull(bob.decrypt(alice.encrypt(new byte[8192])));}
        finally {alice.destroy();bob.destroy();}
    }
    @Test public void pairingHeadersRejectUnsupportedAndOversizedInputs() {
        assertNotNull(header(1,0,32));assertNotNull(header(1,1,8208));
        assertNull(header(2,0,32));assertNull(header(1,2,32));assertNull(header(1,0,-1));assertNull(header(1,0,Integer.MAX_VALUE));assertNull(header(1,0,0));
    }
    private Object header(int version,int type,int size) {return PairingConnectionCtx.PairingPacketHeader.readFrom((ByteBuffer)ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).put((byte)version).put((byte)type).putInt(size).flip());}
}
