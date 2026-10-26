package dev.siconnect.app.transport

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.atomic.AtomicBoolean

/** Observe only Android's validated default network; captive/unvalidated callbacks do not wake retries. */
internal class GatewayNetworkMonitor(private val connectivity:ConnectivityManager,private val onUsable:()->Unit):AutoCloseable {
    private val closed=AtomicBoolean()
    private var registered=false
    private var usable:Network?=null
    private val callback=object:ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities) {
            if(closed.get() || connectivity.activeNetwork!=network) return
            val ready=capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            synchronized(this@GatewayNetworkMonitor) {
                if(closed.get()) return
                if(ready && usable!=network) {usable=network;onUsable()}
                else if(!ready && usable==network) usable=null
            }
        }
        override fun onLost(network:Network) {synchronized(this@GatewayNetworkMonitor) {if(usable==network) usable=null}}
    }
    fun start() {
        check(!registered && !closed.get())
        connectivity.registerDefaultNetworkCallback(callback);registered=true
    }
    override fun close() {
        if(closed.compareAndSet(false,true) && registered) runCatching {connectivity.unregisterNetworkCallback(callback)}
    }
}
