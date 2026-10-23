package dev.siconnect.app.automation

import dev.siconnect.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.*

/** Templates install ordinary validated skills; execution always uses local-automation policy. */
object AutomationTemplates {
    const val HEALTH_SKILL="diagnostics.snapshot"
    /** Exact previous builtin. Owner-authenticated upgrade never replaces a customized workflow. */
    fun legacyHealth()=SkillDefinition(HEALTH_SKILL,1,"Real native device snapshot",steps=listOf(
        SkillStep("status","device.status"),SkillStep("battery","device.battery"),SkillStep("memory","device.memory")),
        output=buildJsonObject {put("device","\${steps.status.output}");put("battery","\${steps.battery.output}");put("memory","\${steps.memory.output}")})

    fun healthV2():SkillDefinition {
        fun compare(op:String,left:String,right:JsonElement)=buildJsonObject {put("op",op);put("left",left);put("right",right)}
        fun group(op:String,vararg conditions:JsonObject)=buildJsonObject {put("op",op);put("args",JsonArray(conditions.toList()))}
        val warning=group("and",compare("eq","\${input.notifyProblems}",JsonPrimitive(true)),group("or",
            group("and",compare("gte","\${steps.battery.output.percent}",JsonPrimitive(0)),
                compare("lte","\${steps.battery.output.percent}",JsonPrimitive("\${input.minimumBatteryPercent}")),
                compare("eq","\${steps.battery.output.charging}",JsonPrimitive(false))),
            compare("lt","\${steps.storage.output.availableBytes}",JsonPrimitive(2_147_483_648L)),
            compare("eq","\${steps.memory.output.lowMemory}",JsonPrimitive(true)),
            compare("gte","\${steps.thermal.output.status}",JsonPrimitive(3))))
        return SkillDefinition(HEALTH_SKILL,2,"Read battery, memory, storage, network and thermal health without opening the screen. Optionally notify when a threshold is reached.",
            inputs=objectSchema {
                prop("notifyProblems",false,buildJsonObject {put("type","boolean");put("description","Notify about health warnings");put("default",false)})
                prop("minimumBatteryPercent",false,buildJsonObject {put("type","integer");put("description","Low battery threshold (%)");put("minimum",1);put("maximum",99);put("default",20)})
            },steps=legacyHealth().steps+listOf(SkillStep("storage","device.storage"),SkillStep("network","device.network"),SkillStep("thermal","device.thermal"),
                SkillStep("warn","notifications.post",buildJsonObject {put("title","Device health needs attention");put("text","A health scan found a battery, storage, memory or temperature warning. Review the run in SIConnect Automations.")},`when`=warning)),
            output=JsonObject(legacyHealth().output+buildJsonObject {
                put("storage","\${steps.storage.output}");put("network","\${steps.network.output}");put("thermal","\${steps.thermal.output}");put("warningState","\${steps.warn.state}")
            }))
    }

    fun health():SkillDefinition {
        val old=healthV2()
        val extra=objectSchema {
            prop("cooldownMinutes",false,buildJsonObject {put("type","integer");put("description","Warning cooldown (minutes)");put("minimum",15);put("maximum",1440);put("default",60)})
            prop("repeatPersistent",false,buildJsonObject {put("type","boolean");put("description","Remind about unchanged warnings after cooldown");put("default",false)})
        }
        val properties=JsonObject(old.inputs.getValue("properties").jsonObject+extra.getValue("properties").jsonObject)
        val healthArgs=buildJsonObject {
            listOf("battery","memory","storage","thermal").forEach {put(it,"\${steps.$it.output}")}
            put("minimumBatteryPercent","\${input.minimumBatteryPercent}");put("cooldownMinutes","\${input.cooldownMinutes}");put("repeatPersistent","\${input.repeatPersistent}")
        }
        return old.copy(version=3,inputs=JsonObject(old.inputs+("properties" to properties)),
            steps=old.steps.dropLast(1)+old.steps.last().copy(args=JsonObject(old.steps.last().args+("healthCheck" to healthArgs)),
                `when`=buildJsonObject {put("op","eq");put("left","\${input.notifyProblems}");put("right",true)}),
            output=JsonObject(old.output+("warning" to JsonPrimitive("\${steps.warn.output}"))))
    }

    fun upgradeableHealth(definition:SkillDefinition)=definition==legacyHealth() || definition==healthV2()
    fun requestedCapabilities(definition:SkillDefinition,inputs:JsonObject):List<String> {
        val skipWarning=definition.id==HEALTH_SKILL && (definition==health() || definition==healthV2()) && inputs["notifyProblems"]?.jsonPrimitive?.booleanOrNull!=true
        return definition.steps.filterNot {skipWarning && it.id=="warn"}.map {it.capability}.distinct()
    }

    const val NOTICE_SKILL="reminders.recurring_notice"
    fun notice()=SkillDefinition(NOTICE_SKILL,1,"Post a native reminder notification when the generic schedule fires.",
        inputs=objectSchema {
            for((key,label,max) in listOf(Triple("title","Reminder title",150),Triple("text","Reminder text",1000)))
                prop(key,true,buildJsonObject {put("type","string");put("description",label);put("minLength",1);put("maxLength",max)})
        },steps=listOf(SkillStep("notify","notifications.post",buildJsonObject {
            put("title",JsonPrimitive("\${input.title}"));put("text",JsonPrimitive("\${input.text}"))
        })),output=buildJsonObject {put("notification",JsonPrimitive("\${steps.notify.output}"))})

    fun title(skill:SkillDefinition)=when(skill.id) {
        NOTICE_SKILL->"Reminder"
        "diagnostics.snapshot"->"Device health snapshot"
        "weather.commute_check"->"Commute weather"
        else->skill.id.replace('.',' ').replace('_',' ')
    }
    fun initialInputs(skill:SkillDefinition,zoneId:String):JsonObject {
        val defaults=defaults(skill.inputs).toMutableMap()
        if(skill.id=="weather.commute_check") {
            defaults["timezone"]=JsonPrimitive(zoneId)
            defaults["useNextAlarm"]=JsonPrimitive(true)
            defaults["rainThresholdPercent"]=JsonPrimitive(60)
            defaults["snowThresholdCm"]=JsonPrimitive(1)
            defaults["minimumTemperatureC"]=JsonPrimitive(0)
            defaults["reminderOffsetMinutes"]=JsonPrimitive(0)
            defaults["notifyWinter"]=JsonPrimitive(true);defaults["notifyCold"]=JsonPrimitive(true)
            defaults["remindRain"]=JsonPrimitive(true);defaults["exactReminder"]=JsonPrimitive(false)
        }
        return JsonObject(defaults)
    }
    fun defaults(schema:JsonObject):JsonObject=JsonObject((schema["properties"] as? JsonObject).orEmpty().mapNotNull {(key,value)->
        val field=value.jsonObject
        field["default"]?.let {key to it} ?: if(field["type"]?.jsonPrimitive?.content=="object") {
            defaults(field).takeIf {it.isNotEmpty()}?.let {key to it}
        } else null
    }.toMap())
}

/** Saveable form model. Stable id/revision keep edits from overwriting concurrent owner changes. */
@Serializable
data class AutomationDraft(
    val id:String,val label:String,val skillId:String,val inputs:JsonObject,
    val recurrenceKind:RecurrenceKind=RecurrenceKind.DAILY,val localTime:String="09:00",
    val startDate:String=LocalDate.now().toString(),val zoneId:String=ZoneId.systemDefault().id,
    val intervalMinutes:String="60",val weekdays:List<Int> = listOf(1,2,3,4,5),val dayOfMonth:String="1",
    val exact:Boolean=false,val enabled:Boolean=true,val missedPolicy:MissedRunPolicy=MissedRunPolicy.COALESCE,
    val maxLatenessMinutes:String="60",val revision:Long=0,
) {
    fun effectiveInputs():JsonObject=when(skillId) {
        AutomationTemplates.NOTICE_SKILL->JsonObject(inputs+("title" to JsonPrimitive(label.trim())))
        "weather.commute_check"->JsonObject(inputs+("timezone" to JsonPrimitive(zoneId)))
        else->inputs
    }
    fun recurrence():Recurrence=when(recurrenceKind) {
        RecurrenceKind.ONCE->Recurrence(RecurrenceKind.ONCE)
        RecurrenceKind.FIXED_INTERVAL->Recurrence(RecurrenceKind.FIXED_INTERVAL,intervalMs=Math.multiplyExact(requireNotNull(intervalMinutes.toLongOrNull()) {"Enter the interval in minutes"},60_000))
        RecurrenceKind.DAILY->Recurrence(RecurrenceKind.DAILY,localTime=localTime)
        RecurrenceKind.WEEKDAYS->Recurrence(RecurrenceKind.WEEKDAYS,localTime=localTime,weekdays=weekdays.sorted())
        RecurrenceKind.WEEKLY->Recurrence(RecurrenceKind.WEEKLY,localTime=localTime,weekdays=weekdays.take(1))
        RecurrenceKind.MONTHLY->Recurrence(RecurrenceKind.MONTHLY,localTime=localTime,dayOfMonth=dayOfMonth.toIntOrNull())
    }.also {it.validate()}
    fun build(nowMs:Long,original:Automation?=null):Automation {
        require(label.isNotBlank() && label.length<=120) {"Give this automation a name (up to 120 characters)"}
        val zone=ZoneId.of(zoneId);var rule=recurrence()
        val originalDraft=original?.let(::from)
        val timingUnchanged=originalDraft!=null && recurrenceKind==originalDraft.recurrenceKind && localTime==originalDraft.localTime && startDate==originalDraft.startDate && zoneId==originalDraft.zoneId && intervalMinutes==originalDraft.intervalMinutes && weekdays==originalDraft.weekdays && dayOfMonth==originalDraft.dayOfMonth
        if(timingUnchanged) rule=original!!.recurrence ?: Recurrence(if(original.intervalMs==null) RecurrenceKind.ONCE else RecurrenceKind.FIXED_INTERVAL,intervalMs=original.intervalMs)
        val next=if(timingUnchanged) original!!.nextAtMs else if(rule.kind in setOf(RecurrenceKind.ONCE,RecurrenceKind.FIXED_INTERVAL)) {
            Recurrence.oneTime("$startDate"+"T"+LocalTime.parse(localTime),zone.id).also {
                require(!enabled || it>nowMs) {"Choose a future start time"}
            }
        } else requireNotNull(rule.nextAfter(nowMs,nowMs,zone.id))
        val lateness=requireNotNull(maxLatenessMinutes.toLongOrNull()) {"Enter the maximum delay in minutes"}
        require(lateness in 1..1440) {"Maximum delay must be between 1 minute and 24 hours"}
        if(skillId=="weather.commute_check" && rule.kind!=RecurrenceKind.ONCE)
            require("targetDate" !in inputs) {"Recurring commute checks use tomorrow each run; clear the fixed target date"}
        val savedInputs=effectiveInputs()
        return Automation(id,skillId,next,enabled=enabled,exact=exact,zoneId=zone.id,recurrence=rule,
            inputs=savedInputs,missedPolicy=missedPolicy,maxLatenessMs=if(original!=null && maxLatenessMinutes==(original.maxLatenessMs/60_000).toString()) original.maxLatenessMs else lateness*60_000,revision=revision,
            lastRunId=original?.lastRunId,lastState=original?.lastState,label=label.trim())
    }
    fun preview(nowMs:Long,original:Automation?=null):List<Long> {
        val automation=build(nowMs,original);val times=mutableListOf(automation.nextAtMs)
        repeat(2) {val next=automation.nextAfter(times.last()) ?: return times;times+=next}
        return times
    }
    companion object {
        fun from(a:Automation):AutomationDraft {
            val rule=a.recurrence ?: Recurrence(if(a.intervalMs==null) RecurrenceKind.ONCE else RecurrenceKind.FIXED_INTERVAL,intervalMs=a.intervalMs)
            val first=Instant.ofEpochMilli(a.nextAtMs).atZone(ZoneId.of(a.zoneId))
            return AutomationDraft(a.id,a.label.ifBlank {a.id},a.skillId,a.inputs,rule.kind,rule.localTime ?: first.toLocalTime().withSecond(0).withNano(0).toString(),
                first.toLocalDate().toString(),a.zoneId,((rule.intervalMs ?: 3_600_000)/60_000).toString(),
                rule.weekdays.takeIf {it.isNotEmpty()} ?: listOf(1,2,3,4,5),(rule.dayOfMonth ?: 1).toString(),
                a.exact,a.enabled,a.missedPolicy,(a.maxLatenessMs/60_000).toString(),a.revision)
        }
    }
}
