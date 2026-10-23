package dev.siconnect.app.automation

import kotlinx.serialization.json.*
import java.util.Locale

/** Read only recorded output; absent readings never become a healthy status. */
internal object HealthScanSummary {
    fun lines(output:JsonElement,inputs:JsonObject=JsonObject(emptyMap())):List<String> {
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
            if("memory" in root) add("Available memory: "+(reading("memory")?.number("availableBytes")?.takeIf {it>=0}?.let {"${gib(it)} GiB"} ?: "unavailable"))
            if("storage" in root) add("Available storage: "+(reading("storage")?.number("availableBytes")?.takeIf {it>=0}?.let {"${gib(it)} GiB"} ?: "unavailable"))
            reading("network")?.let {network->
                fun flag(key:String)=(network[key] as? JsonPrimitive)?.booleanOrNull
                val transport=when {flag("wifi")==true->"Wi-Fi";flag("cellular")==true->"Mobile data";else->"Network"}
                add(when {flag("connected")==false->"No active network";flag("validated")==true->"$transport · internet validated";flag("connected")==true->"$transport · internet not validated";else->"Network state unavailable"})
            }
            if("thermal" in root) {
                val status=reading("thermal")?.number("status")?.takeIf {it in 0..6}
                add("Thermal: "+(status?.let {listOf("no throttling reported","light throttling","moderate throttling","severe throttling","critical","emergency","shutdown")[it.toInt()]} ?: "unavailable"))
            }
            val warning=root["warning"] as? JsonObject
            val alert=warning?.get("healthAlert") as? JsonObject
            if(alert!=null) {
                fun names(key:String)=(alert[key] as? JsonArray).orEmpty().joinToString {it.jsonPrimitive.content.replace('_',' ')}
                val active=names("activeIssues");add(if(active.isEmpty()) "No active warning condition recorded" else "Conditions: $active")
                if(names("unavailableReadings").isNotEmpty()) add("Unavailable checks: ${names("unavailableReadings")}")
                if(names("clearedIssues").isNotEmpty()) add("Cleared: ${names("clearedIssues")}")
                add(when(alert["reason"]?.jsonPrimitive?.content) {
                    "POSTED"->"Health warning notification posted"
                    "COOLDOWN"->"No notification: warning cooldown is active"
                    "UNCHANGED"->"No notification: persistent conditions are unchanged"
                    "NO_ACTIVE_ISSUES"->"No notification: no active warning condition"
                    "READINGS_UNAVAILABLE"->"No notification: health readings are unavailable"
                    else->"Notification decision unavailable"
                })
                alert["nextEligibleAtMs"]?.jsonPrimitive?.longOrNull?.let {add("Next alert eligible: "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(it)))}
                if(alert["previousAlertOutcome"]?.jsonPrimitive?.content=="CLAIMED") add("A previous alert's delivery was not verified. Its original request is retained.")
            }
            (root["warningState"] as? JsonPrimitive)?.content?.let {state->
                if(alert!=null) return@let
                if(state=="SUCCEEDED") add("Health warning notification posted")
                else if(state=="SKIPPED") add(if(inputs["notifyProblems"]?.jsonPrimitive?.booleanOrNull!=true) "Warning notifications were off for this run" else "No warning notification dispatched; review readings")
                // A skipped warning can mean notifications are off; it does not prove every reading is healthy.
                else if(state!="SKIPPED") add("Warning step: ${state.lowercase().replace('_',' ')}")
            }
        }
    }
}
