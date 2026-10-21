package dev.siconnect.app.capabilities

import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.data.AppJson
import dev.siconnect.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.*

@Serializable
data class WeatherCommutePlan(
    val latitude: Double, val longitude: Double, val timezone: String, val targetDate: String,
    val startHour: Int, val endHour: Int, val windowStartMs: Long, val windowEndMs: Long,
    val evaluatedAtMs: Long, val wakeAtMs: Long, val departureAtMs: Long, val reminderAtMs: Long,
    val wakeSource: String, val rainThresholdPercent: Double, val snowThresholdCm: Double,
    val minimumTemperatureC: Double, val notifyWinter: Boolean, val notifyCold: Boolean,
    val remindRain: Boolean, val exactReminder: Boolean, val deduplicationKey: String,
)

/** Pure planning/branching. Production SkillDefinition obtains its forecast from weather.forecast. */
object WeatherCommute {
    val inputs = objectSchema {
        number("latitude", "Explicit location latitude; otherwise owner-configured weather location", min=-90.0,max=90.0)
        number("longitude", "Explicit location longitude; otherwise owner-configured weather location", min=-180.0,max=180.0)
        string("timezone", "Pinned IANA timezone", true,maxLength=100)
        string("wakeTime", "Configured fallback wake time HH:mm; never assumed", true,maxLength=8)
        string("departureTime", "Expected local departure HH:mm", true,maxLength=8)
        string("targetDate", "Optional target date YYYY-MM-DD; otherwise tomorrow in selected timezone",maxLength=10)
        boolean("useNextAlarm", "Use next visible Clock alarm only if it belongs to target date and precedes departure (default true)")
        integer("startHour", "Explicit forecast window start hour; otherwise wake hour",min=0,max=23)
        integer("endHour", "Exclusive window end hour; otherwise departure rounded upward",min=1,max=24)
        number("rainThresholdPercent", "Rain-probability threshold; default 60 percent",min=1.0,max=100.0)
        number("snowThresholdCm", "Expected snowfall threshold; default 1 cm",min=0.01,max=1000.0)
        number("minimumTemperatureC", "Cold-clothing threshold; default 0 C",min=-100.0,max=60.0)
        integer("reminderOffsetMinutes", "Offset from selected wake time; default zero",min=-180,max=180)
        boolean("notifyWinter", "Evening notification for snowfall/freezing precipitation; default true")
        boolean("notifyCold", "Evening clothing notification below temperature threshold; default true")
        boolean("remindRain", "Create a morning rain reminder; default true")
        boolean("exactReminder", "Use exact AlarmManager delivery when authorized; default false (deferrable)")
    }
    private fun JsonObject.bool(key:String,default:Boolean) = this[key]?.jsonPrimitive?.booleanOrNull ?: default
    private fun JsonObject.number(key:String,default:Double) = this[key]?.jsonPrimitive?.doubleOrNull ?: default

    fun plan(config:JsonObject, evaluatedAtMs:Long, nextAlarmMs:Long?, defaultLatitude:Double?=null,
             defaultLongitude:Double?=null):WeatherCommutePlan {
        if(Schema.validate(inputs,config).isNotEmpty()) throw CapabilityException("INVALID_ARGUMENT","Invalid commute inputs")
        val lat=config["latitude"]?.jsonPrimitive?.doubleOrNull ?: defaultLatitude
            ?: throw CapabilityException("LOCATION_REQUIRED","Configure weather location or supply latitude/longitude")
        val lon=config["longitude"]?.jsonPrimitive?.doubleOrNull ?: defaultLongitude
            ?: throw CapabilityException("LOCATION_REQUIRED","Configure weather location or supply latitude/longitude")
        if(!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0)
            throw CapabilityException("INVALID_LOCATION","Coordinates are outside supported bounds")
        val zone=ZoneId.of(config.str("timezone")); val today=Instant.ofEpochMilli(evaluatedAtMs).atZone(zone).toLocalDate()
        val date=config["targetDate"]?.jsonPrimitive?.content?.let(LocalDate::parse) ?: today.plusDays(1)
        if(date<today || date>today.plusDays(6)) throw CapabilityException("FORECAST_WINDOW_UNAVAILABLE","Commute target must be within the next seven local dates")
        fun instant(time:LocalTime)=date.atTime(time).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val departure=instant(LocalTime.parse(config.str("departureTime")))
        val fallback=instant(LocalTime.parse(config.str("wakeTime")))
        val alarm=nextAlarmMs?.takeIf { it>evaluatedAtMs && it<=departure && Instant.ofEpochMilli(it).atZone(zone).toLocalDate()==date }
            ?.takeIf { config.bool("useNextAlarm",true) }
        val wake=alarm ?: fallback
        if(wake>departure) throw CapabilityException("INVALID_COMMUTE_TIMING","Wake time must not follow departure")
        val wakeLocal=Instant.ofEpochMilli(wake).atZone(zone).toLocalTime()
        val departureLocal=Instant.ofEpochMilli(departure).atZone(zone).toLocalTime()
        val start=config.long("startHour",wakeLocal.hour.toLong()).toInt()
        val end=config.long("endHour",(departureLocal.hour+if(departureLocal.minute>0 || departureLocal.second>0) 1 else 0).toLong()).toInt()
        val bounds=ForecastWindow.bounds(date.toString(),zone.id,start,end)
        val reminder=Math.addExact(wake,config.long("reminderOffsetMinutes",0)*60_000)
        val rain=config.number("rainThresholdPercent",60.0);val snow=config.number("snowThresholdCm",1.0)
        val cold=config.number("minimumTemperatureC",0.0)
        // No coordinates in event logs; this opaque identity is scoped by invoking agent in native mutations.
        val fingerprint=Canonical.sha256Hex(Canonical.encode(buildJsonObject {
            put("latitude",lat);put("longitude",lon);put("zone",zone.id);put("wakeTime",config.str("wakeTime"));put("departureTime",config.str("departureTime"))
            put("startHour",start);put("endHour",end);put("rain",rain);put("snow",snow);put("cold",cold)
            put("offset",config.long("reminderOffsetMinutes",0));put("exact",config.bool("exactReminder",false))
        })).take(24)
        return WeatherCommutePlan(lat,lon,zone.id,date.toString(),start,end,bounds.first.toEpochMilli(),bounds.second.toEpochMilli(),
            evaluatedAtMs,wake,departure,reminder,if(alarm!=null) "NEXT_VISIBLE_ALARM" else "CONFIGURED_FALLBACK",rain,snow,cold,
            config.bool("notifyWinter",true),config.bool("notifyCold",true),config.bool("remindRain",true),config.bool("exactReminder",false),
            "commute:$date:$fingerprint")
    }

    fun decide(plan:WeatherCommutePlan, forecast:JsonObject, nowMs:Long):JsonObject {
        fun number(key:String)=forecast[key]?.jsonPrimitive?.doubleOrNull?.takeIf(Double::isFinite)
        fun status(reason:String)=buildJsonObject {
            put("status",reason);put("notifyEvening",false);put("createMorningReminder",false)
            put("targetDate",plan.targetDate);put("deduplicationKey",plan.deduplicationKey)
            put("guidance","No weather alert/reminder created; inspect source status and retrieve a fresh complete window")
        }
        val fetched=forecast["fetchedAtMs"]?.jsonPrimitive?.longOrNull
        val expires=forecast["expiresAtMs"]?.jsonPrimitive?.longOrNull
        if(fetched==null || expires==null || nowMs<fetched || nowMs>=expires || forecast["stale"]?.jsonPrimitive?.booleanOrNull!=false)
            return status("STALE_OR_UNDATED_FORECAST")
        val sameZone=runCatching { ZoneId.of(forecast["forecastTimezone"]?.jsonPrimitive?.content).rules==ZoneId.of(plan.timezone).rules }.getOrDefault(false)
        if(forecast["summaryScope"]?.jsonPrimitive?.content!="EXPLICIT_WINDOW" ||
            forecast["windowStartMs"]?.jsonPrimitive?.longOrNull!=plan.windowStartMs ||
            forecast["windowEndMs"]?.jsonPrimitive?.longOrNull!=plan.windowEndMs ||
            !sameZone)
            return status("FORECAST_WINDOW_MISMATCH")
        val rain=number("maxRainProbability");val snow=number("expectedSnowCm");val temperature=number("minimumTemperatureC")
        val codes=(forecast["weatherCodes"] as? JsonArray)?.mapNotNull {it.jsonPrimitive.intOrNull}
        val units=forecast["units"] as? JsonObject
        if(forecast["completeness"]?.jsonPrimitive?.content!="COMPLETE" || rain==null || snow==null || temperature==null || codes.isNullOrEmpty() ||
            rain !in 0.0..100.0 || snow<0 || units?.get("temperature")?.jsonPrimitive?.content!="C" ||
            units?.get("snowfall")?.jsonPrimitive?.content!="cm" || units?.get("probability")?.jsonPrimitive?.content!="percent")
            return status("INCOMPLETE_FORECAST")
        if(nowMs>=plan.departureAtMs) return status("COMMUTE_WINDOW_PASSED")
        val winter=snow>=plan.snowThresholdCm || codes.any {it in setOf(56,57,66,67)}
        val cold=temperature<plan.minimumTemperatureC
        val raining=rain>=plan.rainThresholdPercent
        val evening=(winter && plan.notifyWinter) || (cold && plan.notifyCold)
        val morning=raining && plan.remindRain && plan.reminderAtMs>nowMs
        val winterText="Snow or freezing precipitation is forecast before departure. Prepare ahead of departure and consider leaving earlier. Road and traffic conditions have not been checked."
        val coldText="Cold weather is forecast during your commute. Prepare warm layers."
        return buildJsonObject {
            put("status",if(evening || morning) "ACTIONABLE" else "NO_ACTION")
            put("targetDate",plan.targetDate);put("timezone",plan.timezone);put("deduplicationKey",plan.deduplicationKey)
            put("winterConditions",winter);put("coldConditions",cold);put("rainExpected",raining)
            put("maxRainProbability",rain);put("expectedSnowCm",snow);put("minimumTemperatureC",temperature)
            put("notifyEvening",evening);put("createMorningReminder",morning)
            put("morningReminderState",if(raining && plan.remindRain && plan.reminderAtMs<=nowMs) "WAKE_REMINDER_TIME_PASSED" else if(morning) "ELIGIBLE" else "NOT_NEEDED")
            put("notificationTitle","Commute weather for ${plan.targetDate}")
            put("notificationText",if(winter) winterText else coldText)
            put("reminderTitle","Commute rain reminder")
            put("reminderText","Rain is forecast during your commute. Bring a raincoat or umbrella." + if(cold) " Wear warm layers." else "")
            put("reminderAtMs",plan.reminderAtMs);put("exactReminder",plan.exactReminder)
            put("sourceFetchedAtMs",fetched);put("sourceExpiresAtMs",expires)
            put("verification","Forecast thresholds only; road conditions, traffic and delivery to the owner are not verified")
        }
    }

    private fun ref(value:String)=JsonPrimitive("\${$value}")
    private fun truthy(value:String)=buildJsonObject {put("op","truthy");put("value",ref(value))}

    /** Install through the existing SkillCatalog. No independent weather scheduler or native-code loader. */
    fun skill()=SkillDefinition("weather.commute_check",1,"Tomorrow’s forecast → deterministic winter/cold notification and wake-time rain reminder. Configure an evening DAILY generic automation in the same pinned timezone.",
        inputs=inputs,timeoutMs=120_000,steps=listOf(
            SkillStep("alarm","alarms.next"),
            SkillStep("plan","weather.commute_plan",buildJsonObject {
                put("config",ref("input"));put("evaluatedAtMs",ref("now.epoch_ms"));put("nextAlarm",ref("steps.alarm.output"))
            }),
            SkillStep("forecast","weather.forecast",buildJsonObject {
                for(field in listOf("latitude","longitude","timezone","targetDate","startHour","endHour")) put(field,ref("steps.plan.output.$field"))
            }),
            SkillStep("decision","weather.commute_decide",buildJsonObject {
                put("plan",ref("steps.plan.output"));put("forecast",ref("steps.forecast.output"))
            }),
            SkillStep("evening","notifications.post",buildJsonObject {
                put("title",ref("steps.decision.output.notificationTitle"));put("text",ref("steps.decision.output.notificationText"))
                put("deduplicationKey",ref("steps.plan.output.deduplicationKey"))
            },`when`=truthy("steps.decision.output.notifyEvening")),
            SkillStep("morning","reminders.create",buildJsonObject {
                put("title",ref("steps.decision.output.reminderTitle"));put("text",ref("steps.decision.output.reminderText"))
                put("fireAtMs",ref("steps.plan.output.reminderAtMs"));put("exact",ref("steps.plan.output.exactReminder"))
                put("deduplicationKey",ref("steps.plan.output.deduplicationKey"))
            },`when`=truthy("steps.decision.output.createMorningReminder"))
        ),output=buildJsonObject {put("plan",ref("steps.plan.output"));put("decision",ref("steps.decision.output"));put("evening",ref("steps.evening"));put("morning",ref("steps.morning"))})
}

fun registerWeatherCommuteCapabilities(g:RuntimeGraph) {
    fun register(id:String,title:String,schema:JsonObject,body:(JsonObject)->JsonObject) {
        g.registry.register(object:Capability {
            override val descriptor=CapabilityDescriptor(id,title=title,description="$title. Deterministic calculation; no notification, reminder or Clock alarm is changed by this calculation.",
                inputSchema=schema,backend=BackendKind.NATIVE,risk=RiskClass.READ_ONLY,interaction=InteractionMode.BACKGROUND)
            override fun availability()=Availability.IMPLEMENTED
            override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {
                val output=try { body(args) } catch(e:DateTimeException) {
                    throw CapabilityException("INVALID_COMMUTE_TIMING","Invalid local date, time or timezone")
                } catch(e:kotlinx.serialization.SerializationException) {
                    throw CapabilityException("INVALID_COMMUTE_PLAN","Incomplete or malformed commute plan")
                }
                return CapabilityResult(output,BackendKind.NATIVE,"uid=${android.os.Process.myUid()}",
                    VerificationStatus.VERIFIED,"Deterministic calculation over supplied inputs; source forecast authenticity belongs to the weather.forecast step")
            }
        })
    }
    register("weather.commute_plan","Resolve tomorrow’s commute date and wake time",objectSchema {
        obj("config","Validated weather.commute_check inputs",true)
        integer("evaluatedAtMs","Stable evaluation time, anchored to skill start",true,min=1)
        obj("nextAlarm","Result of alarms.next",true)
    }) {a ->
        val alarm=a.getValue("nextAlarm").jsonObject
        val alarmMs=if(alarm["visible"]?.jsonPrimitive?.booleanOrNull==true) alarm["triggerAtMs"]?.jsonPrimitive?.longOrNull else null
        val plan=WeatherCommute.plan(a.getValue("config").jsonObject,a.long("evaluatedAtMs",0),alarmMs,g.settings.weather.latitude,g.settings.weather.longitude)
        AppJson.encodeToJsonElement(WeatherCommutePlan.serializer(),plan).jsonObject
    }
    register("weather.commute_decide","Evaluate a complete fresh commute forecast",objectSchema {
        obj("plan","Resolved commute plan",true);obj("forecast","Explicit-window weather.forecast result",true)
    }) {a ->WeatherCommute.decide(AppJson.decodeFromJsonElement(WeatherCommutePlan.serializer(),a.getValue("plan")),a.getValue("forecast").jsonObject,System.currentTimeMillis())}
}
