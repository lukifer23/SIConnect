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
    internal val healthAlerts = HealthAlertStore(db)
    val agents = AgentStore(db)
    val registry = CapabilityRegistry()
    val shizuku = ShizukuBackend(context, scope)
    val privileged = PrivilegedBackend(settings, shizuku)
    val bootstrap = dev.siconnect.app.bootstrap.LocalAdbBootstrap(context,settings,scope)
    val ui = dev.siconnect.app.coordination.UiLeaseCoordinator(dev.siconnect.app.coordination.SqlUiLeaseStore(db),events,settings.deviceId)
    val approvals = AndroidApprovals(context)
    val engine = ExecutionEngine(settings.deviceId, registry, PolicyEngine(), requests, events, approvals,
        agents, { settings.controls.value }, scope)
    val proposalStore = SqlProposalStore(db)
    val proposals = ProposalCoordinator(settings.deviceId,proposalStore,registry,agents,engine,events,controls={settings.controls.value})
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
    private val recovery = RuntimeRecovery { stage, error ->
        android.util.Log.e("SIConnect", "$stage deferred: ${error.javaClass.simpleName}")
    }
    private val workflowDispatch by lazy {RuntimeDispatch(scope,onFailure={_,e->android.util.Log.e("SIConnect","Workflow continuation deferred: ${e.javaClass.simpleName}")}) {runner.resume(it)}}
    private val scheduleDispatch by lazy {RuntimeDispatch(scope,workers=2,onFailure={_,e->android.util.Log.e("SIConnect","Automation reconciliation deferred: ${e.javaClass.simpleName}")}) {scheduler.reconcileRun(it)}}
    private val proposalDispatch by lazy {RuntimeDispatch(scope,workers=1,capacity=2,onFailure={_,e->android.util.Log.e("SIConnect","Proposal maintenance deferred: ${e.javaClass.simpleName}")}) {
        if(it=="recover") proposals.recover() else proposals.maintain()
    }}
    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel("approvals", "Owner approvals", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel("messages", "Reminders and messages", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("connection", "Remote connection", NotificationManager.IMPORTANCE_LOW)))
        registerCapabilities(this)
        registerAutomationCapabilities(this)
        dev.siconnect.app.capabilities.registerWeatherCommuteCapabilities(this)
        dev.siconnect.app.capabilities.registerProposalCapabilities(this)
        ready = scope.async {
            check(db.readableDatabase.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }) { "SQLite integrity check failed; database preserved for recovery" }
            if (agents.get("local-owner") == null) agents.put(AgentRecord(AgentGrant("local-owner", "Device owner", scopes=listOf("*"), autoApproveUpTo=RiskClass.LOW_IMPACT), "", System.currentTimeMillis(), tokenHint="local only"))
            if (agents.get("local-automation") == null) agents.put(AgentRecord(AgentGrant("local-automation", "Local automations", scopes=listOf("device.*", "alarms.next", "weather.forecast", "notifications.post"), autoApproveUpTo=RiskClass.LOW_IMPACT), "", System.currentTimeMillis(), tokenHint="scheduler only"))
            ui.recover()
            privileged.refresh()
            engine.recover(); runner.prepareRecovery()
            if (!skills.exists(dev.siconnect.app.automation.AutomationTemplates.HEALTH_SKILL)) skills.install(dev.siconnect.app.automation.AutomationTemplates.health())
            if(!skills.exists("weather.commute_check")) skills.install(dev.siconnect.app.capabilities.WeatherCommute.skill())
            events.trim(System.currentTimeMillis() - settings.eventRetentionDays * 86_400_000L)
            scheduler.restore()
            reminders.restore()
            if (BuildConfig.DEBUG) { local = LocalEndpoint(this@RuntimeGraph).also { it.start() } }
            health.value = "Ready — SQLite schema ${SIConnectDb.VERSION}"
            bootstrap.recoverIfEnabled()
        }
        scope.launch {
            ready.await()
            runner.runUpdates.collect {run -> scheduleDispatch.submit(run.runId)}
        }
        scope.launch {
            ready.await()
            proposalDispatch.submit("recover")
            recovery.run("Workflow recovery lookup") {skillRuns.active().forEach {workflowDispatch.submit(it.runId)}}
        }
        // A Binder/provider or scheduled job can recreate the app without an Activity or a
        // surviving package-replaced receiver. Reuse the owner's saved service preference once.
        scope.launch {ready.await();dev.siconnect.app.transport.ConnectionService.restoreOwnerEnabled(this@RuntimeGraph)}
        scope.launch {
            ready.await()
            engine.recordUpdates.collect { record ->
                recovery.run("Proposal reconciliation") {proposals.onRequest(record)}
                if (record.state.isTerminal) {
                    recovery.run("Reminder reconciliation") {reminders.onRequestUpdated(record)}
                    recovery.run("Workflow lookup") {
                        skillRuns.active().filter {it.pendingRequestId==record.requestId}.forEach {run ->
                            workflowDispatch.submit(run.runId)
                        }
                    }
                }
            }
        }
        scope.launch {
            ready.await()
            while (isActive) {
                delay(60_000)
                proposalDispatch.submit("maintain")
                recovery.run("Automation maintenance") {scheduler.reconciliationCandidates().forEach {scheduleDispatch.submit(it)}}
                recovery.run("Approval expiration") {engine.expireStale()}
                recovery.run("Workflow maintenance") {
                    skillRuns.active().filter {it.state==SkillState.READY || it.deadlineMs<=System.currentTimeMillis() ||
                        it.pendingRequestId?.let {id->requests.get(id)?.state?.isTerminal}==true}.forEach {run ->
                        workflowDispatch.submit(run.runId)
                    }
                }
            }
        }
        scope.launch {ready.await();privileged.directStatus.collect {gateway.refreshCatalog()}}
        scope.launch {ready.await();shizuku.status.collect {gateway.refreshCatalog()}}
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
            val grant=a.grant.withShellPermission(always)
            agents.put(a.copy(grant=grant))
            events.emit(Event(System.currentTimeMillis(),eventType=if(always) "grant.shell_broad.allowed" else "grant.shell_standing.cleared",agentId=agentId,deviceId=settings.deviceId,detail="Local owner changed persistent shell permission"))
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }
    /** Explicit owner action, only low-impact native weather calculations/reminders; no shell authority. */
    fun enableWeatherAutomationFromOwner() {
        val w=db.writableDatabase;w.beginTransaction()
        try {
            val a=requireNotNull(agents.get("local-automation"))
            val needed=listOf("weather.commute_plan","weather.commute_decide","reminders.create")
            agents.put(a.copy(grant=a.grant.copy(scopes=(a.grant.scopes+needed).distinct())))
            events.emit(Event(System.currentTimeMillis(),eventType="automation.weather.authorized",agentId="local-automation",detail="Local owner enabled native commute checks and reminder scheduling"))
            w.setTransactionSuccessful()
        } finally {w.endTransaction()}
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
    /** Local owner only. A package allowance is separate from family scope and broad shell. */
    fun setSemanticPackagePermissionFromOwner(agentId:String,targetPackage:String,enabled:Boolean) {
        require(!agentId.startsWith("local-")) {"Select a remote identity"}
        require(targetPackage.length<=200 && targetPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {"Enter an Android application package"}
        require(targetPackage!=context.packageName && !dev.siconnect.app.accessibility.SemanticUiRules.protectedPackage(targetPackage)) {"Owner, system permission and biometric surfaces are protected"}
        val ids=setOf("ui.click","ui.set_text")
        val constraints=mapOf("packageName" to JsonPrimitive(targetPackage))
        val w=db.writableDatabase;w.beginTransaction()
        try {
            val a=requireNotNull(agents.get(agentId)) {"Agent no longer exists"}
            require(!a.grant.revoked && (a.grant.expiresAtMs?.let {it>System.currentTimeMillis()} ?: true)) {"Agent is revoked or expired"}
            if(enabled) require(ids.all {id->a.grant.scopes.any {PolicyEngine.globMatches(it,id)} && a.grant.denied.none {PolicyEngine.globMatches(it,id)}}) {"Allow the UI interaction family first"}
            val retained=a.grant.standing.filterNot {it.capabilityId in ids && it.backend==BackendKind.ACCESSIBILITY && it.actionHash==null && it.constraints==constraints}
            val added=if(enabled) ids.map {id->StandingGrant(id,constraints,maxRisk=RiskClass.HIGH_IMPACT,backend=BackendKind.ACCESSIBILITY,note="Owner-selected semantic app access")} else emptyList()
            agents.put(a.copy(grant=a.grant.copy(standing=retained+added)))
            events.emit(Event(System.currentTimeMillis(),eventType="grant.semantic_app.changed",agentId=agentId,detail="Local owner ${if(enabled) "allowed" else "removed"} semantic clicks/text for $targetPackage"))
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
        val hash=Settings.sha256Hex(token)
        val agent=agents.byCredentialHash(hash) ?: return null
        return agent.takeIf { MessageDigest.isEqual(it.tokenSha256.toByteArray(),hash.toByteArray()) && !it.grant.revoked && (it.grant.expiresAtMs?.let {e->e>System.currentTimeMillis()} ?: true) }
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
