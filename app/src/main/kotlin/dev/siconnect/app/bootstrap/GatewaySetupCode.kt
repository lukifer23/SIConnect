package dev.siconnect.app.bootstrap

import kotlinx.serialization.json.*
import java.net.URI
import java.util.Base64

internal data class GatewaySetupCode(val endpoint:String,val enrollment:String) {
    companion object {
        fun parse(input:String):GatewaySetupCode {
            require(input.length in 1..4096) {"Setup code must fit within 4 KiB"}
            val text=input.trim().let {if(it.startsWith("{")) it else String(Base64.getUrlDecoder().decode(it),Charsets.UTF_8)}
            val code=Json.parseToJsonElement(text).jsonObject
            val endpoint=code.getValue("url").jsonPrimitive.content.trimEnd('/')
            val uri=URI(endpoint)
            require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.rawQuery==null && uri.fragment==null && uri.path.isNullOrBlank()) {"Use the HTTPS origin of your existing SIConnect gateway, without credentials, query, or path"}
            val token=code.getValue("enroll").jsonPrimitive.content
            require(token.matches(Regex("[A-Za-z0-9_-]{20,300}"))) {"Invalid gateway enrollment credential"}
            return GatewaySetupCode(endpoint,token)
        }
    }
}
