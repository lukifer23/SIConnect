package dev.siconnect.app.transport

import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class GatewayFailureTest {
    @Test fun guidanceNeverIncludesUntrustedExceptionOrCloseText() {
        val privateText="https://private.invalid/secret-token"
        assertEquals(GatewayFailure.AUTH_REJECTED,GatewayFailure.failure(Exception(privateText),401))
        assertEquals(GatewayFailure.DNS_FAILURE,GatewayFailure.failure(UnknownHostException(privateText),null))
        assertEquals(GatewayFailure.TLS_REJECTED,GatewayFailure.failure(SSLHandshakeException(privateText),null))
        assertEquals(GatewayFailure.REMOTE_CLOSED,GatewayFailure.closed(privateText))
        GatewayFailure.entries.forEach {assertFalse(it.guidance.contains(privateText))}
    }
    @Test fun sessionReplacementIsDistinctFromOrdinaryClosure() {
        assertEquals(GatewayFailure.SESSION_REPLACED,GatewayFailure.closed("Replaced by authenticated reconnect"))
        assertEquals(GatewayFailure.REMOTE_CLOSED,GatewayFailure.closed("maintenance"))
    }
}
