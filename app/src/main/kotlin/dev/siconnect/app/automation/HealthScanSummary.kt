package dev.siconnect.app.automation

import kotlinx.serialization.json.*
import java.util.Locale

/** Read only recorded output; absent readings never become a healthy status. */
internal object HealthScanSummary {
    fun lines(output:JsonElement):List<String> {
        val root=output as? JsonObject ?: return emptyList()
        fun reading(name:String)=root[name] as? JsonObject
        fun JsonObject.number(key:String)=(get(key) as? JsonPrimitive)?.longOrNull
        fun gib(bytes:Long)=String.format(Locale.getDefault(),"%.1f",bytes/1_073_741_824.0)
        return buildList {
            reading("battery")?.let {battery->
                val percent=battery.number("percent")?.takeIf {it in 0..100}
                val charging=(battery["charging"] as? JsonPrimitive)?.booleanOrNull
                add("Battery: ${percent?.let {"$it%"} ?: "unavailable"}"+when(charging) {true->" · charging";false->" · not charging";null->""})
            }
            reading("memory")?.number("availableBytes")?.takeIf {it>=0}?.let {add("Available memory: ${gib(it)} GiB")}
            reading("storage")?.number("availableBytes")?.takeIf {it>=0}?.let {add("Available storage: ${gib(it)} GiB")}
            reading("network")?.let {network->
                fun flag(key:String)=(network[key] as? JsonPrimitive)?.booleanOrNull
                val transport=when {flag("wifi")==true->"Wi-Fi";flag("cellular")==true->"Mobile data";else->"Network"}
                add(when {flag("connected")==false->"No active network";flag("validated")==true->"$transport · internet validated";flag("connected")==true->"$transport · internet not validated";else->"Network state unavailable"})
            }
            reading("thermal")?.number("status")?.let {status->
                add("Thermal: "+listOf("no throttling reported","light throttling","moderate throttling","severe throttling","critical","emergency","shutdown").getOrElse(status.toInt()) {"status unavailable"})
            }
            (root["warningState"] as? JsonPrimitive)?.content?.let {state->
                if(state=="SUCCEEDED") add("Health warning notification posted")
                // A skipped warning can mean notifications are off; it does not prove every reading is healthy.
                else if(state!="SKIPPED") add("Warning step: ${state.lowercase().replace('_',' ')}")
            }
        }
    }
}
