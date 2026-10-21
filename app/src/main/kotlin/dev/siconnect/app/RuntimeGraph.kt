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
    val reminders = dev.siconnect.app.automation.ReminderScheduler(this)
    val gateway = GatewayConnection(this)
    val pairing = dev.siconnect.app.transport.BotPairing(this)
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
            reminders.restore()
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
        agents.put(AgentRecord(AgentGrant(id, "ADB development client", scopes=listOf("device.*", "requests.*", "apps.list", "apps.inspect", "shell.*", "notifications.post", "alarms.next", "reminders.*", "weather.forecast"), autoApproveUpTo=RiskClass.LOW_IMPACT),
            Settings.sha256Hex(token), System.currentTimeMillis(), tokenHint=token.take(8)))
        settings.setBotToken(token)
        if (BuildConfig.DEBUG) File(context.noBackupFilesDir, "development-client.json").apply {
            writeText(buildJsonObject { put("agentId", id); put("token", token); put("port", 8765) }.toString())
            setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true)
        }
        return token
    }
    /** Local owner UI only. Preserve an existing debug pairing while migrating its key to Keystore encryption. */
    fun botKeyForOwner(): String? {
        settings.botToken()?.let { if (authenticate(it) != null) return it }
        if (BuildConfig.DEBUG) {
            val previous=runCatching {
                Json.parseToJsonElement(File(context.noBackupFilesDir,"development-client.json").readText())
                    .jsonObject.getValue("token").jsonPrimitive.content
            }.getOrNull()
            if (previous != null && authenticate(previous) != null) {
                settings.setBotToken(previous)
                return previous
            }
        }
        return null
    }
    /** Called only by the local owner review screen. Remote entry points never accept grant changes. */
    suspend fun approveFromOwner(requestId: String, hash: String, always: Boolean = false, broadShell: Boolean = false): RequestRecord {
        ready.await()
        if(always || broadShell) {
            val r=requireNotNull(requests.get(requestId)) { "Request no longer exists" }
            require(r.state==RequestState.AWAITING_APPROVAL && r.argsHash==hash && hash==Canonical.actionHash(r.capabilityId,r.capabilityVersion,r.arguments)) { "Approval changed or is no longer pending" }
            val now=System.currentTimeMillis()
            require(now < r.expiresAtMs && now < (r.approvalExpiresAtMs ?: r.expiresAtMs)) { "Approval expired. Ask your bot to submit a new request." }
            val cap=requireNotNull(registry.get(r.capabilityId))
            require(cap.descriptor.version==r.capabilityVersion && (r.selectedBackend==null || r.selectedBackend==cap.descriptor.backend)) { "Capability changed. Submit a new request." }
            val assessment=requireNotNull(r.assessment)
            val risk=maxOf(cap.descriptor.risk,assessment.risk)
            require(risk < RiskClass.CRITICAL) { "Critical actions require approval every time" }
            val ownerDb=db.writableDatabase
            ownerDb.beginTransaction()
            try {
                val agent=requireNotNull(agents.get(r.agentId))
                val decision=PolicyEngine().evaluate(agent.grant,cap.descriptor,r.arguments,assessment,cap.availability(r.arguments),settings.controls.value,Origin.valueOf(r.origin),now)
                require(decision.outcome==PolicyOutcome.ALLOW || decision.outcome==PolicyOutcome.REQUIRE_APPROVAL) { decision.reason }
                val grant=if(broadShell) {
                    require(assessment.shellCommand!=null) { "This is not a shell request" }
                    agent.grant.copy(shellMode=ShellMode.BROAD,scopes=(agent.grant.scopes+"requests.*").distinct())
                } else {
                    val exact=StandingGrant(r.capabilityId,maxRisk=risk,actionHash=hash,backend=cap.descriptor.backend,note=assessment.summary)
                    agent.grant.copy(standing=agent.grant.standing.filterNot { it.actionHash==hash && it.backend==exact.backend }+exact)
                }
                agents.put(agent.copy(grant=grant))
                events.emit(Event(now,eventType=if(broadShell) "grant.shell_broad.allowed" else "grant.standing.allowed",requestId=r.requestId,traceId=r.traceId,agentId=r.agentId,deviceId=settings.deviceId,capabilityId=r.capabilityId,detail="Local owner saved durable authorization"))
                ownerDb.setTransactionSuccessful()
            } finally { ownerDb.endTransaction() }
        }
        return engine.resolveApproval(requestId,true,"local-owner",hash)
    }
    /** Persistent owner-selected shell policy. No remote endpoint calls this method. */
    fun setShellPermissionFromOwner(agentId: String, always: Boolean) {
        val w=db.writableDatabase
        w.beginTransaction()
        try {
            val a=requireNotNull(agents.get(agentId))
            require(!agentId.startsWith("local-") && !a.grant.revoked && (a.grant.expiresAtMs?.let { it>System.currentTimeMillis() } ?: true)) { "Agent is revoked or expired" }
            val grant=if(always) a.grant.copy(shellMode=ShellMode.BROAD,scopes=(a.grant.scopes+"requests.*").distinct())
                else a.grant.copy(shellMode=ShellMode.APPROVAL_REQUIRED,standing=emptyList(),shellStandingCommands=emptyList())
            agents.put(a.copy(grant=grant))
            events.emit(Event(System.currentTimeMillis(),eventType=if(always) "grant.shell_broad.allowed" else "grant.standing.cleared",agentId=agentId,deviceId=settings.deviceId,detail="Local owner changed persistent shell permission"))
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }
    /** Owner-selected capability family; every invocation still passes the shared policy engine. */
    fun setCapabilityFamilyFromOwner(agentId:String,family:String,enabled:Boolean) {
        require(family.matches(Regex("[a-z][a-z0-9_]*")) && registry.statuses().any { it.descriptor.id.startsWith("$family.") }) { "Unknown capability family" }
        require(!agentId.startsWith("local-"))
        val w=db.writableDatabase;w.beginTransaction()
        try {
            val agent=requireNotNull(agents.get(agentId))
            require(!agent.grant.revoked && (agent.grant.expiresAtMs?.let { it>System.currentTimeMillis() } ?: true)) { "Bot is revoked or expired" }
            val prefix="$family.";val pattern="$family.*"
            val grant=if(enabled) agent.grant.copy(scopes=(agent.grant.scopes+pattern).distinct(),denied=agent.grant.denied.filterNot { it==pattern || it.startsWith(prefix) })
                else agent.grant.copy(scopes=agent.grant.scopes.filterNot { it==pattern || it.startsWith(prefix) },denied=(agent.grant.denied+pattern).distinct())
            agents.put(agent.copy(grant=grant))
            events.emit(Event(System.currentTimeMillis(),eventType="grant.family.changed",agentId=agentId,deviceId=settings.deviceId,detail="Local owner ${if(enabled) "allowed" else "denied"} $pattern"))
            w.setTransactionSuccessful()
        } finally {w.endTransaction()}
    }
    /** Persist revocation and its audit event together; callers are local management screens only. */
    fun revokeAgentFromOwner(agentId: String) {
        require(!agentId.startsWith("local-")) { "Local identities cannot be revoked as remote bots" }
        val w=db.writableDatabase;w.beginTransaction()
        try {
            val agent=requireNotNull(agents.get(agentId)) { "Bot no longer exists" }
            agents.put(agent.copy(grant=agent.grant.copy(revoked=true)))
            events.emit(Event(System.currentTimeMillis(),eventType="agent.revoked",agentId=agentId,deviceId=settings.deviceId,detail="Local owner revoked bot"))
            w.setTransactionSuccessful()
        } finally {w.endTransaction()}
    }
    fun authenticate(token: String): AgentRecord? {
        if (token.length !in 20..300) return null
        val hashed = Settings.sha256Hex(token).toByteArray()
        return agents.all().firstOrNull { it.tokenSha256.isNotEmpty() && MessageDigest.isEqual(it.tokenSha256.toByteArray(), hashed) && !it.grant.revoked && (it.grant.expiresAtMs?.let { e -> e > System.currentTimeMillis() } ?: true) }
    }
    suspend fun remoteInvoke(body: JsonObject, token: String): RequestRecord {
        ready.await()
        val agent = authenticate(token) ?: throw SecurityException("Invalid or revoked agent credential")
        if(settings.pendingPairing()?.agentId==agent.grant.agentId) pairing.consumed(agent.grant.agentId)
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
