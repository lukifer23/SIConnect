package dev.siconnect.app.privileged

import dev.siconnect.app.backend.*
import kotlinx.serialization.json.Json

/** Instantiated by Shizuku in its own shell/root process, never by Android's app ServiceManager. */
class PrivilegedShellService(private val authorizedUid: Int? = null) : IPrivilegedShell.Stub() {
    private val json = Json { encodeDefaults = true }
    private val actualIdentity = ShellRunner.identity()
    private fun authorize() { authorizedUid?.let { check(android.os.Binder.getCallingUid() == it) { "Caller does not own this service" } } }
    override fun identity(): String { authorize(); return actualIdentity }
    override fun exec(requestJson: String): String {
        authorize()
        require(requestJson.length <= 128_000) { "Command envelope too large" }
        return json.encodeToString(ShellResult.serializer(), ShellRunner.run(json.decodeFromString(ShellRequest.serializer(), requestJson), actualIdentity))
    }
    override fun cancel(execId: String): Boolean { authorize(); return ShellRunner.cancel(execId) }
    override fun destroy() { authorize(); ShellRunner.cancelAll(); kotlin.system.exitProcess(0) }
}
