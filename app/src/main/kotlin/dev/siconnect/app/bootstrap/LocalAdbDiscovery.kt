package dev.siconnect.app.bootstrap

import android.content.Context
import android.net.nsd.*
import android.os.Build
import kotlinx.coroutines.*
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Resolve only this phone. Never scan ports or connect to another advertised Android device. */
internal class LocalAdbDiscovery(private val context:Context) {
    companion object {
        const val PAIRING="_adb-tls-pairing._tcp."
        const val CONNECT="_adb-tls-connect._tcp."
        fun localAddress(host:String):java.net.InetAddress? = runCatching {
            // No DNS and no caller-supplied hostname. Compare actual assigned numeric addresses.
            val numeric=android.net.InetAddresses.parseNumericAddress(host)
            NetworkInterface.getNetworkInterfaces().toList().flatMap {it.inetAddresses.toList()}.firstOrNull {LocalAdbAddress.matches(numeric,it)}
        }.getOrNull()
        fun isLocal(host:String)=localAddress(host)!=null
    }
    suspend fun find(type:String,picker:Boolean,localPermission:Boolean):LocalAdbEndpoint=withContext(Dispatchers.Main) {
        require(type==PAIRING || type==CONNECT)
        val nsd=context.getSystemService(NsdManager::class.java)
        val callbacks=mutableListOf<NsdManager.ServiceInfoCallback>()
        val resolutions=mutableListOf<NsdManager.ResolveListener>()
        var discovery:NsdManager.DiscoveryListener?=null
        try {withTimeout(30_000) {suspendCancellableCoroutine {continuation ->
            fun fail(message:String,permissionDenied:Boolean=false) {
                if(continuation.isActive) continuation.resumeWithException(if(permissionDenied) SecurityException(message) else IllegalStateException(message))
            }
            fun resolved(info:NsdServiceInfo) {
                val addresses=if(Build.VERSION.SDK_INT>=34) info.hostAddresses else listOfNotNull(info.host)
                val address=addresses.firstNotNullOfOrNull {localAddress(it.hostAddress ?: "")} ?: return
                if(continuation.isActive) continuation.resume(LocalAdbEndpoint(requireNotNull(address.hostAddress),info.port))
            }
            discovery=object:NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type:String) {}
                override fun onDiscoveryStopped(type:String) {}
                override fun onStartDiscoveryFailed(type:String,error:Int) {fail(if(error==NsdManager.FAILURE_PERMISSION_DENIED) "Android denied service discovery. Choose the service in Android’s picker or grant local-network access." else "Wireless debugging discovery could not start ($error)",error==NsdManager.FAILURE_PERMISSION_DENIED)}
                override fun onStopDiscoveryFailed(type:String,error:Int) {}
                override fun onServiceLost(info:NsdServiceInfo) {}
                override fun onServiceFound(info:NsdServiceInfo) {
                    if(!continuation.isActive) return
                    if(Build.VERSION.SDK_INT>=34) {
                        val callback=object:NsdManager.ServiceInfoCallback {
                            override fun onServiceInfoCallbackRegistrationFailed(error:Int) {fail("Wireless debugging resolution failed ($error)",error==NsdManager.FAILURE_PERMISSION_DENIED)}
                            override fun onServiceInfoCallbackUnregistered() {}
                            override fun onServiceLost() {}
                            override fun onServiceUpdated(info:NsdServiceInfo) {resolved(info)}
                        }
                        callbacks+=callback
                        try {nsd.registerServiceInfoCallback(info,context.mainExecutor,callback)} catch(_:SecurityException) {fail("Android denied service resolution",true)}
                    } else {
                        val listener=object:NsdManager.ResolveListener {
                            override fun onResolveFailed(info:NsdServiceInfo,error:Int) {fail("Wireless debugging resolution failed ($error)",error==NsdManager.FAILURE_PERMISSION_DENIED)}
                            override fun onServiceResolved(info:NsdServiceInfo) {resolved(info)}
                        }
                        resolutions+=listener
                        @Suppress("DEPRECATION") nsd.resolveService(info,listener)
                    }
                }
            }
            if(Build.VERSION.SDK_INT>=37) {
                val flags=if(picker) DiscoveryRequest.FLAG_SHOW_PICKER else if(localPermission) DiscoveryRequest.FLAG_NO_PICKER else DiscoveryRequest.FLAG_USER_APPROVED_ONLY
                nsd.discoverServices(DiscoveryRequest.Builder(type).setFlags(flags).build(),context.mainExecutor,requireNotNull(discovery))
            } else nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,requireNotNull(discovery))
        }}} finally {
            discovery?.let {runCatching {nsd.stopServiceDiscovery(it)}}
            if(Build.VERSION.SDK_INT>=34) callbacks.forEach {runCatching {nsd.unregisterServiceInfoCallback(it)}}
        }
    }
}
