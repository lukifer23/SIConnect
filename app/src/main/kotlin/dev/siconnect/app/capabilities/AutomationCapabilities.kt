package dev.siconnect.app.capabilities

import android.content.ContentValues
import android.content.Context
import androidx.work.*
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.graph
import dev.siconnect.app.data.*
import dev.siconnect.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class Reminder(val id: String, val fireAtMs: Long, val title: String, val text: String, val agentId: String, val state: String="SCHEDULED", val exact: Boolean=false, val error: String?=null, val revision: Long=0)

fun registerAutomationCapabilities(g: RuntimeGraph) {
    fun register(id: String, schema: JsonObject, risk: RiskClass, available: (JsonObject)->Availability={Availability.IMPLEMENTED}, body: suspend (JsonObject,ExecutionContext)->JsonElement) {
        g.registry.register(object : Capability {
            override val descriptor=CapabilityDescriptor(id,title=id,description=id,inputSchema=schema,backend=BackendKind.NATIVE,risk=risk,interaction=InteractionMode.BACKGROUND,
                sideEffects=if(risk>RiskClass.READ_ONLY) listOf("Changes SIConnect reminders") else emptyList())
            override fun availability()=Availability.IMPLEMENTED
            override fun availability(arguments: JsonObject)=available(arguments)
            override fun availabilityDetail()="Precise reminders require Android exact alarm access. Deferrable reminders do not."
            override suspend fun execute(args: JsonObject,ctx: ExecutionContext)=CapabilityResult(body(args,ctx),BackendKind.NATIVE,"uid=${android.os.Process.myUid()}")
        })
    }
    fun exact(a: JsonObject)=a["exact"]?.jsonPrimitive?.booleanOrNull ?: false
    fun availability(a: JsonObject)=if(exact(a) && !g.reminders.canScheduleExact()) Availability.PERMISSION_REQUIRED else Availability.IMPLEMENTED
    fun owned(id: String,ctx: ExecutionContext): Reminder {
        val r=g.reminders.get(id) ?: throw CapabilityException("NOT_FOUND","No such reminder")
        if(r.agentId!=ctx.agentId && ctx.agentId!="local-owner") throw CapabilityException("POLICY_DENIED","Reminder belongs to another agent")
        return r
    }
    register("reminders.create",objectSchema {
        string("title","Title",true,maxLength=150);string("text","Text",true,maxLength=1000)
        integer("fireAtMs","Delivery time in epoch milliseconds",true,min=1);boolean("exact","Precise time using AlarmManager; requires special access")
        string("deduplicationKey","Optional stable per-agent reminder identity; changed arguments require explicit update",maxLength=200)
    },RiskClass.LOW_IMPACT,::availability) { a,ctx ->
        val key=a["deduplicationKey"]?.jsonPrimitive?.content
        val id=if(key==null) ctx.requestId else "dedup:"+Settings.sha256Hex(ctx.agentId+":"+key)
        val r=Reminder(id,a.long("fireAtMs",0),a.str("title"),a.str("text"),ctx.agentId,exact=exact(a))
        AppJson.encodeToJsonElement(Reminder.serializer(),if(key==null) g.reminders.put(r) else g.reminders.createDeduplicated(r))
    }
    register("reminders.update",objectSchema {
        string("id","Existing reminder ID",true,maxLength=200);integer("fireAtMs","Replacement delivery time",min=1)
        string("title","Replacement title",maxLength=150);string("text","Replacement content",maxLength=1000)
        boolean("exact","Precise time using AlarmManager; requires special access")
    },RiskClass.LOW_IMPACT,{ a ->
        val precise=a["exact"]?.jsonPrimitive?.booleanOrNull ?: g.reminders.get(a.str("id"))?.exact ?: false
        if(precise && !g.reminders.canScheduleExact()) Availability.PERMISSION_REQUIRED else Availability.IMPLEMENTED
    }) { a,ctx ->
        val r=owned(a.str("id"),ctx)
        val updated=r.copy(fireAtMs=a["fireAtMs"]?.jsonPrimitive?.longOrNull ?: r.fireAtMs,title=a["title"]?.jsonPrimitive?.content ?: r.title,
            text=a["text"]?.jsonPrimitive?.content ?: r.text,exact=a["exact"]?.jsonPrimitive?.booleanOrNull ?: r.exact)
        AppJson.encodeToJsonElement(Reminder.serializer(),g.reminders.put(updated))
    }
    register("reminders.list",objectSchema(),RiskClass.READ_ONLY) { _,ctx -> JsonArray(g.reminders.all().filter { it.agentId==ctx.agentId || ctx.agentId=="local-owner" }.map { AppJson.encodeToJsonElement(Reminder.serializer(),it) }) }
    register("reminders.cancel",objectSchema { string("id","Reminder ID",true,maxLength=200) },RiskClass.LOW_IMPACT) { a,ctx ->
        val r=owned(a.str("id"),ctx)
        AppJson.encodeToJsonElement(Reminder.serializer(),g.reminders.cancel(r.id))
    }
    val http=OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS).build()
    g.registry.register(object : Capability {
        override val descriptor=CapabilityDescriptor("weather.forecast",title="Open-Meteo forecast",description="Real forecast. For a commute/date window provide targetDate (YYYY-MM-DD), timezone (IANA), startHour and endHour. Hourly accumulations use their actual preceding-hour intervals. Missing data is not zero. Without a date window, legacy two-day summary remains available and is explicitly labeled FULL_RESPONSE.",
            inputSchema=objectSchema {
                number("latitude","Latitude",min=-90.0,max=90.0);number("longitude","Longitude",min=-180.0,max=180.0)
                string("targetDate","Target local date YYYY-MM-DD",maxLength=10);string("timezone","IANA timezone; default device timezone",maxLength=100)
                integer("startHour","Window start local hour; default 6",min=0,max=23);integer("endHour","Exclusive local end hour; default 12 (24 is next midnight)",min=1,max=24)
            },backend=BackendKind.NETWORK,risk=RiskClass.READ_ONLY,interaction=InteractionMode.BACKGROUND)
        override fun availability()=Availability.IMPLEMENTED
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult=withContext(Dispatchers.IO) {
            val lat=args["latitude"]?.jsonPrimitive?.doubleOrNull ?: g.settings.weather.latitude ?: throw CapabilityException("LOCATION_REQUIRED","Configure weather coordinates")
            val lon=args["longitude"]?.jsonPrimitive?.doubleOrNull ?: g.settings.weather.longitude ?: throw CapabilityException("LOCATION_REQUIRED","Configure weather coordinates")
            val zone=java.time.ZoneId.of(args["timezone"]?.jsonPrimitive?.content ?: java.time.ZoneId.systemDefault().id)
            val windowed=listOf("targetDate","startHour","endHour").any {it in args}
            val date=args["targetDate"]?.jsonPrimitive?.content ?: java.time.LocalDate.now(zone).toString()
            val bounds=if(windowed) ForecastWindow.bounds(date,zone.id,args.long("startHour",6).toInt(),args.long("endHour",12).toInt()) else null
            if(windowed) require(java.time.LocalDate.parse(date) in java.time.LocalDate.now(zone)..java.time.LocalDate.now(zone).plusDays(6)) {"Forecast date must be within the next seven local dates"}
            val days=if(windowed) 7 else 2
            val url="https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&hourly=temperature_2m,precipitation_probability,precipitation,snowfall,weather_code&forecast_days=$days&timezone=${java.net.URLEncoder.encode(zone.id,"UTF-8")}&timeformat=unixtime"
            val cache=java.io.File(g.context.cacheDir,"forecast-v2-${Settings.sha256Hex("$lat,$lon,${zone.id},$days")}.json")
            val now=System.currentTimeMillis()
            var fetchedAt=now
            val cached=if(cache.exists() && now-cache.lastModified() in 0..900_000 && cache.length()<=256000) runCatching {Json.parseToJsonElement(cache.readText()).jsonObject}.getOrNull() else null
            val body=if(cached!=null) {fetchedAt=cache.lastModified();cached} else {
                val fresh=http.newCall(Request.Builder().url(url).build()).execute().use {r ->
                    if(!r.isSuccessful) throw CapabilityException("WEATHER_HTTP","Provider status ${r.code}",true)
                    val bytes=java.io.ByteArrayOutputStream()
                    r.body.byteStream().use {input ->
                        val buffer=ByteArray(8192)
                        while(true) {val n=input.read(buffer);if(n<0) break
                            if(bytes.size()+n>256000) throw CapabilityException("WEATHER_TOO_LARGE","Forecast exceeded response limit")
                            bytes.write(buffer,0,n)
                        }
                    }
                    Json.parseToJsonElement(bytes.toString("UTF-8")).jsonObject
                }
                // AtomicFile prevents concurrent readers/process interruption seeing half a forecast.
                synchronized(http) {
                    val atomic=android.util.AtomicFile(cache);val stream=atomic.startWrite()
                    try {stream.write(fresh.toString().toByteArray());atomic.finishWrite(stream)} catch(e:Exception) {atomic.failWrite(stream);throw e}
                }
                fresh
            }
            val hourly=body.getValue("hourly").jsonObject
            val summary=if(bounds!=null) ForecastWindow.summarize(body,bounds.first,bounds.second) else buildJsonObject {
                put("maxRainProbability",hourly.getValue("precipitation_probability").jsonArray.mapNotNull {it.jsonPrimitive.doubleOrNull}.maxOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                val snow=hourly.getValue("snowfall").jsonArray
                put("expectedSnowCm",snow.mapNotNull {it.jsonPrimitive.doubleOrNull}.takeIf {it.size==snow.size}?.sum()?.let(::JsonPrimitive) ?: JsonNull)
                put("minimumTemperatureC",hourly.getValue("temperature_2m").jsonArray.mapNotNull {it.jsonPrimitive.doubleOrNull}.minOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            }
            val normalized=buildJsonObject {
                put("fetchedAtMs",fetchedAt);put("expiresAtMs",fetchedAt+900_000);put("provider","Open-Meteo");put("stale",now-fetchedAt>900_000)
                put("summaryScope",if(windowed) "EXPLICIT_WINDOW" else "FULL_RESPONSE")
                put("deviceTimezone",java.time.ZoneId.systemDefault().id);put("forecastTimezone",body["timezone"] ?: JsonPrimitive(zone.id))
                summary.forEach {(key,value)->put(key,value)}
                if(!windowed) put("forecast",body)
            }
            CapabilityResult(normalized,BackendKind.NETWORK,if(cached!=null) "Open-Meteo (cached <15m)" else "api.open-meteo.com")
        }
    })
}
