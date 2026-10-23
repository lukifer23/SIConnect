package dev.siconnect.app.automation

import dev.siconnect.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class HealthIssueState(val severity:Int=0,val lastAlertAtMs:Long=0,val lastAlertSeverity:Int=0)

internal data class HealthAlertEvaluation(
    val states:Map<String,HealthIssueState>,val active:List<String>,val unavailable:List<String>,
    val cleared:List<String>,val notify:Map<String,String>,val suppressed:Map<String,String>,val nextEligibleAtMs:Long?,
)

/** Deterministic condition identities and hysteresis; no text matching, I/O or execution authority. */
internal object HealthAlertPolicy {
    val ids=listOf("battery_low","storage_low","memory_low","thermal_high")
    fun schema()=objectSchema {
        integer("minimumBatteryPercent","Low battery threshold",true,min=1,max=99)
        integer("cooldownMinutes","Minimum repeat interval",true,min=15,max=1440)
        boolean("repeatPersistent","Remind about unchanged warnings after cooldown")
        listOf("battery","storage","memory","thermal").forEach {prop(it,false,buildJsonObject {put("type","object")})}
    }
    private fun JsonObject.number(key:String)=(get(key) as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull
    private fun JsonObject.flag(key:String)=(get(key) as? JsonPrimitive)?.takeUnless {it.isString}?.booleanOrNull
    fun evaluate(args:JsonObject,previous:Map<String,HealthIssueState>,now:Long):HealthAlertEvaluation {
        require(Schema.validate(schema(),args).isEmpty()) {"Invalid health alert policy"}
        val minimum=args.getValue("minimumBatteryPercent").jsonPrimitive.int
        val cooldown=args.getValue("cooldownMinutes").jsonPrimitive.long*60_000
        val repeat=args.flag("repeatPersistent")==true
        fun reading(name:String)=args[name] as? JsonObject ?: JsonObject(emptyMap())
        fun active(id:String)=(previous[id]?.severity ?: 0)>0
        val battery=reading("battery");val percent=battery.number("percent")?.takeIf {it in 0..100}
        val charging=battery.flag("charging")
        val storage=reading("storage").number("availableBytes")?.takeIf {it>=0}
        val thermal=reading("thermal").number("status")?.takeIf {it in 0..6}
        val observed=mapOf(
            "battery_low" to when {
                charging==true->0
                percent==null || charging==null->null
                percent<=minimum || (active("battery_low") && percent<(minimum+3).coerceAtMost(100))->if(percent<=5) 2 else 1
                else->0
            },
            "storage_low" to storage?.let {if(it<2_147_483_648L || (active("storage_low") && it<2_684_354_560L)) if(it<268_435_456L) 2 else 1 else 0},
            "memory_low" to reading("memory").flag("lowMemory")?.let {if(it) 1 else 0},
            "thermal_high" to thermal?.let {when {it>=5->3;it>=4->2;it>=3 || (active("thermal_high") && it>=2)->1;else->0}},
        )
        val states=previous.toMutableMap();val unavailable=mutableListOf<String>();val cleared=mutableListOf<String>()
        val notify=linkedMapOf<String,String>();val suppressed=linkedMapOf<String,String>();val next=mutableListOf<Long>()
        ids.forEach {id->
            val old=previous[id] ?: HealthIssueState();val severity=observed[id]
            if(severity==null) {unavailable+=id;return@forEach} // Unknown never clears or creates an issue.
            var state=old.copy(severity=severity)
            if(severity==0) {if(old.severity>0) cleared+=id}
            else {
                // A backwards wall-clock adjustment must not suppress an alert for months.
                val elapsed=if(old.lastAlertAtMs==0L || now<old.lastAlertAtMs) Long.MAX_VALUE else now-old.lastAlertAtMs
                val interval=if(severity>=2) minOf(cooldown,3_600_000L) else cooldown
                val worsened=severity>old.severity && severity>old.lastAlertSeverity
                val reason=when {
                    (old.severity==0 || old.lastAlertAtMs==0L) && elapsed>=cooldown->"NEW_ISSUE"
                    worsened && elapsed>=60_000->"WORSENED"
                    (repeat || severity>=2) && elapsed>=interval->"REMINDER"
                    elapsed<interval->"COOLDOWN"
                    else->"UNCHANGED"
                }
                if(reason in setOf("NEW_ISSUE","WORSENED","REMINDER")) {
                    notify[id]=reason;state=state.copy(lastAlertAtMs=now,lastAlertSeverity=severity)
                } else {
                    suppressed[id]=reason
                    if(reason=="COOLDOWN") next+=old.lastAlertAtMs+interval
                }
            }
            states[id]=state
        }
        return HealthAlertEvaluation(states,ids.filter {(states[it]?.severity ?: 0)>0},unavailable,cleared,notify,suppressed,next.minOrNull())
    }
}
