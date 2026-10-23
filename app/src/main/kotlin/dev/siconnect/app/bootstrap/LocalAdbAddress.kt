package dev.siconnect.app.bootstrap

import java.net.Inet6Address
import java.net.InetAddress

internal object LocalAdbAddress {
    /** Text compression and zone-name spelling do not change an assigned address's identity. */
    fun matches(candidate:InetAddress,assigned:InetAddress):Boolean {
        if(!candidate.address.contentEquals(assigned.address)) return false
        if(candidate is Inet6Address && assigned is Inet6Address && candidate.isLinkLocalAddress && candidate.scopeId!=0)
            return candidate.scopeId==assigned.scopeId
        return true
    }
}
