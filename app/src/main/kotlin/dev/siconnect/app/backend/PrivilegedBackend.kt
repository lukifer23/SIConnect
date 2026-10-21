package dev.siconnect.app.backend

import dev.siconnect.app.privileged.DirectAuthority
import dev.siconnect.app.data.*
import dev.siconnect.core.*
import kotlinx.coroutines.*

/** Explicit owner selection. Losing one backend never switches authority to another. */
class PrivilegedBackend(private val settings: Settings, val shizuku: ShizukuBackend) {
    val directStatus=DirectAuthority.status
    val kind: BackendKind get()=if(settings.privilegeBackend=="shizuku") BackendKind.SHIZUKU else BackendKind.ADB_SERVICE
    fun available()=if(kind==BackendKind.SHIZUKU) shizuku.available() else DirectAuthority.service?.asBinder()?.isBinderAlive==true
    fun detail()=if(kind==BackendKind.SHIZUKU) shizuku.status.value else directStatus.value
    suspend fun refresh() {
        if(kind==BackendKind.SHIZUKU) { shizuku.connect(); return }
        val service=DirectAuthority.service
        if(service==null) {directStatus.value="Built-in privileged service has not been started";return}
        refreshDirectAuthority(service)
    }
    /** A late identity reply from a replaced/dead Binder cannot overwrite the current backend status. */
    suspend fun refreshDirectAuthority(service:dev.siconnect.app.privileged.IPrivilegedShell) {
        val detail=runCatching {withContext(Dispatchers.IO) {service.identity()}}
            .getOrElse {"Built-in service unavailable: ${it.javaClass.simpleName}. Run SIConnect setup once after reboot."}
        synchronized(DirectAuthority) {if(DirectAuthority.service?.asBinder()===service.asBinder()) directStatus.value=detail}
    }
    suspend fun execute(req: ShellRequest): ShellResult {
        if(kind==BackendKind.SHIZUKU) return shizuku.execute(req)
        val service=DirectAuthority.service ?: throw CapabilityException("PRIVILEGE_REQUIRED",detail())
        return ShellTransport.execute(service,req)
    }
}
