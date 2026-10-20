package dev.siconnect.app.transport

import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.data.AppJson
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.net.*
import java.util.concurrent.Semaphore

/** Debug-only authenticated loopback transport. It cannot create agents or resolve owner approvals. */
class LocalEndpoint(private val g: RuntimeGraph) {
    private val capacity = Semaphore(8)
    fun start() { g.scope.launch {
        val server = ServerSocket(8765, 8, InetAddress.getByName("127.0.0.1"))
        while (isActive) {
            val socket = server.accept()
            if (!capacity.tryAcquire()) { socket.close(); continue }
            g.scope.launch { try { socket.use { handle(it) } } finally { capacity.release() } }
        }
    } }
    private suspend fun handle(s: Socket) {
        s.soTimeout = 15000
        val input=s.getInputStream()
        fun line(): String {
            val b=StringBuilder()
            while (b.length < 8192) { val ch=input.read(); if (ch < 0 || ch == 10) return b.toString().trimEnd('\r'); b.append(ch.toChar()) }
            error("Header limit")
        }
        val first=line().split(' '); require(first.size == 3)
        val method=first[0]; val path=first[1]
        val headers=mutableMapOf<String,String>()
        repeat(32) { if (headers["_done"] == null) { val l=line(); if (l.isEmpty()) headers["_done"]="1" else { val i=l.indexOf(':'); require(i>0); headers[l.take(i).lowercase()]=l.drop(i+1).trim() } } }
        var code=200
        val response: String = try {
            require("transfer-encoding" !in headers && headers["_done"] != null)
            val token=headers["authorization"]?.removePrefix("Bearer ") ?: throw SecurityException()
            val agent=g.authenticate(token) ?: throw SecurityException()
            val n=headers["content-length"]?.toInt() ?: 0; require(n in 0..131072)
            val bytes=ByteArray(n); var offset=0
            while (offset<n) { val count=input.read(bytes,offset,n-offset); require(count>0); offset+=count }
            val body=if (n>0) Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject else JsonObject(emptyMap())
            when {
                method=="GET" && path=="/capabilities" -> AppJson.encodeToString(ListSerializer(CapabilityStatus.serializer()), g.registry.statuses())
                method=="POST" && path=="/invoke" -> AppJson.encodeToString(RequestRecord.serializer(), g.remoteInvoke(body, token))
                method=="GET" && path.startsWith("/requests/") -> {
                    val r=g.requests.get(path.removePrefix("/requests/")) ?: throw IllegalArgumentException("Unknown request")
                    if (r.agentId!=agent.grant.agentId) throw SecurityException()
                    AppJson.encodeToString(RequestRecord.serializer(),r)
                }
                method=="POST" && path.startsWith("/cancel/") -> {
                    val id=path.removePrefix("/cancel/"); if (g.requests.get(id)?.agentId!=agent.grant.agentId) throw SecurityException()
                    AppJson.encodeToString(RequestRecord.serializer(), requireNotNull(g.engine.cancel(id, agent.grant.agentId)))
                }
                method=="GET" && path=="/events" -> AppJson.encodeToString(ListSerializer(Event.serializer()), g.events.query(limit=200).filter { it.agentId==agent.grant.agentId })
                else -> { code=404; "{\"error\":\"NOT_FOUND\"}" }
            }
        } catch (e: SecurityException) { code=401; "{\"error\":\"UNAUTHORIZED\"}" }
          catch (e: CapabilityException) { code=409; buildJsonObject { put("error",e.code) }.toString() }
          catch (e: Exception) { code=400; "{\"error\":\"INVALID_REQUEST\"}" }
        val b=response.toByteArray()
        s.getOutputStream().apply { write("HTTP/1.1 $code Result\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(b); flush() }
    }
}
