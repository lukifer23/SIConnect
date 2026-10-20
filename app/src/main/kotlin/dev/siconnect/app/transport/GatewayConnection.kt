package dev.siconnect.app.transport

import android.app.*
import android.content.*
import dev.siconnect.app.R
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.graph
import dev.siconnect.app.data.AppJson
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class ConnectionState { DISABLED, CONNECTING, AUTHENTICATING, ONLINE, BACKOFF, AUTH_FAILED }

/** Outbound authenticated device socket. Each invocation additionally carries its agent credential. */
class GatewayConnection(private val g: RuntimeGraph) {
    val state=MutableStateFlow(ConnectionState.DISABLED)
    val detail=MutableStateFlow("Remote connection disabled")
    private val client=OkHttpClient.Builder().pingInterval(90,TimeUnit.SECONDS).build()
    private var loop: Job?=null
    @Volatile private var socket: WebSocket?=null
    fun stop() { loop?.cancel(); loop=null; socket?.close(1000,"Owner disabled connection"); socket=null; state.value=ConnectionState.DISABLED }
    fun start() {
        if(loop?.isActive==true) return
        loop=g.scope.launch {
            g.ready.await(); var attempt=0
            while(isActive && g.settings.gateway.value.enabled) {
                try {
                val config=g.settings.gateway.value
                val base=config.baseUrl.trimEnd('/')
                if(!base.startsWith("https://") && !(dev.siconnect.app.BuildConfig.DEBUG && base.startsWith("http://127.0.0.1:"))) {
                    state.value=ConnectionState.AUTH_FAILED; detail.value="HTTPS gateway required"; break
                }
                state.value=ConnectionState.CONNECTING
                if(!g.settings.enrolled && !g.settings.enrollToken().isNullOrBlank()) {
                    state.value=ConnectionState.AUTHENTICATING
                    val registration=withContext(Dispatchers.IO) {
                        client.newCall(Request.Builder().url(base+"/register/${g.settings.deviceId}")
                            .header("Authorization","Bearer ${g.settings.enrollToken()}")
                            .post(buildJsonObject { put("deviceToken",g.settings.deviceSecret()) }.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r -> r.code }
                    }
                    if(registration==200) { g.settings.enrolled=true; g.settings.setEnrollToken(null) }
                    else if(registration!=409) { state.value=ConnectionState.AUTH_FAILED; detail.value="Gateway enrollment failed: $registration"; break }
                }
                val done=CompletableDeferred<Unit>()
                val request=Request.Builder().url(base.replaceFirst("https://","wss://").replaceFirst("http://","ws://")+"/device/${g.settings.deviceId}")
                    .header("Authorization","Bearer ${g.settings.deviceSecret()}").build()
                socket=client.newWebSocket(request,object:WebSocketListener() {
                    override fun onOpen(ws: WebSocket,r: Response) {
                        state.value=ConnectionState.AUTHENTICATING
                        ws.send(buildJsonObject { put("type","hello"); put("deviceId",g.settings.deviceId)
                            put("capabilities",AppJson.encodeToJsonElement(ListSerializer(CapabilityStatus.serializer()),g.registry.statuses())) }.toString())
                        state.value=ConnectionState.ONLINE; detail.value="Connected"; attempt=0
                        g.events.emit(Event(System.currentTimeMillis(),eventType="gateway.connected",deviceId=g.settings.deviceId))
                    }
                    override fun onMessage(ws: WebSocket,text: String) {
                        if(text.length>131072) { ws.close(1009,"Envelope too large"); return }
                        g.scope.launch {
                            runCatching {
                                val msg=Json.parseToJsonElement(text).jsonObject
                                val type=msg["type"]?.jsonPrimitive?.content
                                if(type=="invoke") {
                                    val correlation=msg.getValue("id").jsonPrimitive.content
                                    val token=msg.getValue("agentToken").jsonPrimitive.content
                                    val result=g.remoteInvoke(msg.getValue("request").jsonObject,token)
                                    ws.send(buildJsonObject { put("type","result"); put("id",correlation); put("record",AppJson.encodeToJsonElement(RequestRecord.serializer(),result)) }.toString())
                                } else if(type=="cancel") {
                                    val token=msg.getValue("agentToken").jsonPrimitive.content
                                    val agent=g.authenticate(token) ?: throw SecurityException()
                                    val id=msg.getValue("requestId").jsonPrimitive.content
                                    if(g.requests.get(id)?.agentId==agent.grant.agentId) g.engine.cancel(id,agent.grant.agentId)
                                }
                            }.onFailure { e ->
                                val id=runCatching { Json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
                                ws.send(buildJsonObject { put("type","error"); put("id",id); put("code",if(e is CapabilityException) e.code else if(e is SecurityException) "UNAUTHORIZED" else "INVALID_REQUEST") }.toString())
                            }
                        }
                    }
                    override fun onClosed(ws: WebSocket,code: Int,reason: String) { done.complete(Unit) }
                    override fun onFailure(ws: WebSocket,t: Throwable,r: Response?) {
                        detail.value="Connection failed: ${r?.code ?: t.javaClass.simpleName}"
                        if(r?.code==401 || r?.code==403) state.value=ConnectionState.AUTH_FAILED
                        done.complete(Unit)
                    }
                })
                try { done.await() } finally { socket?.cancel() }
                if(state.value==ConnectionState.AUTH_FAILED) break
                state.value=ConnectionState.BACKOFF
                val cap=minOf(300_000L,1000L shl attempt.coerceAtMost(8)); attempt++
                delay(Random.nextLong(cap/2,cap+1))
                } catch(e: CancellationException) { throw e }
                  catch(e: Exception) {
                    state.value=ConnectionState.BACKOFF; detail.value="Connection unavailable: ${e.javaClass.simpleName}"
                    val cap=minOf(300_000L,1000L shl attempt.coerceAtMost(8));attempt++
                    delay(Random.nextLong(cap/2,cap+1))
                }
            }
        }
    }
}
class ConnectionService: Service() {
    override fun onBind(i: Intent?)=null
    override fun onStartCommand(i: Intent?,flags: Int,startId: Int): Int {
        startForeground(880,Notification.Builder(this,"connection").setSmallIcon(R.drawable.ic_stat).setContentTitle("SIConnect remote bridge")
            .setContentText("Owner-enabled authenticated gateway connection").setOngoing(true).build())
        graph.gateway.start(); return START_STICKY
    }
    override fun onDestroy() { graph.gateway.stop(); super.onDestroy() }
}
