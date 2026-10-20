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
        directStatus.value=runCatching { withContext(Dispatchers.IO) { requireNotNull(DirectAuthority.service).identity() } }
            .getOrElse { "Built-in service unavailable: ${it.javaClass.simpleName}. Run SIConnect setup once after reboot." }
    }
    suspend fun execute(req: ShellRequest): ShellResult {
        if(kind==BackendKind.SHIZUKU) return shizuku.execute(req)
        val service=DirectAuthority.service ?: throw CapabilityException("PRIVILEGE_REQUIRED",detail())
        return coroutineScope {
            val call=async(Dispatchers.IO) { service.exec(AppJson.encodeToString(ShellRequest.serializer(),req)) }
            try { AppJson.decodeFromString(ShellResult.serializer(),call.await()) }
            catch(e: CancellationException) { withContext(NonCancellable+Dispatchers.IO) { runCatching { service.cancel(req.execId) } }; throw e }
        }
    }
}
