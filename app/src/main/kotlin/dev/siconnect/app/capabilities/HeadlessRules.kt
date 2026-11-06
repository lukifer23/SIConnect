package dev.siconnect.app.capabilities

import dev.siconnect.app.accessibility.SemanticUiRules
import java.net.URI

/** Shared admission bounds; none of these checks grants Android or agent authority. */
internal object HeadlessRules {
    fun validatePackage(value:String) {
        require(value.length<=200 && value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {"Use an exact installed Android package"}
        require(value !in setOf("dev.siconnect.app","dev.siconnect.app.test","com.android.shell","moe.shizuku.privileged.api") && !SemanticUiRules.protectedPackage(value)) {
            "Owner management, Android permission, lock and biometric surfaces cannot run in private workspaces"
        }
    }
    fun validateSize(width:Int,height:Int,density:Int,ttlMs:Long) {
        require(width in 320..1080 && height in 480..1920 && width.toLong()*height<=2_073_600 && density in 160..480 && ttlMs in 15_000..600_000) {"Private display size, density or lifetime exceeds its bounds"}
    }
    fun validateUri(value:String) {
        val uri=URI(value)
        require(value.length<=2000 && uri.scheme in setOf("https","http","geo") && uri.userInfo==null) {"Use a bounded web/map URI; arbitrary intent/file/content schemes are excluded"}
    }
}
