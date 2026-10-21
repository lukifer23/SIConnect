package dev.siconnect.app.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import dev.siconnect.core.*
import dev.siconnect.app.data.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
private data class PackageInspection(
    val packageName:String, val user:Int, val installed:Boolean,
    val versionName:String?=null, val versionCode:Long?=null, val enabled:Boolean?=null,
    val installerPackage:String?=null, val initiatingPackage:String?=null, val originatingPackage:String?=null,
    val firstInstallTimeMs:Long?=null, val lastUpdateTimeMs:Long?=null,
    val completeness:String="COMPLETE", val errors:List<String> = emptyList(),
    val source:String="Android PackageManager", val verifiedAtMs:Long=System.currentTimeMillis(),
)

/** Bounded authoritative metadata, without parsing dumpsys or traversing other Android users. */
class PackageInspectionCapability(private val context:Context):Capability {
    override val descriptor=CapabilityDescriptor("apps.inspect_detailed",title="Inspect package and installation source",
        description="Structured package/version/enabled/installer metadata for Android user 0. Confirms installation state with a second PackageManager query. No raw dumps, app data, or Secure Folder access. Use this instead of dumpsys to verify installs and removals.",
        inputSchema=objectSchema {string("packageName","Exact package identifier",true,maxLength=200);integer("user","Android user (only user 0 is supported)",min=0,max=0)},
        outputSchema=objectSchema {
            string("packageName","Package identifier",true);integer("user","Android user",true);boolean("installed","Installed in this user",true)
            string("versionName","Version name");integer("versionCode","Long version code");boolean("enabled","Application enabled")
            string("installerPackage","Installing package");string("initiatingPackage","Initiating package");string("originatingPackage","Originating package")
            integer("firstInstallTimeMs","First installation timestamp");integer("lastUpdateTimeMs","Last update timestamp")
            string("completeness","Metadata completeness",true,enum=listOf("COMPLETE","PARTIAL"));stringArray("errors","Metadata error codes",true)
            string("source","Metadata source",true);integer("verifiedAtMs","Observation timestamp",true)
        },backend=BackendKind.NATIVE,risk=RiskClass.READ_ONLY,interaction=InteractionMode.BACKGROUND,verification="Second PackageManager query confirms package state")
    override fun availability()=Availability.IMPLEMENTED
    private val pm get()=context.packageManager
    private fun info(name:String):PackageInfo? = try {
        if(Build.VERSION.SDK_INT>=33) pm.getPackageInfo(name,PackageManager.PackageInfoFlags.of(0)) else pm.getPackageInfo(name,0)
    } catch(_:PackageManager.NameNotFoundException) {null}
    override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {
        val name=args.str("packageName");val user=args.long("user",0).toInt()
        if(!name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*"))) throw CapabilityException("INVALID_PACKAGE","Invalid package identifier")
        if(user!=0 || Process.myUserHandle()!=android.os.UserHandle.getUserHandleForUid(0)) throw CapabilityException("UNSUPPORTED_USER","This operation inspects only the primary Android user 0; other profiles are isolated")
        if(context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES)!=PackageManager.PERMISSION_GRANTED) throw CapabilityException("PACKAGE_VISIBILITY_REQUIRED","Package visibility is required for authoritative absence checks")
        val errors=mutableListOf<String>()
        val first=info(name)
        val source=if(first!=null) try {pm.getInstallSourceInfo(name)} catch(_:SecurityException) {
            errors+="INSTALL_SOURCE_PERMISSION_DENIED";null
        } catch(_:PackageManager.NameNotFoundException) {errors+="PACKAGE_CHANGED_DURING_SOURCE_QUERY";null} else null
        val second=info(name)
        val unchanged=first?.let { a -> second?.let { b -> a.longVersionCode==b.longVersionCode && a.versionName==b.versionName && a.lastUpdateTime==b.lastUpdateTime && a.firstInstallTime==b.firstInstallTime && a.applicationInfo?.enabled==b.applicationInfo?.enabled } ?: false } ?: (second==null)
        if(!unchanged) errors+="PACKAGE_CHANGED_DURING_QUERY"
        val result=PackageInspection(name,user,first!=null,first?.versionName,first?.longVersionCode,first?.applicationInfo?.enabled,
            source?.installingPackageName,source?.initiatingPackageName,source?.originatingPackageName,
            first?.firstInstallTime,first?.lastUpdateTime,if(errors.isEmpty()) "COMPLETE" else "PARTIAL",errors)
        return CapabilityResult(AppJson.encodeToJsonElement(PackageInspection.serializer(),result),BackendKind.NATIVE,"uid=${Process.myUid()}",
            if(unchanged) VerificationStatus.VERIFIED else VerificationStatus.NOT_VERIFIED,
            if(unchanged) "Installation/version/enabled state matched two PackageManager queries; metadata completeness is reported separately" else "Package changed during inspection; query again",
            if(unchanged) null else "PACKAGE_CHANGED")
    }
}
