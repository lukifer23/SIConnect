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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

class SIConnectApp : Application() {
    lateinit var graph: RuntimeGraph; private set
    override fun onCreate() { super.onCreate(); graph = RuntimeGraph(this) }
}
val Context.graph: RuntimeGraph get() = (applicationContext as SIConnectApp).graph

data class FullAccessPendingContinuation(
    val records:List<RequestRecord>,
    val examined:Int,
    /** More waiting records exist beyond this bounded owner-reviewed batch. */
    val morePending:Boolean,
)

class RuntimeGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        android.util.Log.e("SIConnect", "Runtime task failed: ${error.javaClass.simpleName}")
    })
    val settings = Settings(context)
    val db = SIConnectDb(context)
    val requests = SqlRequestStore(db)
    val events = SqlEventLog(db)
    val retention = StorageRetention(db, { settings.executionRetention }, { settings.executionRetention = it }) {cutoff,limit->
        dev.siconnect.app.termux.TermuxBridge.get(context).compactPayloads(cutoff,limit) {id->
            requests.get(id)?.state?.let {it.isTerminal && it!=RequestState.UNKNOWN_OUTCOME}==true
        }
    }
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
    val startup=RuntimeStartup(listOf(
        StartupStep("database","Check saved database",true) {
            check(db.readableDatabase.rawQuery("PRAGMA quick_check",null).use {it.moveToFirst() && it.getString(0)=="ok"}) {"SQLite integrity check failed; database preserved for recovery"}
        },
        StartupStep("local_principals","Restore local owner identities",true) {
            if(agents.get("local-owner")==null) agents.put(AgentRecord(AgentGrant("local-owner","Device owner",scopes=listOf("*"),autoApproveUpTo=RiskClass.LOW_IMPACT),"",System.currentTimeMillis(),tokenHint="local only"))
            if(agents.get("local-automation")==null) agents.put(AgentRecord(AgentGrant("local-automation","Local automations",scopes=listOf("device.*","alarms.next","weather.forecast","notifications.post"),autoApproveUpTo=RiskClass.LOW_IMPACT),"",System.currentTimeMillis(),tokenHint="scheduler only"))
        },
        StartupStep("screen_ownership","Recover screen ownership",true) {ui.recover()},
        StartupStep("backend","Observe selected device control",false) {privileged.refresh()},
        StartupStep("executions","Reconcile interrupted executions",true) {engine.recover();runner.prepareRecovery()},
        StartupStep("workflow_defaults","Check built-in workflows",false) {
            if(!skills.exists(dev.siconnect.app.automation.AutomationTemplates.HEALTH_SKILL)) skills.install(dev.siconnect.app.automation.AutomationTemplates.health())
            if(!skills.exists("weather.commute_check")) skills.install(dev.siconnect.app.capabilities.WeatherCommute.skill())
        },
        StartupStep("audit_history","Maintain diagnostic history",false) {events.trim(System.currentTimeMillis()-settings.eventRetentionDays*86_400_000L)},
        StartupStep("saved_automations","Restore saved automation dispatch",false) {scheduler.restore()},
        StartupStep("saved_reminders","Restore saved reminder dispatch",false) {reminders.restore()},
    ))
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
        dev.siconnect.app.capabilities.registerHeadlessCapabilities(this)
        dev.siconnect.app.capabilities.registerBridgeMaintenance(this)
        dev.siconnect.app.capabilities.registerProposalCapabilities(this)
        dev.siconnect.app.capabilities.registerRecoveryCapabilities(this)
        dev.siconnect.app.capabilities.registerClockCapabilities(this)
        dev.siconnect.app.capabilities.registerMessagesCapabilities(this)
        dev.siconnect.app.capabilities.registerCodexCapabilities(this)
        ready = scope.async {
            startup.start()
            if (BuildConfig.DEBUG) { local = LocalEndpoint(this@RuntimeGraph).also { it.start() } }
            updateStartupHealth()
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
        // Separate from readiness and recovery. Opt-in maintenance cannot delay startup or
        // remove an expiration/continuation collector if a storage operation fails.
        scope.launch {
            ready.await()
            while (isActive) {
                delay(15 * 60_000L)
                recovery.run("Execution retention") {
                    val batch = retention.maintain()
                    if (batch.total > 0) events.emit(Event(System.currentTimeMillis(), eventType="storage.payloads.compacted",
                        detail="${batch.requests} requests, ${batch.workflows} workflows, ${batch.automationRuns} automation runs"))
                }
            }
        }
        scope.launch {ready.await();privileged.directStatus.collect {gateway.refreshCatalog()}}
        scope.launch {
            ready.await()
            val bridge=dev.siconnect.app.termux.TermuxBridge.get(context)
            bridge.updates.map {bridge.availability()}.distinctUntilChanged().collect {gateway.refreshCatalog()}
        }
        scope.launch {ready.await();shizuku.status.collect {gateway.refreshCatalog()}}
        ready.invokeOnCompletion { e -> if (e != null) health.value = "Startup failed: ${e.javaClass.simpleName}" }
    }
    internal fun updateStartupHealth() {
        val failed=startup.stages.value.count {it.state in setOf("FAILED","INTERRUPTED")}
        health.value="Ready — SQLite schema ${SIConnectDb.VERSION}"+if(failed>0) " · $failed startup tasks need attention" else ""
    }
    fun renameAgentFromOwner(agentId:String,label:String,expectedLabel:String) {
        require(!agentId.startsWith("local-")) {"Local runtime identities are not editable"}
        agents.rename(agentId,label,expectedLabel)
        events.emit(Event(System.currentTimeMillis(),eventType="agent.label.updated",agentId=agentId,deviceId=settings.deviceId,
            detail="Owner changed display name; identity, credential and permissions preserved"))
    }
    /** Owner-only creation/rotation. Existing owner-selected access and profile snapshots stay unchanged. */
    fun createDevelopmentAgent(): String {
        val token = Settings.randomToken("sia_")
        val id = DEVELOPMENT_AGENT_ID
        requests.atomic {agents.put(developmentAgentWithCredential(agents.get(id),Settings.sha256Hex(token),token.take(8),System.currentTimeMillis()))}
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
            requireCustomAccess(a)
            val grant=a.grant.withShellPermission(always)
            agents.put(a.copy(grant=grant))
            events.emit(Event(System.currentTimeMillis(),eventType=if(always) "grant.shell_broad.allowed" else "grant.shell_standing.cleared",agentId=agentId,deviceId=settings.deviceId,detail="Local owner changed persistent shell permission"))
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }
    /** Local owner UI only; callers authenticate the complete full-control decision before enabling. */
    fun setFullAccessFromOwner(agentId:String,enabled:Boolean) {
        require(!agentId.startsWith("local-")) {"Select a remote identity"}
        val w=db.writableDatabase;w.beginTransaction()
        try {
            val record=requireNotNull(agents.get(agentId)) {"Agent no longer exists"}
            require(record.grant.agentId==agentId) {"Agent identity no longer matches the selected record"}
            val previous=record.fullAccessPreviousGrant
            require(previous==null || previous.agentId==agentId) {"Saved custom access belongs to a different identity"}
            if(enabled) {
                require(!record.grant.revoked) {"Agent is revoked"}
                val full=record.grant.copy(scopes=listOf("*"),denied=emptyList(),
                    autoApproveUpTo=RiskClass.CRITICAL,shellMode=ShellMode.BROAD,allowSharedScreen=true,
                    expiresAtMs=null,fullControl=true)
                if(previous==null || record.grant!=full) {
                    agents.put(record.copy(grant=full,fullAccessPreviousGrant=previous ?: record.grant.copy(fullControl=false)))
                    events.emit(Event(System.currentTimeMillis(),eventType="grant.full_access.allowed",agentId=agentId,deviceId=settings.deviceId,
                        detail="Local owner enabled full control without SIConnect action approvals or grant expiry; protected surfaces, Android authorization and device controls still apply"))
                }
            } else if(previous!=null) {
                // Restoring permissions must never reverse a later revocation, expiry or identity edit.
                val restored=previous.copy(agentId=record.grant.agentId,label=record.grant.label,
                    revoked=record.grant.revoked,expiresAtMs=record.grant.expiresAtMs ?: previous.expiresAtMs,fullControl=false)
                agents.put(record.copy(grant=restored,fullAccessPreviousGrant=null))
                events.emit(Event(System.currentTimeMillis(),eventType="grant.full_access.restored",agentId=agentId,deviceId=settings.deviceId,
                    detail="Local owner disabled full control and restored the saved custom permissions"))
            }
            w.setTransactionSuccessful()
        } finally {w.endTransaction()}
    }

    /**
     * Explicit continuation from the authenticated full-control owner decision only.
     * Reuses original waiting records; reads, reconnects and profile persistence never call this.
     */
    suspend fun resumeEligiblePendingForFullAccessFromOwner(agentId:String):FullAccessPendingContinuation {
        ready.await()
        require(!agentId.startsWith("local-")) {"Select a remote identity"}
        fun fullAgent():AgentRecord? = agents.get(agentId)?.takeIf {
            it.grant.agentId==agentId && it.grant.fullControl && it.fullAccessPreviousGrant?.agentId==agentId && !it.grant.revoked &&
                (it.grant.expiresAtMs?.let {expiry->expiry>System.currentTimeMillis()} ?: true)
        }
        require(fullAgent()!=null) {"Full control must be active for this agent before continuing waiting actions"}
        val candidates=requests.historyPage(limit=101,agentId=agentId,states=setOf(RequestState.AWAITING_APPROVAL),newest=false)
        val batch=candidates.take(100)
        val continued=mutableListOf<RequestRecord>()
        var examined=0
        fun skipped(summary:RequestSummary,reason:String) {
            events.emit(Event(System.currentTimeMillis(),eventType="grant.full_access.pending_skipped",agentId=agentId,
                deviceId=settings.deviceId,requestId=summary.requestId,capabilityId=summary.capabilityId,errorCode=reason,
                detail="Owner full-control continuation left the original action for separate review"))
        }
        for(summary in batch) {
            currentCoroutineContext().ensureActive()
            examined++
            if(fullAgent()==null) {skipped(summary,"FULL_ACCESS_INACTIVE");break}
            val record=requests.get(summary.requestId)
            if(record==null || record.agentId!=agentId || record.deviceId!=settings.deviceId || record.state!=RequestState.AWAITING_APPROVAL) {
                skipped(summary,"REQUEST_NO_LONGER_WAITING");continue
            }
            val now=System.currentTimeMillis()
            if(record.payloadExpired || now>=record.expiresAtMs || now>=(record.approvalExpiresAtMs ?: record.expiresAtMs)) {
                skipped(summary,"REQUEST_EXPIRED");continue
            }
            val capability=registry.get(record.capabilityId)
            val descriptor=capability?.descriptor
            if(capability==null || descriptor==null || descriptor.id!=record.capabilityId || descriptor.version!=record.capabilityVersion ||
                record.selectedBackend==null || record.selectedBackend!=descriptor.backend ||
                record.argsHash!=Canonical.actionHash(record.capabilityId,record.capabilityVersion,record.arguments)) {
                skipped(summary,"ACTION_CHANGED");continue
            }
            val eligible=try {
                val origin=Origin.valueOf(record.origin)
                val assessment=capability.assess(record.arguments)
                val originalAssessment=record.assessment
                val grant=fullAgent()?.grant
                // Only the owner's explicit full-control profile waives SIConnect action
                // approvals, including classified critical work. All eligibility gates remain.
                originalAssessment!=null && grant?.fullControl==true &&
                    Schema.validate(descriptor.inputSchema,record.arguments).isEmpty() &&
                    PolicyEngine().evaluate(grant,descriptor,record.arguments,assessment,capability.availability(record.arguments),
                        settings.controls.value,origin,System.currentTimeMillis()).outcome==PolicyOutcome.ALLOW
            } catch(e:CancellationException) {throw e}
              catch(_:Exception) {false}
            if(!eligible) {skipped(summary,"SEPARATE_REVIEW_REQUIRED");continue}
            if(fullAgent()==null) {skipped(summary,"FULL_ACCESS_INACTIVE");break}
            // No SQLite transaction is held across execution. The engine owns atomic
            // state transitions, hash/deadline/backend rechecks and cancellation races.
            val result=engine.resolveApproval(record.requestId,true,"local-owner",record.argsHash)
            continued+=result
            events.emit(Event(System.currentTimeMillis(),eventType="grant.full_access.pending_continued",agentId=agentId,
                deviceId=settings.deviceId,requestId=result.requestId,capabilityId=result.capabilityId,executionStatus=result.state.name,
                detail="Local owner continued this existing waiting action under full control; no new request was submitted"))
        }
        val more=candidates.size>100 || examined<batch.size
        events.emit(Event(System.currentTimeMillis(),eventType="grant.full_access.pending_reviewed",agentId=agentId,deviceId=settings.deviceId,
            detail="Examined $examined waiting actions; ${continued.size} continuation results; additional waiting actions: $more"))
        return FullAccessPendingContinuation(continued,examined,more)
    }

    private fun requireCustomAccess(record:AgentRecord) {
        require(record.fullAccessPreviousGrant==null && !record.grant.fullControl) {"Switch to custom access before changing individual permissions"}
    }

    /** Explicit owner action, only low-impact native weather calculations/reminders; no shell authority. */
    fun enableWeatherAutomationFromOwner() =
        allowLocalAutomationFromOwner(listOf("weather.commute_plan","weather.commute_decide","reminders.create"),
            "automation.weather.authorized","Local owner enabled native commute checks and reminder scheduling")
    /**
     * Owner-authenticated grant of exact typed capabilities to local schedules. Shell is never granted
     * here (it has its own explicit control), and each run still passes the policy engine and approvals.
     */
    fun allowLocalAutomationFromOwner(capabilityIds:List<String>,eventType:String="automation.scope.authorized",
        detail:String="Local owner allowed typed capabilities for local automations") {
        val ids=capabilityIds.distinct()
        require(ids.isNotEmpty() && ids.size<=32) {"Choose between 1 and 32 capabilities"}
        ids.forEach {id->
            val d=requireNotNull(registry.get(id)?.descriptor) {"Unknown capability $id"}
            require(d.id==id && !d.ownerOnly && !id.startsWith("shell.")) {"$id cannot be granted to local automations"}
        }
        val w=db.writableDatabase;w.beginTransaction()
        try {
            val a=requireNotNull(agents.get("local-automation"))
            agents.put(a.copy(grant=a.grant.copy(scopes=(a.grant.scopes+ids).distinct())))
            events.emit(Event(System.currentTimeMillis(),eventType=eventType,agentId="local-automation",detail="$detail: ${ids.joinToString()}"))
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
            requireCustomAccess(agent)
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
            requireCustomAccess(a)
            if(enabled) require(ids.all {id->a.grant.scopes.any {PolicyEngine.globMatches(it,id)} && a.grant.denied.none {PolicyEngine.globMatches(it,id)}}) {"Allow the UI interaction family first"}
            val retained=a.grant.standing.filterNot {it.capabilityId in ids && it.backend==BackendKind.ACCESSIBILITY && it.actionHash==null && it.constraints==constraints}
            val added=if(enabled) ids.map {id->StandingGrant(id,constraints,maxRisk=RiskClass.HIGH_IMPACT,backend=BackendKind.ACCESSIBILITY,note="Owner-selected semantic app access")} else emptyList()
            agents.put(a.copy(grant=a.grant.copy(standing=retained+added)))
            events.emit(Event(System.currentTimeMillis(),eventType="grant.semantic_app.changed",agentId=agentId,detail="Local owner ${if(enabled) "allowed" else "removed"} semantic clicks/text for $targetPackage"))
            w.setTransactionSuccessful()
        } finally {w.endTransaction()}
    }

    /** One authenticated local-owner decision enables the family and this app's private allowance. */
    fun enablePrivateAppAccessFromOwner(agentId:String,targetPackage:String,selectedBackend:BackendKind) {
        // Reject protected/invalid targets before opening a transaction or changing any grant.
        dev.siconnect.app.capabilities.HeadlessRules.validatePackage(targetPackage)
        require(!agentId.startsWith("local-")) {"Select a remote identity"}
        require(selectedBackend in setOf(BackendKind.ADB_SERVICE,BackendKind.SHIZUKU) && privileged.kind==selectedBackend) {
            "Selected backend changed during owner review; review the private allowance again"
        }
        val w=db.writableDatabase;w.beginTransaction()
        try {
            val record=requireNotNull(agents.get(agentId)) {"Agent no longer exists"}
            require(!record.grant.revoked && (record.grant.expiresAtMs?.let {it>System.currentTimeMillis()} ?: true)) {"Agent is revoked or expired"}
            if(record.grant.fullControl && record.fullAccessPreviousGrant!=null) {
                // The full profile already covers this app. Keep its original custom snapshot intact.
                w.setTransactionSuccessful()
                return
            }
            // Both existing setters use this thread's SQLite connection. Their nested
            // transactions and audit writes commit only with this outer transaction.
            setCapabilityFamilyFromOwner(agentId,"headless",true)
            setHeadlessPackagePermissionFromOwner(agentId,targetPackage,true,selectedBackend)
            require(privileged.kind==selectedBackend) {
                "Selected backend changed during owner review; review the private allowance again"
            }
            w.setTransactionSuccessful()
        } finally {w.endTransaction()}
    }

    /** Persist revocation and its audit event together; callers are local management screens only. */
    fun setHeadlessPackagePermissionFromOwner(agentId:String,targetPackage:String,enabled:Boolean,selected:BackendKind=privileged.kind) {
        require(!agentId.startsWith("local-")) {"Select a remote identity"}
        dev.siconnect.app.capabilities.HeadlessRules.validatePackage(targetPackage)
        val ids=setOf("headless.open","headless.click","headless.set_text","headless.scroll","headless.close")
        val constraints=mapOf("packageName" to JsonPrimitive(targetPackage))
        if(enabled) require(privileged.kind==selected) {"Selected backend changed during owner review; review the private allowance again"}
        val w=db.writableDatabase;w.beginTransaction()
        try {
            val record=requireNotNull(agents.get(agentId)) {"Agent no longer exists"}
            require(!record.grant.revoked && (record.grant.expiresAtMs?.let {it>System.currentTimeMillis()} ?: true)) {"Agent is revoked or expired"}
            requireCustomAccess(record)
            if(enabled) require(ids.all {id->record.grant.scopes.any {PolicyEngine.globMatches(it,id)} && record.grant.denied.none {PolicyEngine.globMatches(it,id)}}) {"Allow the private workspace capability family first"}
            val retained=record.grant.standing.filterNot {it.capabilityId in ids && it.backend in setOf(BackendKind.ADB_SERVICE,BackendKind.SHIZUKU) && it.actionHash==null && it.constraints==constraints}
            val added=if(enabled) ids.map {StandingGrant(it,constraints,maxRisk=RiskClass.HIGH_IMPACT,backend=selected,note="Owner-selected private app workspace access")} else emptyList()
            agents.put(record.copy(grant=record.grant.copy(standing=retained+added)))
            events.emit(Event(System.currentTimeMillis(),eventType="grant.private_app.changed",agentId=agentId,detail="Local owner ${if(enabled) "allowed" else "removed"} private app actions for $targetPackage"))
            w.setTransactionSuccessful()
        } finally {w.endTransaction()}
    }

    fun setSharedScreenPermissionFromOwner(agentId:String,allowed:Boolean) {
        require(!agentId.startsWith("local-")) {"Select a remote identity"}
        val w=db.writableDatabase;w.beginTransaction()
        try {
            val record=requireNotNull(agents.get(agentId)) {"Agent no longer exists"}
            requireCustomAccess(record)
            agents.put(record.copy(grant=record.grant.copy(allowSharedScreen=allowed)))
            events.emit(Event(System.currentTimeMillis(),eventType="grant.shared_screen.changed",agentId=agentId,
                detail=if(allowed) "Local owner allowed shared-screen operations" else "Local owner restricted agent to background APIs and private displays"))
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
        gateway.refreshCatalog() // re-sends hello with the updated revocation list
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
