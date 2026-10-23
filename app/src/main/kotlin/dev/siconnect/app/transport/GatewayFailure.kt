package dev.siconnect.app.transport

import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Fixed categories only: server close reasons, URLs and exception messages never enter the log. */
internal enum class GatewayFailure(val guidance:String) {
    AUTH_REJECTED("The gateway rejected this phone's credential. Review the existing gateway configuration."),
    DNS_FAILURE("The gateway address could not be resolved. Check internet access."),
    TLS_REJECTED("The secure connection was rejected. Check the gateway certificate and phone clock."),
    NETWORK_TIMEOUT("The connection timed out. SIConnect will retry with bounded backoff."),
    SESSION_REPLACED("The gateway replaced this device session. Retrieve original request results before retrying actions."),
    REMOTE_CLOSED("The gateway closed the connection. SIConnect will reconnect."),
    TRANSPORT_FAILURE("The internet connection was interrupted. SIConnect will reconnect.");

    companion object {
        fun failure(error:Throwable,httpCode:Int?)=when {
            httpCode==401 || httpCode==403->AUTH_REJECTED
            error is SSLException->TLS_REJECTED
            error is UnknownHostException->DNS_FAILURE
            error is InterruptedIOException->NETWORK_TIMEOUT
            else->TRANSPORT_FAILURE
        }
        fun closed(reason:String)=if(reason=="Replaced by authenticated reconnect") SESSION_REPLACED else REMOTE_CLOSED
    }
}
