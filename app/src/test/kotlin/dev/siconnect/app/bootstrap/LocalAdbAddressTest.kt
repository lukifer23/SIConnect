package dev.siconnect.app.bootstrap

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class LocalAdbAddressTest {
    @Test fun ipv4MustBeAnActuallyAssignedAddress() {
        val local=InetAddress.getByAddress(byteArrayOf(127,0,0,1))
        assertTrue(LocalAdbAddress.matches(InetAddress.getByAddress(local.address),local))
        assertFalse(LocalAdbAddress.matches(InetAddress.getByAddress(byteArrayOf(127,0,0,2)),local))
    }
    @Test fun equivalentIpv6BytesMatchWithoutComparingTheirZoneText() {
        val bytes=ByteArray(16).also {it[0]=0xfe.toByte();it[1]=0x80.toByte();it[15]=1}
        val local=Inet6Address.getByAddress(null,bytes,4)
        assertTrue(LocalAdbAddress.matches(Inet6Address.getByAddress(null,bytes,0),local))
        assertTrue(LocalAdbAddress.matches(Inet6Address.getByAddress(null,bytes,4),local))
        assertFalse(LocalAdbAddress.matches(Inet6Address.getByAddress(null,bytes,5),local))
        assertFalse(LocalAdbAddress.matches(Inet6Address.getByAddress(null,bytes.copyOf().also {it[15]=2},4),local))
    }
}
