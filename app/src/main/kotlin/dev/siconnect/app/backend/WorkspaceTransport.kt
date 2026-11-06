package dev.siconnect.app.backend

import android.os.ParcelFileDescriptor
import dev.siconnect.app.privileged.IPrivilegedShell
import dev.siconnect.core.CapabilityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

/** Existing authenticated Binder, reliable owned pipe, bounded reply. No extra socket/server. */
internal object WorkspaceTransport {
    // The real calling app process owns this token for its lifetime, independently of
    // whichever provider attachment currently supplies the privileged service Binder.
    val ownerToken=android.os.Binder()
    suspend fun execute(remote:IPrivilegedShell,request:JsonObject):JsonObject=withContext(Dispatchers.IO) {
        val execId="workspace-${java.util.UUID.randomUUID()}"
        val encoded=buildJsonObject {request.forEach {(k,v)->put(k,v)};put("execId",execId)}.toString()
        if(encoded.toByteArray(Charsets.UTF_8).size>16_384) throw CapabilityException("REQUEST_TOO_LARGE","Workspace request exceeds 16 KiB",noSideEffects=true)
        val descriptor=try {remote.workspaceStream(encoded,ownerToken)} catch(e:android.os.RemoteException) {
            throw CapabilityException("WORKSPACE_RESULT_LOST","Private backend reply was lost. Inspect the original request; the selected backend may need restarting after an update")
        }
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {input->
            cancellableIo(cancel={try {remote.cancelWorkspace(execId)} finally {input.close()}}) {
                val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {
                    val size=input.read(buffer);if(size<0) break
                    check(out.size()+size<=ShellOutput.MAX_RESULT_BYTES) {"Workspace result exceeds its bound"}
                    out.write(buffer,0,size)
                }
                val result=Json.parseToJsonElement(out.toString("UTF-8")).jsonObject
                result["errorCode"]?.jsonPrimitive?.content?.let {code->
                    throw CapabilityException(code,result["message"]?.jsonPrimitive?.content ?: "Private workspace operation failed",
                        noSideEffects=result["noSideEffects"]?.jsonPrimitive?.booleanOrNull==true)
                }
                result
            }
        }
    }
}
