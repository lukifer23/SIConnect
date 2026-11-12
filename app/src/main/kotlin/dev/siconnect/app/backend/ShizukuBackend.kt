package dev.siconnect.app.backend

import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import dev.siconnect.app.data.AppJson
import dev.siconnect.app.privileged.*
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku
import kotlinx.serialization.json.JsonObject

class ShizukuBackend(context: Context, private val scope: CoroutineScope) {
    val status = MutableStateFlow("Shizuku is not running")
    @Volatile private var service: IPrivilegedShell? = null
    private val args = Shizuku.UserServiceArgs(ComponentName(context, PrivilegedShellService::class.java))
        .tag("siconnect.shell").version(6).daemon(false).processNameSuffix("privileged")
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = IPrivilegedShell.Stub.asInterface(binder)
            service = connected
            // A late death notice from a replaced Binder must not clear the newer connection.
            binder?.linkToDeath({ synchronized(this) { if (service === connected) { service = null; status.value = "UserService died; reconnect required" } } }, 0)
            scope.launch { status.value = runCatching { withContext(Dispatchers.IO) { service?.identity() } }.getOrNull() ?: "Identity unavailable" }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; status.value = "UserService disconnected" }
    }
    init {
        Shizuku.addBinderReceivedListenerSticky { connect() }
        Shizuku.addBinderDeadListener { service = null; status.value = "Shizuku server stopped" }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) connect() else status.value = "Shizuku permission denied"
        }
    }
    fun available(): Boolean = service?.asBinder()?.isBinderAlive == true && runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun requestPermission() {
        if (!Shizuku.pingBinder()) { status.value = "Start Shizuku first"; return }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) connect() else Shizuku.requestPermission(40)
    }
    fun connect() {
        if (!Shizuku.pingBinder()) { status.value = "Shizuku is not running"; return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { status.value = "Shizuku authorization required"; return }
        status.value = "Binding UserService"
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure { status.value = "Bind failed: ${it.javaClass.simpleName}" }
    }
    suspend fun execute(request: ShellRequest): ShellResult {
        val remote = service?.takeIf { available() } ?: throw CapabilityException("PRIVILEGE_REQUIRED", status.value)
        return ShellTransport.execute(remote,request)
    }
    suspend fun workspace(request:JsonObject):JsonObject {
        val remote=service?.takeIf {available()} ?: throw CapabilityException("PRIVILEGE_REQUIRED",status.value,noSideEffects=true)
        return WorkspaceTransport.execute(remote,request)
    }
}
