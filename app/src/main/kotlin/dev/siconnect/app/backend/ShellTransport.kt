package dev.siconnect.app.backend

import android.os.ParcelFileDescriptor
import dev.siconnect.app.data.AppJson
import dev.siconnect.app.privileged.IPrivilegedShell
import dev.siconnect.core.CapabilityException
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/** Both authority backends use the same bounded, descriptor-owned result transport. */
internal object ShellTransport {
    suspend fun execute(remote:IPrivilegedShell,request:ShellRequest):ShellResult = coroutineScope {
        val encoded=AppJson.encodeToString(ShellRequest.serializer(),request)
        if(encoded.length>128_000 || encoded.toByteArray(Charsets.UTF_8).size>131_072)
            throw CapabilityException("REQUEST_TOO_LARGE","Shell command envelope exceeds 128 KiB before dispatch",noSideEffects=true)
        val call=async(Dispatchers.IO) {
            val descriptor=remote.execStream(encoded)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {input ->
                val bytes=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {
                    val read=input.read(buffer);if(read<0) break
                    check(bytes.size()+read<=ShellOutput.MAX_RESULT_BYTES) {"Shell result envelope too large"}
                    bytes.write(buffer,0,read)
                }
                AppJson.decodeFromString(ShellResult.serializer(),bytes.toString("UTF-8"))
            }
        }
        try {call.await()}
        catch(e:CancellationException) {
            withContext(NonCancellable+Dispatchers.IO) {runCatching {remote.cancel(request.execId)}}
            throw e
        }
    }
}
