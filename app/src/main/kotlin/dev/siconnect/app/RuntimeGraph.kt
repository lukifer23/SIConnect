package dev.siconnect.app

import android.app.*
import android.content.*
import dev.siconnect.app.approvals.AndroidApprovals
import dev.siconnect.app.automation.Scheduler
import dev.siconnect.app.backend.ShizukuBackend
import dev.siconnect.app.backend.PrivilegedBackend
import dev.siconnect.app.capabilities.registerCapabilities
import dev.siconnect.app.capabilities.registerAutomationCapabilities
import dev.siconnect.app.data.*
import dev.siconnect.app.transport.LocalEndpoint
import dev.siconnect.app.transport.GatewayConnection
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

class SIConnectApp : Application() {
    lateinit var graph: RuntimeGraph; private set
    override fun onCreate() { super.onCreate(); graph = RuntimeGraph(this) }
}
val Context.graph: RuntimeGraph get() = (applicationContext as SIConnectApp).graph

class RuntimeGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        android.util.Log.e("SIConnect", "Runtime task failed: ${error.javaClass.simpleName}")
    })
    val settings = Settings(context)
    val db = SIConnectDb(context)
    val requests = SqlRequestStore(db)
    val events = SqlEventLog(db)
    val agents = AgentStore(db)
    val registry = CapabilityRegistry()
    val shizuku = ShizukuBackend(context, scope)
    val privileged = PrivilegedBackend(settings, shizuku)
    val approvals = AndroidApprovals(context)
    val engine = ExecutionEngine(settings.deviceId, registry, PolicyEngine(), requests, events, approvals,
        agents, { settings.controls.value }, scope)
    val skillRuns = SqlSkillRunStore(db)
    val skills = SkillCatalog(db, registry)
    val runner = DurableSkillRunner(engine, skillRuns)
    val scheduler = Scheduler(this)
    val gateway = GatewayConnection(this)
    val health = MutableStateFlow("Starting")
    val ready: Deferred<Unit>
    private var local: LocalEndpoint? = null
    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel("approvals", "Owner approvals", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel("messages", "Reminders and messages", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("connection", "Remote connection", NotificationManager.IMPORTANCE_LOW)))
        registerCapabilities(this)
        registerAutomationCapabilities(this)
        ready = scope.async {
            check(db.readableDatabase.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }) { "SQLite integrity check failed; database preserved for recovery" }
            if (agents.get("local-owner") == null) agents.put(AgentRecord(AgentGrant("local-owner", "Device owner", scopes=listOf("*"), autoApproveUpTo=RiskClass.LOW_IMPACT), "", System.currentTimeMillis(), tokenHint="local only"))
            if (agents.get("local-automation") == null) agents.put(AgentRecord(AgentGrant("local-automation", "Local automations", scopes=listOf("device.*", "alarms.next", "weather.forecast", "notifications.post"), autoApproveUpTo=RiskClass.LOW_IMPACT), "", System.currentTimeMillis(), tokenHint="scheduler only"))
            privileged.refresh()
            engine.recover(); runner.recover()
            if (skills.get("diagnostics.snapshot") == null) skills.install(SkillDefinition("diagnostics.snapshot", 1, "Real native device snapshot", steps=listOf(
                SkillStep("status", "device.status"), SkillStep("battery", "device.battery"), SkillStep("memory", "device.memory")),
                output=buildJsonObject { put("device", JsonPrimitive("\${steps.status.output}")); put("battery", JsonPrimitive("\${steps.battery.output}")); put("memory", JsonPrimitive("\${steps.memory.output}")) }))
            events.trim(System.currentTimeMillis() - settings.eventRetentionDays * 86_400_000L)
            scheduler.restore()
            if (BuildConfig.DEBUG) { local = LocalEndpoint(this@RuntimeGraph).also { it.start() } }
            health.value = "Ready — SQLite schema ${SIConnectDb.VERSION}"
        }
        scope.launch {
            ready.await()
            engine.recordUpdates.collect { record ->
                if (record.state.isTerminal) skillRuns.active().filter { it.pendingRequestId == record.requestId }.forEach { runner.resume(it.runId) }
            }
        }
        scope.launch {
            ready.await()
            while (isActive) { delay(60_000); engine.expireStale(); skillRuns.active().filter { it.deadlineMs <= System.currentTimeMillis() }.forEach { runner.resume(it.runId) } }
        }
        ready.invokeOnCompletion { e -> if (e != null) health.value = "Startup failed: ${e.javaClass.simpleName}" }
    }
    /** Owner-only local UI action. Default development scope allows diagnostics and approval-gated shell. */
    fun createDevelopmentAgent(): String {
        val token = Settings.randomToken("sia_")
        val id = "development-client"
        agents.put(AgentRecord(AgentGrant(id, "ADB development client", scopes=listOf("device.*", "apps.list", "apps.inspect", "shell.*", "notifications.post", "alarms.next", "reminders.*", "weather.forecast"), autoApproveUpTo=RiskClass.LOW_IMPACT),
            Settings.sha256Hex(token), System.currentTimeMillis(), tokenHint=token.take(8)))
        if (BuildConfig.DEBUG) File(context.noBackupFilesDir, "development-client.json").apply {
            writeText(buildJsonObject { put("agentId", id); put("token", token); put("port", 8765) }.toString())
            setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true)
        }
        return token
    }
    fun authenticate(token: String): AgentRecord? {
        if (token.length !in 20..300) return null
        val hashed = Settings.sha256Hex(token).toByteArray()
        return agents.all().firstOrNull { it.tokenSha256.isNotEmpty() && MessageDigest.isEqual(it.tokenSha256.toByteArray(), hashed) && !it.grant.revoked && (it.grant.expiresAtMs?.let { e -> e > System.currentTimeMillis() } ?: true) }
    }
    suspend fun remoteInvoke(body: JsonObject, token: String): RequestRecord {
        ready.await()
        val agent = authenticate(token) ?: throw SecurityException("Invalid or revoked agent credential")
        require(body.keys.all { it in setOf("requestId", "traceId", "capabilityId", "arguments", "idempotencyKey", "expiresAtMs") }) { "Unsupported invocation field" }
        return engine.submit(InvocationRequest(
            requestId=(body["requestId"] as? JsonPrimitive)?.content ?: java.util.UUID.randomUUID().toString(),
            traceId=(body["traceId"] as? JsonPrimitive)?.content ?: java.util.UUID.randomUUID().toString(),
            agentId=agent.grant.agentId, capabilityId=body.getValue("capabilityId").jsonPrimitive.content,
            arguments=(body["arguments"] as? JsonObject) ?: JsonObject(emptyMap()),
            idempotencyKey=(body["idempotencyKey"] as? JsonPrimitive)?.content,
            expiresAtMs=(body["expiresAtMs"] as? JsonPrimitive)?.longOrNull), Origin.REMOTE)
    }
}
