package dev.siconnect.app.bootstrap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import dev.siconnect.app.backend.*
import dev.siconnect.app.data.Settings
import dev.siconnect.app.privileged.DirectAuthority
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.PairingConnectionCtx
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Local owner setup. Not a capability, IPC endpoint, or alternative runtime execution engine. */
class LocalAdbBootstrap(private val context:Context,private val settings:Settings,private val scope:CoroutineScope) {
    val status=MutableStateFlow(BootstrapStatus())
    private val identity=AdbHostIdentity(context)
    private val discovery=LocalAdbDiscovery(context)
    private val operations=BootstrapOperations(scope) {busy->status.update {it.copy(busy=busy)}}
    private val connectivity=context.getSystemService(android.net.ConnectivityManager::class.java)
    private var recoveryCallback:android.net.ConnectivityManager.NetworkCallback?=null
    private var recoveryNetwork:android.net.Network?=null
    private var authorityObserver:Job?=null
    val hasLocalPermission:Boolean get()=Build.VERSION.SDK_INT<37 || context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)==PackageManager.PERMISSION_GRANTED
    val pairedAt:Long get()=identity.pairedAt
    val lastBootstrap:Long get()=identity.lastBootstrap
    val recoveryEnabled:Boolean get()=identity.recover
    val recovery=MutableStateFlow(identity.recover)
    val fingerprint=MutableStateFlow<String?>(null)
    val lastFailure=MutableStateFlow<String?>(null)
    fun setRecoveryFromOwner(enabled:Boolean) {
        identity.recover=enabled
        recovery.value=identity.recover
        if(enabled) {observeRecoveryNetwork();recoverIfEnabled()} else {stopRecoveryObservers();cancel()}
    }
    @Synchronized private fun stopRecoveryObservers() {
        recoveryCallback?.let {runCatching {connectivity.unregisterNetworkCallback(it)}}
        recoveryCallback=null;recoveryNetwork=null
        authorityObserver?.cancel();authorityObserver=null
    }
    @Synchronized private fun observeRecoveryNetwork() {
        if(identity.recover && authorityObserver==null) {
            authorityObserver=scope.launch(start=CoroutineStart.LAZY) {
                DirectAuthority.status.collect {
                    if(DirectAuthority.service?.asBinder()?.isBinderAlive!=true) recoverIfEnabled()
                }
            }
            authorityObserver?.start()
        }
        if(recoveryCallback!=null || !identity.recover) return
        val callback=object:android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network:android.net.Network,capabilities:android.net.NetworkCapabilities) {
                if(capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) && recoveryNetwork!=network) {
                    recoveryNetwork=network;recoverIfEnabled()
                }
            }
            override fun onLost(network:android.net.Network) {if(recoveryNetwork==network) recoveryNetwork=null}
        }
        connectivity.registerNetworkCallback(android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(),callback)
        recoveryCallback=callback
    }
    fun resetIdentityFromOwner()=launch {
        stopRecoveryObservers()
        try {identity.reset()} finally {recovery.value=identity.recover}
        fingerprint.value=null
        publish(BootstrapStatus(BootstrapPhase.PAIRING_REQUIRED,"Local host identity removed. Forget SIConnect in Android’s paired devices, then pair again. Existing agents and gateway identity are unchanged."))
    }
    fun cancel() {operations.cancel()}
    private fun publish(value:BootstrapStatus) {status.value=value.copy(busy=operations.busy)}
    fun refresh() {
        if(status.value.busy) return
        launch(BootstrapOperations.Kind.REFRESH) {checkStatus()}
    }
    private suspend fun checkStatus() {
        fingerprint.value=identity.existing()?.fingerprint
        val service=DirectAuthority.service
        if(service?.asBinder()?.isBinderAlive==true) {
            verifyBinder(SystemClock.elapsedRealtime())
        } else {
            val debugging=android.provider.Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0)==1
            publish(when {
                !debugging -> BootstrapStatus(BootstrapPhase.DEBUGGING_DISABLED,"Wireless debugging is disabled. Enable it in Developer options on a trusted Wi-Fi network. Native features remain available.")
                identity.pairedAt==0L -> BootstrapStatus(BootstrapPhase.PAIRING_REQUIRED,"Pair this phone’s SIConnect host identity with Android Wireless debugging.")
                else -> BootstrapStatus(BootstrapPhase.PAIRED,"A previous pairing is saved; Android trust is verified only by a fresh authenticated connection. Find the current connection port to restore control.")
            })
        }
    }
    suspend fun discover(pairing:Boolean,picker:Boolean):LocalAdbEndpoint {
        if(!picker && Build.VERSION.SDK_INT>=37 && !hasLocalPermission && !identity.recover)
            error("Choose the Android service picker or grant local-network access")
        return discovery.find(if(pairing) LocalAdbDiscovery.PAIRING else LocalAdbDiscovery.CONNECT,picker,hasLocalPermission)
    }
    fun manualEndpoint(host:String,port:String):LocalAdbEndpoint {
        require(hasLocalPermission) {"Local-network access is required for manual connection on Android 17"}
        val local=LocalAdbDiscovery.localAddress(host) ?: error("Only an address assigned to this phone is accepted")
        return LocalAdbEndpoint(requireNotNull(local.hostAddress),BootstrapContract.port(port),requiresLocalPermission=true)
    }
    fun pairFromOwner(endpoint:LocalAdbEndpoint,code:String)=launch {pair(endpoint,code)}
    /** One fresh owner confirmation covers the fixed pair → connect → Binder verification sequence. */
    fun pairAndEnableFromOwner(endpoint:LocalAdbEndpoint,code:String)=launch {
        pair(endpoint,code)
        publish(BootstrapStatus(BootstrapPhase.ADB_CONNECTING,"Paired. Finding the connection service…",true))
        val connection=discovery.find(LocalAdbDiscovery.CONNECT,!hasLocalPermission,hasLocalPermission)
        bootstrap(connection,false,verifyTransport=true)
    }
    private suspend fun pair(endpoint:LocalAdbEndpoint,code:String) {
        requireLocal(endpoint)
        val password=BootstrapContract.code(code)
        try {
            val key=identity.loadOrCreate();fingerprint.value=key.fingerprint
            publish(BootstrapStatus(BootstrapPhase.PAIRING_REQUIRED,"Verifying Android’s pairing code…",busy=true))
            val pairing=PairingConnectionCtx(endpoint.host,endpoint.port,password,key.key,key.certificate,"SIConnect")
            try {bounded(pairing,30_000) {pairing.start()}} finally {pairing.close();pairing.destroySecrets()}
            identity.paired()
            publish(BootstrapStatus(BootstrapPhase.PAIRED,"Paired with Android. Ready to enable control."))
        } finally {password.fill(0)}
    }
    fun restoreFromOwner()=launch {
        if(DirectAuthority.service?.asBinder()?.isBinderAlive==true) bootstrap(null,false)
        else bootstrap(discovery.find(LocalAdbDiscovery.CONNECT,!hasLocalPermission,hasLocalPermission),false)
    }
    fun bootstrapFromOwner(endpoint:LocalAdbEndpoint?,restart:Boolean=false)=launch {
        val chosen=endpoint ?: if(restart || DirectAuthority.service?.asBinder()?.isBinderAlive!=true)
            discovery.find(LocalAdbDiscovery.CONNECT,!hasLocalPermission,hasLocalPermission) else null
        bootstrap(chosen,restart)
    }
    /** Saved owner consent permits reuse of the existing identity, never pairing or enabling debugging. */
    fun recoverIfEnabled() {
        if(identity.recover) observeRecoveryNetwork()
        if(!identity.recover || settings.privilegeBackend!="direct" || !settings.controls.value.privilegedEnabled) return
        launch(BootstrapOperations.Kind.RECOVERY) {
            for(delayMs in listOf(0L,15_000L,60_000L)) {
                delay(delayMs)
                if(!identity.recover || settings.privilegeBackend!="direct" || !settings.controls.value.privilegedEnabled) return@launch
                if(DirectAuthority.service?.asBinder()?.isBinderAlive==true) {checkStatus();return@launch}
                if(identity.pairedAt==0L || android.provider.Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0)!=1) {checkStatus();return@launch}
                try {bootstrap(null,false);return@launch}
                catch(e:Exception) {
                    // An individual connection timeout may retry; owner/job cancellation may not.
                    currentCoroutineContext().ensureActive()
                    val reason=generateSequence<Throwable>(e) {it.cause}.take(3).joinToString(" → ") {it.message ?: it.javaClass.simpleName}.take(300)
                    lastFailure.value="${status.value.phase}: $reason"
                    publish(BootstrapStatus(BootstrapPhase.OWNER_ACTION_REQUIRED,"Saved recovery failed: $reason. Check the current Wireless debugging service; re-pair only if Android revoked this host."))
                }
            }
        }
    }
    private fun launch(kind:BootstrapOperations.Kind=BootstrapOperations.Kind.OWNER,block:suspend ()->Unit) {
        val started=operations.launch(kind) {
            try {block()}
            catch(e:TimeoutCancellationException) {publish(BootstrapStatus(BootstrapPhase.OWNER_ACTION_REQUIRED,"Wireless debugging timed out. Check the current port and keep the pairing dialog open. A launched daemon may already exist; refresh before retrying."))}
            catch(e:CancellationException) {publish(BootstrapStatus(BootstrapPhase.OWNER_ACTION_REQUIRED,"Setup cancelled. Check the current Binder state before retrying; a launched daemon may already be running."));throw e}
            catch(e:SecurityException) {publish(BootstrapStatus(BootstrapPhase.LOCAL_NETWORK_PERMISSION_REQUIRED,"Android blocked local access. Choose the service picker or allow Nearby devices in app permissions."))}
            catch(e:Exception) {
                val causeChain=generateSequence<Throwable>(e) {it.cause}.take(8).toList()
                val blocked=causeChain.any {it is android.system.ErrnoException && it.errno in setOf(android.system.OsConstants.EPERM,android.system.OsConstants.EACCES)}
                val phase=if(blocked) BootstrapPhase.LOCAL_NETWORK_PERMISSION_REQUIRED else if(e is javax.net.ssl.SSLException || e is io.github.muntashirakon.adb.AdbPairingRequiredException) BootstrapPhase.PAIRING_REQUIRED else BootstrapPhase.RECOVERY_FAILED
                publish(BootstrapStatus(phase,when(e) {
                    is TimeoutCancellationException -> "Wireless debugging timed out. Keep Android’s pairing dialog open; verify the current port and Wi-Fi."
                    is javax.net.ssl.SSLException -> "Android rejected the TLS connection. The saved host may need re-pairing; verify the connection port."
                    else -> e.message?.take(300) ?: "Setup failed (${e.javaClass.simpleName}). Check Wireless debugging and retry."
                }))
            }
        }
        if(!started && kind==BootstrapOperations.Kind.OWNER) lastFailure.value="Setup is already running. Cancel it before starting another operation."
    }
    private fun requireLocal(endpoint:LocalAdbEndpoint) {
        if(endpoint.requiresLocalPermission && !hasLocalPermission) throw SecurityException("Local-network permission was revoked")
        require(LocalAdbDiscovery.isLocal(endpoint.host)) {"Wireless debugging endpoint is not this phone"}
        require(Process.myUid()/100000==0) {"Bundled bootstrap currently supports the primary Android user only"}
    }
    private suspend fun <T> bounded(resource:Closeable,timeout:Long,block:()->T):T=coroutineScope {
        val worker=async(Dispatchers.IO) {block()}
        try {withTimeout(timeout) {worker.await()}}
        finally {withContext(NonCancellable+Dispatchers.IO) {runCatching {resource.close()}};worker.cancel()}
    }
    private suspend fun bootstrap(selected:LocalAdbEndpoint?,restart:Boolean,verifyTransport:Boolean=false) {
        require(settings.privilegeBackend=="direct") {"Select the bundled backend explicitly in Settings first"}
        require(settings.controls.value.privilegedEnabled) {"Privileged routing is disabled in owner settings"}
        val start=SystemClock.elapsedRealtime()
        lastFailure.value=null
        val attached=DirectAuthority.service
        if(attached?.asBinder()?.isBinderAlive==true && !restart && !verifyTransport) {verifyBinder(start);return}
        if(restart && attached!=null) {
            withContext(Dispatchers.IO) {attached.destroy()}
            withTimeout(5000) {while(DirectAuthority.service?.asBinder()?.isBinderAlive==true) delay(100)}
        }
        publish(BootstrapStatus(BootstrapPhase.ADB_CONNECTING,"Finding this phone’s current TLS connection service…",true))
        val endpoint=selected ?: discover(false,false)
        requireLocal(endpoint)
        val key=identity.existing() ?: error("Pair with Android Wireless debugging first")
        val caller=currentCoroutineContext()
        val holder=AtomicReference<AdbConnection?>()
        bounded(Closeable {holder.get()?.close()},40_000) {
            val connection=AdbConnection.Builder(endpoint.host,endpoint.port).setApi(Build.VERSION.SDK_INT)
                .setPrivateKey(key.key).setCertificate(key.certificate).setDeviceName("SIConnect").build()
            holder.set(connection)
            try {
                caller.ensureActive()
                check(connection.connect(10,TimeUnit.SECONDS,true)) {"ADB authentication timed out. Check the current port and saved pairing."}
                caller.ensureActive()
                publish(BootstrapStatus(BootstrapPhase.ADB_CONNECTING,"TLS authenticated. Verifying the ADB shell identity…",true))
                check(BootstrapContract.shellUid(shell(connection,"id -u"))) {"Authenticated ADB did not prove shell UID 2000"}
                caller.ensureActive()
                publish(BootstrapStatus(BootstrapPhase.ADB_AUTHENTICATED,"Authenticated local ADB returned UID 2000. Checking installed package…",true))
                val info=context.packageManager.getApplicationInfo(BootstrapContract.PACKAGE,0)
                check(info.uid==Process.myUid()) {"Package identity mismatch"}
                val apk=BootstrapContract.apk(shell(connection,"pm path --user 0 ${BootstrapContract.PACKAGE}"),info.sourceDir)
                val uid=BootstrapContract.uid(shell(connection,"cmd package list packages --user 0 -U ${BootstrapContract.PACKAGE}"),info.uid)
                val running=shell(connection,"pidof siconnect_privileged || true").trim()
                caller.ensureActive()
                if(running.isNotEmpty()) {
                    require(running.matches(Regex("[0-9]+(?: [0-9]+)*"))) {"Unexpected daemon process response"}
                    publish(BootstrapStatus(BootstrapPhase.BOOTSTRAPPING,"Existing daemon detected; waiting for authenticated Binder attachment without starting a duplicate.",true))
                } else {
                    publish(BootstrapStatus(BootstrapPhase.BOOTSTRAPPING,"Starting the bundled Binder daemon under verified shell authority…",true))
                    caller.ensureActive()
                    shell(connection,BootstrapContract.start(apk,uid))
                }
            } finally {connection.close()}
        }
        // Temporary ADB is already closed. Runtime tools continue through the original Binder interface.
        withTimeout(15_000) {while(DirectAuthority.service?.asBinder()?.isBinderAlive!=true) delay(100)}
        verifyBinder(start)
    }
    private suspend fun verifyBinder(start:Long) {
        val service=requireNotNull(DirectAuthority.service)
        publish(BootstrapStatus(BootstrapPhase.BOOTSTRAPPING,"ADB disconnected. Verifying a fresh command through the bundled Binder service…",true))
        val actual=withContext(Dispatchers.IO) {service.identity()}
        check(actual.startsWith("uid=2000(")) {"Bundled authority did not report shell UID 2000"}
        val result=ShellTransport.execute(service,ShellRequest("bootstrap:${UUID.randomUUID()}",argv=listOf("/system/bin/id","-u"),timeoutMs=5000,maxOutputBytes=1024))
        check(result.exitCode==0 && !result.timedOut && !result.cancelled && BootstrapContract.shellUid(result.stdout)) {"Binder shell UID verification failed"}
        check(DirectAuthority.service?.asBinder()===service.asBinder() && service.asBinder().isBinderAlive) {"Binder attachment changed during verification"}
        identity.bootstrapped()
        lastFailure.value=null
        publish(BootstrapStatus(BootstrapPhase.PRIVILEGED_READY,"Bundled Binder verified UID 2000 with temporary ADB disconnected.",identity=actual,elapsedMs=SystemClock.elapsedRealtime()-start))
    }
    private fun shell(connection:AdbConnection,command:String):String=connection.open("shell,v2,raw:$command").use {stream ->
        val result=stream.openInputStream().use {AdbShellProtocol.read(it)}
        check(result.exitCode==0) {"Bootstrap command exited ${result.exitCode}: ${result.stderr.take(200)}"}
        result.stdout
    }
}
