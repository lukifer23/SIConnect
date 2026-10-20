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
data class Reminder(val id: String, val fireAtMs: Long, val title: String, val text: String, val agentId: String, val state: String="SCHEDULED")

fun registerAutomationCapabilities(g: RuntimeGraph) {
    val table=JsonTable(g.db,"reminders","reminder_id")
    fun all()=table.list().map { AppJson.decodeFromString(Reminder.serializer(),it) }
    fun register(id: String, schema: JsonObject, risk: RiskClass, body: suspend (JsonObject,ExecutionContext)->JsonElement) {
        g.registry.register(object : Capability {
            override val descriptor=CapabilityDescriptor(id,title=id,description=id,inputSchema=schema,backend=BackendKind.NATIVE,risk=risk,
                sideEffects=if(risk>RiskClass.READ_ONLY) listOf("Changes SIConnect reminders") else emptyList())
            override fun availability()=Availability.IMPLEMENTED
            override suspend fun execute(args: JsonObject,ctx: ExecutionContext)=CapabilityResult(body(args,ctx),BackendKind.NATIVE,"uid=${android.os.Process.myUid()}")
        })
    }
    register("reminders.create",objectSchema { string("title","Title",true,maxLength=150); string("text","Text",true,maxLength=1000); integer("fireAtMs","Deferrable delivery time in epoch milliseconds",true,min=1) },RiskClass.LOW_IMPACT) { a,ctx ->
        val r=Reminder(ctx.requestId,a.long("fireAtMs",0),a.str("title"),a.str("text"),ctx.agentId)
        require(r.fireAtMs>System.currentTimeMillis()) { "Reminder must be in the future" }
        table.insertUnique(ContentValues().apply { put("reminder_id",r.id); put("fire_at",r.fireAtMs); put("state",r.state); put("created_by",r.agentId); put("json",AppJson.encodeToString(Reminder.serializer(),r)) })
        WorkManager.getInstance(g.context).enqueueUniqueWork("reminder:${r.id}",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReminderWorker>().setInputData(workDataOf("id" to r.id)).setInitialDelay(r.fireAtMs-System.currentTimeMillis(),TimeUnit.MILLISECONDS).build())
        AppJson.encodeToJsonElement(Reminder.serializer(),r)
    }
    register("reminders.list",objectSchema(),RiskClass.READ_ONLY) { _,ctx -> JsonArray(all().filter { it.agentId==ctx.agentId }.map { AppJson.encodeToJsonElement(Reminder.serializer(),it) }) }
    register("reminders.cancel",objectSchema { string("id","Reminder ID",true,maxLength=200) },RiskClass.LOW_IMPACT) { a,ctx ->
        val r=all().firstOrNull { it.id==a.str("id") && it.agentId==ctx.agentId } ?: throw CapabilityException("NOT_FOUND","No reminder owned by agent")
        WorkManager.getInstance(g.context).cancelUniqueWork("reminder:${r.id}")
        val changed=r.copy(state="CANCELLED")
        table.update(ContentValues().apply { put("state",changed.state); put("json",AppJson.encodeToString(Reminder.serializer(),changed)) },"reminder_id=?",arrayOf(r.id))
        buildJsonObject { put("cancelled",true) }
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
class ReminderWorker(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result {
        val g=applicationContext.graph; g.ready.await()
        val id=inputData.getString("id") ?: return Result.failure()
        val table=JsonTable(g.db,"reminders","reminder_id")
        val r=table.get(id)?.let { AppJson.decodeFromString(Reminder.serializer(),it) } ?: return Result.failure()
        if(r.state!="SCHEDULED") return Result.success()
        val result=g.engine.submit(InvocationRequest(requestId="reminder:$id",agentId="local-automation",capabilityId="notifications.post",
            arguments=buildJsonObject { put("title",r.title); put("text",r.text) }),Origin.LOCAL_AUTOMATION)
        val changed=r.copy(state=result.state.name)
        table.update(ContentValues().apply { put("state",changed.state); put("json",AppJson.encodeToString(Reminder.serializer(),changed)) },"reminder_id=?",arrayOf(id))
        return if(result.state==RequestState.SUCCEEDED) Result.success() else Result.failure()
    }
}
