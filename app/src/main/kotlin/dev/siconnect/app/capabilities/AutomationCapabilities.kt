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
            override val descriptor=CapabilityDescriptor(id,title=id,description=id,inputSchema=schema,backend=BackendKind.NATIVE,risk=risk,
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
    },RiskClass.LOW_IMPACT,::availability) { a,ctx ->
        val r=Reminder(ctx.requestId,a.long("fireAtMs",0),a.str("title"),a.str("text"),ctx.agentId,exact=exact(a))
        AppJson.encodeToJsonElement(Reminder.serializer(),g.reminders.put(r))
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
        override val descriptor=CapabilityDescriptor("weather.forecast",title="Open-Meteo forecast",description="Real forecast for configured coordinates; personal/non-commercial Open-Meteo endpoint",
            inputSchema=objectSchema { number("latitude","Latitude",min=-90.0,max=90.0); number("longitude","Longitude",min=-180.0,max=180.0) },backend=BackendKind.NETWORK,risk=RiskClass.READ_ONLY)
        override fun availability()=Availability.IMPLEMENTED
        override suspend fun execute(args: JsonObject,ctx: ExecutionContext): CapabilityResult = withContext(Dispatchers.IO) {
            val lat=(args["latitude"] as? JsonPrimitive)?.doubleOrNull ?: g.settings.weather.latitude ?: throw CapabilityException("LOCATION_REQUIRED","Configure weather coordinates")
            val lon=(args["longitude"] as? JsonPrimitive)?.doubleOrNull ?: g.settings.weather.longitude ?: throw CapabilityException("LOCATION_REQUIRED","Configure weather coordinates")
            val url="https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&hourly=temperature_2m,precipitation_probability,snowfall,weather_code&forecast_days=2&timezone=auto"
            val cache=java.io.File(g.context.cacheDir,"forecast-${Settings.sha256Hex("$lat,$lon")}.json")
            val now=System.currentTimeMillis()
            if(cache.exists() && now-cache.lastModified() in 0..900_000) return@withContext CapabilityResult(Json.parseToJsonElement(cache.readText()),BackendKind.NETWORK,"Open-Meteo (cached <15m)")
            val body=http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if(!r.isSuccessful) throw CapabilityException("WEATHER_HTTP","Provider status ${r.code}",true)
                val text=r.body.string(); require(text.length<256000); Json.parseToJsonElement(text).jsonObject
            }
            val hourly=body.getValue("hourly").jsonObject
            val normalized=buildJsonObject {
                put("fetchedAtMs",now); put("expiresAtMs",now+900_000); put("provider","Open-Meteo"); put("stale",false)
                put("maxRainProbability",hourly.getValue("precipitation_probability").jsonArray.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.maxOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                put("expectedSnowCm",hourly.getValue("snowfall").jsonArray.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.sum())
                put("minimumTemperatureC",hourly.getValue("temperature_2m").jsonArray.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.minOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                put("forecast",body)
            }
            cache.writeText(normalized.toString())
            CapabilityResult(normalized,BackendKind.NETWORK,"api.open-meteo.com")
        }
    })
}
