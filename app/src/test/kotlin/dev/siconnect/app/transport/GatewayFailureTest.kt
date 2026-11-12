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
    @Test fun transientEnrollmentFailuresRetryInsteadOfStoppingRemoteAccess() {
        listOf(408,429,500,502,503,504,520,522).forEach {assertEquals("HTTP $it",EnrollmentOutcome.RETRY,EnrollmentOutcome.of(it))}
        listOf(400,401,403,404,405).forEach {assertEquals("HTTP $it",EnrollmentOutcome.REJECTED,EnrollmentOutcome.of(it))}
        assertEquals(EnrollmentOutcome.ENROLLED,EnrollmentOutcome.of(200))
        assertEquals(EnrollmentOutcome.ALREADY_REGISTERED,EnrollmentOutcome.of(409))
    }
    @Test fun sessionReplacementIsDistinctFromOrdinaryClosure() {
        assertEquals(GatewayFailure.SESSION_REPLACED,GatewayFailure.closed("Replaced by authenticated reconnect"))
        assertEquals(GatewayFailure.REMOTE_CLOSED,GatewayFailure.closed("maintenance"))
    }
}
