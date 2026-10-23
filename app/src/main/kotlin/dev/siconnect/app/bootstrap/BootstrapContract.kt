package dev.siconnect.app.bootstrap

/** Local setup data only. No registry capability accepts these values. */
enum class BootstrapPhase {
    NATIVE_READY, DEBUGGING_DISABLED, LOCAL_NETWORK_PERMISSION_REQUIRED, PAIRING_REQUIRED,
    PAIRING_EXPIRED, PAIRED, ADB_CONNECTING, ADB_AUTHENTICATED, BOOTSTRAPPING,
    PRIVILEGED_READY, PRIVILEGED_UNAVAILABLE, OWNER_ACTION_REQUIRED, RECOVERY_FAILED
}
data class BootstrapStatus(val phase:BootstrapPhase=BootstrapPhase.NATIVE_READY,
    val detail:String="Native features are available with their Android permissions.",
    val busy:Boolean=false,val identity:String?=null,val elapsedMs:Long?=null)
data class LocalAdbEndpoint(val host:String,val port:Int,val requiresLocalPermission:Boolean=false) {
    init { require(port in 1..65535 && port!=5555) {"Use the dynamic Wireless debugging port, not legacy TCP/5555"} }
}
internal object BootstrapContract {
    const val PACKAGE="dev.siconnect.app"
    fun port(text:String):Int = text.toIntOrNull()?.also {require(it in 1..65535 && it!=5555) {"Invalid Wireless debugging port"}}
        ?: throw IllegalArgumentException("Enter a numeric port")
    fun code(text:String):ByteArray {require(text.matches(Regex("[0-9]{6}"))) {"Enter Android’s six-digit pairing code"};return text.toByteArray(Charsets.US_ASCII)}
    fun apk(output:String,expected:String):String {
        val paths=output.lineSequence().filter {it.startsWith("package:")}.map {it.removePrefix("package:").trim()}.toList()
        require(expected in paths && expected.startsWith("/data/app/") && expected.endsWith("/base.apk") && expected.matches(Regex("[/A-Za-z0-9_.=+~-]+"))) {"Installed APK path did not match this app"}
        return expected
    }
    fun uid(output:String,expected:Int):Int {
        require(output.lineSequence().any {it.trim()=="package:$PACKAGE uid:$expected"} && expected>=10000) {"Installed package UID did not match this app"}
        return expected
    }
    fun start(apk:String,uid:Int):String {
        apk("package:$apk",apk);require(uid>=10000)
        // Only verified PackageManager data enters this fixed owner-only command.
        return "CLASSPATH='$apk' /system/bin/app_process /system/bin --nice-name=siconnect_privileged dev.siconnect.app.privileged.DirectShellDaemon $uid </dev/null >/data/local/tmp/siconnect-bootstrap.log 2>&1 &"
    }
    fun shellUid(output:String)=output.trim()=="2000"
}
