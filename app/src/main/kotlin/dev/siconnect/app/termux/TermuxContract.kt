package dev.siconnect.app.termux

import dev.siconnect.core.Canonical
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Official RUN_COMMAND v0.109+ keys; no terminal-session or file-result fallback. */
internal object TermuxContract {
    const val PACKAGE="com.termux"
    const val SERVICE="com.termux.app.RunCommandService"
    const val PERMISSION="com.termux.permission.RUN_COMMAND"
    const val ACTION="com.termux.RUN_COMMAND"
    const val RESULT_ACTION="dev.siconnect.app.TERMUX_RESULT"
    // Termux ResultData.getErrCode(): Errno.ERRNO_SUCCESS is Activity.RESULT_OK,
    // not the command's POSIX exit code. 0 means Activity.RESULT_CANCELED.
    const val RESULT_ERR_SUCCESS=-1
    const val MAX_OUTPUT_BYTES=32768
    const val MAX_INPUT_BYTES=65536
    const val MAX_PENDING=64
    fun commandSucceeded(errorCode:Int?,exitCode:Int?):Boolean=errorCode==RESULT_ERR_SUCCESS && exitCode==0
    fun completeCallbackStatus(errorCode:Int,exitCode:Int?):Boolean=errorCode!=RESULT_ERR_SUCCESS || exitCode!=null
    fun supportsResultApi(version:String?):Boolean? {
        val match=version?.let {Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*").matchEntire(it)} ?: return null
        val major=match.groupValues[1].toIntOrNull() ?: return null
        val minor=match.groupValues[2].toIntOrNull() ?: return null
        return major>0 || minor>=109
    }
    fun policyCommand(args:JsonObject):String="Termux argv: "+Canonical.encode(buildJsonObject {
        put("executable",args.getValue("executable"));put("arguments",args["arguments"] ?: JsonArray(emptyList()))
        args["cwd"]?.let {put("cwd",it)};args["stdin"]?.let {put("stdin",it)}
    })
}

internal data class TermuxCommand(val executable:String,val arguments:List<String>,val cwd:String?,val stdin:String?,val timeoutMs:Long,val maxOutputBytes:Int) {
    companion object {
        fun parse(args:JsonObject):TermuxCommand {
            val executable=args.getValue("executable").jsonPrimitive.content
            val arguments=args["arguments"]?.jsonArray?.map {it.jsonPrimitive.content} ?: emptyList()
            val cwd=args["cwd"]?.jsonPrimitive?.content
            val stdin=args["stdin"]?.jsonPrimitive?.content
            val timeout=args["timeoutMs"]?.jsonPrimitive?.long ?: 30000
            val output=args["maxOutputBytes"]?.jsonPrimitive?.int ?: 16384
            require(executable.startsWith('/') && executable.length<=1024 && '\u0000' !in executable) {"Executable must be an absolute path without NUL"}
            require(cwd==null || (cwd.startsWith('/') && cwd.length<=1024 && '\u0000' !in cwd)) {"Working directory must be an absolute path without NUL"}
            require(arguments.size<=128 && arguments.all {it.length<=8192 && '\u0000' !in it}) {"At most 128 bounded arguments without NUL are supported"}
            require(timeout in 1..120000 && output in 1024..TermuxContract.MAX_OUTPUT_BYTES) {"Invalid command timeout or output bound"}
            require(listOfNotNull(executable,cwd,stdin).sumOf {it.toByteArray(Charsets.UTF_8).size.toLong()}+
                arguments.sumOf {it.toByteArray(Charsets.UTF_8).size.toLong()+1}<=TermuxContract.MAX_INPUT_BYTES) {"Termux command input exceeds 64 KiB"}
            return TermuxCommand(executable,arguments,cwd,stdin,timeout,output)
        }
    }
}

internal data class BoundedTermuxText(val text:String,val bytes:Int,val truncated:Boolean)
internal fun boundedTermuxText(raw:String,maxBytes:Int):BoundedTermuxText {
    require(maxBytes>0)
    val bytes=raw.toByteArray(Charsets.UTF_8)
    if(bytes.size<=maxBytes) return BoundedTermuxText(raw,bytes.size,false)
    var end=maxBytes
    // Do not split a UTF-8 sequence or introduce replacement characters into command output.
    while(end>0 && (bytes[end].toInt() and 0xc0)==0x80) end--
    return BoundedTermuxText(String(bytes,0,end,Charsets.UTF_8),end,true)
}

@Serializable
data class TermuxStatus(
    val installed:Boolean,
    val appEnabled:Boolean,
    val serviceAvailable:Boolean,
    val versionName:String?=null,
    val versionCode:Long?=null,
    val uid:Int?=null,
    val runCommandPermissionGranted:Boolean,
    /** Callback observation, never a read of Termux private settings or a live health guarantee. */
    val allowExternalAppsState:String="UNKNOWN",
    val lastCallbackAtMs:Long?=null,
    val lastSuccessfulCommandAtMs:Long?=null,
    val lastErrorMessage:String?=null,
    val pendingExecutionCount:Int=0,
    val resultApiSupported:Boolean?=null,
    val firstInstallAtMs:Long?=null,
)
