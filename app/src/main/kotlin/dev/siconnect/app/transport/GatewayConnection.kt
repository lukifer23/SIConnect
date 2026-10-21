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
    private val pairingAcks=java.util.concurrent.ConcurrentHashMap<String,CompletableDeferred<Unit>>()
    suspend fun publishPairing(invitation: PairingInvitation) {
        check(state.value==ConnectionState.ONLINE) { "Phone is offline" }
        val hash=dev.siconnect.app.data.Settings.sha256Hex(invitation.code)
        val ack=CompletableDeferred<Unit>()
        check(pairingAcks.putIfAbsent(hash,ack)==null) { "Pairing is already being prepared" }
        try {
            check(socket?.send(buildJsonObject { put("type","pairing");put("invitation",buildJsonObject {
                put("hash",hash);put("agentId",invitation.agentId);put("expiresAt",invitation.expiresAt);put("sealed",invitation.sealed)
            }) }.toString())==true) { "Connection lost" }
            withTimeout(8_000) { ack.await() }
        } finally { pairingAcks.remove(hash,ack) }
    }
    private val generation=java.util.concurrent.atomic.AtomicLong()
    /** Refresh discovery after backend/permission changes using the same authoritative registry. */
    fun refreshCatalog() {
        if(state.value!=ConnectionState.ONLINE) return
        g.scope.launch {socket?.send(hello())}
    }
    private fun hello()=buildJsonObject {
        put("type","hello");put("deviceId",g.settings.deviceId)
        put("capabilities",AppJson.encodeToJsonElement(ListSerializer(CapabilityStatus.serializer()),g.registry.statuses()))
    }.toString()
    private var loop: Job?=null
    @Volatile private var socket: WebSocket?=null
    @Synchronized fun stop() { generation.incrementAndGet(); loop?.cancel(); loop=null; socket?.close(1000,"Owner disabled connection"); socket=null; state.value=ConnectionState.DISABLED }
    @Synchronized fun start() {
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
                val epoch=generation.incrementAndGet()
                val request=Request.Builder().url(base.replaceFirst("https://","wss://").replaceFirst("http://","ws://")+"/device/${g.settings.deviceId}")
                    .header("Authorization","Bearer ${g.settings.deviceSecret()}").build()
                val connectedSocket=client.newWebSocket(request,object:WebSocketListener() {
                    override fun onOpen(ws: WebSocket,r: Response) {
                        if(generation.get()!=epoch) {ws.cancel();done.complete(Unit);return}
                        state.value=ConnectionState.AUTHENTICATING
                        ws.send(hello())
                        g.scope.launch {
                            delay(10_000)
                            if(socket===ws && state.value==ConnectionState.AUTHENTICATING) { detail.value="Gateway did not confirm connection";ws.close(1002,"Handshake timeout");done.complete(Unit) }
                        }
                    }
                    override fun onMessage(ws: WebSocket,text: String) {
                        if(generation.get()!=epoch) return
                        if(text.toByteArray(Charsets.UTF_8).size>131072) { ws.close(1009,"Envelope too large"); return }
                        g.scope.launch {
                            if(generation.get()!=epoch) return@launch
                            runCatching {
                                val msg=Json.parseToJsonElement(text).jsonObject
                                val type=msg["type"]?.jsonPrimitive?.content
                                if(type=="hello_ack") {
                                    require(msg.getValue("protocol").jsonPrimitive.int>=2) { "Gateway update required" }
                                    val reconnect=state.value!=ConnectionState.ONLINE
                                    state.value=ConnectionState.ONLINE;detail.value="Connected";attempt=0
                                    if(reconnect) g.events.emit(Event(System.currentTimeMillis(),eventType="gateway.connected",deviceId=g.settings.deviceId))
                                } else if(type=="pairing_saved") {
                                    pairingAcks[msg.getValue("hash").jsonPrimitive.content]?.complete(Unit)
                                } else if(type=="pairing_consumed") {
                                    g.pairing.consumed(msg.getValue("agentId").jsonPrimitive.content)
                                    msg["hash"]?.jsonPrimitive?.content?.let { pairingAcks[it]?.completeExceptionally(IllegalStateException("This code was already used. Create a new pairing code.")) }
                                } else if(type=="invoke") {
                                    val correlation=msg.getValue("id").jsonPrimitive.content
                                    val token=msg.getValue("agentToken").jsonPrimitive.content
                                    val result=g.remoteInvoke(msg.getValue("request").jsonObject,token)
                                    if(!ws.send(ResultEnvelope.result(correlation,result))) {
                                        runCatching {g.events.emit(Event(System.currentTimeMillis(),eventType="gateway.result_delivery_failed",requestId=result.requestId,traceId=result.traceId,agentId=result.agentId,deviceId=g.settings.deviceId,capabilityId=result.capabilityId,errorCode="RESULT_NOT_DELIVERED",executionStatus=result.state.name,detail="Stored request remains authoritative; retrieve the original request ID"))}
                                        ws.cancel();done.complete(Unit)
                                    }
                                } else if(type=="cancel") {
                                    val token=msg.getValue("agentToken").jsonPrimitive.content
                                    val agent=g.authenticate(token) ?: throw SecurityException()
                                    val id=msg.getValue("requestId").jsonPrimitive.content
                                    if(g.requests.get(id)?.agentId==agent.grant.agentId) g.engine.cancel(id,agent.grant.agentId)
                                }
                            }.onFailure { e ->
                                if(e is CancellationException) throw e
                                val code=when(e) {is CapabilityException->e.code;is SecurityException->"UNAUTHORIZED";is IllegalArgumentException->"INVALID_REQUEST";else->"UNKNOWN_OUTCOME"}
                                runCatching {g.events.emit(Event(System.currentTimeMillis(),eventType="gateway.protocol_error",deviceId=g.settings.deviceId,errorCode=code,detail="Incoming gateway message could not be processed"))}
                                val id=runCatching {Json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.content}.getOrNull()
                                ws.send(buildJsonObject {put("type","error");put("id",id);put("requestId",id);put("code",code)}.toString())
                            }
                        }
                    }
                    override fun onClosing(ws:WebSocket,code:Int,reason:String) {
                        ws.close(code,reason);done.complete(Unit)
                    }
                    override fun onClosed(ws: WebSocket,code: Int,reason: String) { done.complete(Unit) }
                    override fun onFailure(ws: WebSocket,t: Throwable,r: Response?) {
                        if(generation.get()==epoch) {
                            detail.value="Connection failed: ${r?.code ?: t.javaClass.simpleName}"
                            if(r?.code==401 || r?.code==403) state.value=ConnectionState.AUTH_FAILED
                        }
                        done.complete(Unit)
                    }
                })
                socket=connectedSocket
                try { done.await() } finally {
                    connectedSocket.cancel()
                    if(socket===connectedSocket) socket=null
                    pairingAcks.values.forEach {it.completeExceptionally(IllegalStateException("Gateway connection lost; retry the same invitation"))}
                }
                ensureActive()
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
