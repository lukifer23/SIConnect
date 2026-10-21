package dev.siconnect.app.ui

import android.Manifest
import android.app.*
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.lifecycle.*
import dev.siconnect.app.ui.navigation.*
import dev.siconnect.app.ui.home.HomeDashboard
import dev.siconnect.app.ui.agents.AgentsScreen
import dev.siconnect.app.ui.automations.AutomationsScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.siconnect.app.*
import dev.siconnect.app.data.*
import dev.siconnect.app.automation.Automation
import dev.siconnect.app.transport.ConnectionService
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

open class MainActivity: androidx.fragment.app.FragmentActivity() {
    private lateinit var controlModel:ControlCenterState
    internal lateinit var ownerAuthentication:dev.siconnect.app.approvals.OwnerAuthentication
    internal var documentStatus by mutableStateOf("")
    internal val documentPicker=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {uri ->
        if(uri!=null) graph.scope.launch {
            try {graph.ready.await();val root=DocumentRoots(this@MainActivity,graph.db).add(uri)
                withContext(Dispatchers.Main) {documentStatus="Folder ${root.label} is connected";permissionRevision++}}
            catch(e:Exception) {withContext(Dispatchers.Main) {documentStatus="Folder access failed: ${e.javaClass.simpleName}";permissionRevision++}}
        }
    }
    internal var permissionRevision by mutableIntStateOf(0)
    internal val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRevision++;graph.gateway.refreshCatalog() }
    override fun onResume() { super.onResume(); permissionRevision++;graph.gateway.refreshCatalog();if(::controlModel.isInitialized) controlModel.refresh() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);permissionRevision++ }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ownerAuthentication=dev.siconnect.app.approvals.OwnerAuthentication(this)
        controlModel=ViewModelProvider(this,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=ControlCenterState(graph) as T
        })[ControlCenterState::class.java]
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
        setContent {
            val appearance by graph.settings.appearance.collectAsState()
            SIConnectTheme(appearance) {dark ->
                SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply {
                        isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
                    }
                }
                Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {Home()}
            }
        }
    }

    override fun onStart() {
        super.onStart()
        graph.gateway.refreshCatalog()
        if(graph.settings.gateway.value.enabled) startForegroundService(Intent(this,ConnectionService::class.java))
    }

    @Composable private fun Home() {
        val g=graph
        val scope=rememberCoroutineScope()
        val config by g.settings.gateway.collectAsState()
        val connection by g.gateway.state.collectAsState()
        val connectionDetail by g.gateway.detail.collectAsState()
        val direct by g.privileged.directStatus.collectAsState()
        val shizuku by g.shizuku.status.collectAsState()
        val controls by g.settings.controls.collectAsState()
        val health by g.health.collectAsState()
        var snapshot by remember {mutableStateOf(controlModel.state.value)}
        LaunchedEffect(lifecycle) {lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {controlModel.state.collect {snapshot=it}}}
        var destination by rememberSaveable {mutableStateOf(Destination.HOME)}
        var selectedAgentId by rememberSaveable {mutableStateOf<String?>(null)}
        LaunchedEffect(destination) {controlModel.refresh()}
        var advanced by remember { mutableStateOf(false) }
        var changed by remember { mutableIntStateOf(0) }
        val pending=snapshot.pending
        val recent=snapshot.recent

        var activity by rememberSaveable { mutableStateOf(false) }
        var inbox by rememberSaveable {mutableStateOf(false)}
        var note by remember { mutableStateOf("") }
        var measurements by remember {mutableStateOf<JsonObject?>(null)}
        var busy by remember { mutableStateOf(false) }
        var setup by remember { mutableStateOf(false) }
        var controlHelp by remember { mutableStateOf(false) }
        var connectionCode by remember { mutableStateOf("") }
        var pairName by remember { mutableStateOf("Dots") }
        var pairError by remember { mutableStateOf("") }
        var pairDialog by remember { mutableStateOf(false) }
        var pairCode by remember { mutableStateOf<dev.siconnect.app.transport.PairingInvitation?>(null) }
        var hasBotKey by remember { mutableStateOf(false) }
        var pairedBot by remember { mutableStateOf<AgentRecord?>(null) }
        var allowBot by remember { mutableStateOf<AgentRecord?>(null) }
        var reviewId by rememberSaveable { mutableStateOf<String?>(null) }
        var review by remember { mutableStateOf<RequestRecord?>(null) }
        var inspected by remember { mutableStateOf<RequestRecord?>(null) }
        var inspectedEvents by remember { mutableStateOf(emptyList<Event>()) }
        val bots=snapshot.agents.filterNot {it.grant.agentId.startsWith("local-")}
        var manageBotId by rememberSaveable { mutableStateOf<String?>(null) }
        val manageBot=bots.firstOrNull { it.grant.agentId==manageBotId }
        val labels=snapshot.agents.associate {it.grant.agentId to botDisplayName(it,snapshot.agents)}
        val reminders=snapshot.reminders
        var reminder by remember { mutableStateOf<dev.siconnect.app.capabilities.Reminder?>(null) }
        var exactAccess by remember { mutableStateOf(false) }
        var notifications by remember { mutableStateOf(getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
        fun work(block: suspend ()->Unit) { scope.launch { busy=true; try { withContext(Dispatchers.IO) { g.ready.await(); block() }; changed++ } catch(e: CancellationException) {throw e} catch(e: Exception) { note=e.message ?: "Action failed" } finally { busy=false } } }
        LaunchedEffect(changed,permissionRevision) {
            controlModel.refresh();exactAccess=g.reminders.canScheduleExact()
            reminder=withContext(Dispatchers.IO) {reminder?.let {g.reminders.get(it.id)}}
        }
        LaunchedEffect(reviewId,changed) {
            review=withContext(Dispatchers.IO) { g.ready.await();reviewId?.let(g.requests::get) }
        }
        LaunchedEffect(Unit) { g.engine.recordUpdates.collect { record -> if(reviewId==record.requestId) review=record } }
        LaunchedEffect(pending,permissionRevision) {
            val target=intent.getStringExtra("requestId")
            if(target!=null) {
                val stored=withContext(Dispatchers.IO) { g.ready.await();g.requests.get(target) }
                if(stored!=null) {
                    destination=Destination.HOME;advanced=false;reviewId=stored.requestId;review=stored
                    // Consume this delivery, not the request identity: a later notification tap
                    // must reopen the same action, including its resolved/expired result.
                    intent.removeExtra("requestId")
                }
            }
        }
        LaunchedEffect(inspected) { inspectedEvents=withContext(Dispatchers.IO) { inspected?.let { g.events.query(requestId=it.requestId,limit=30) } ?: emptyList() } }
        LaunchedEffect(pairDialog) { if(pairDialog) withContext(Dispatchers.IO) {
            g.ready.await();pairCode=g.settings.pendingPairing();pairCode?.let {pairName=it.label}
        } }
        LaunchedEffect(setup,changed) { if(setup) withContext(Dispatchers.IO) { g.ready.await(); val key=g.botKeyForOwner(); hasBotKey=key!=null; pairedBot=key?.let(g::authenticate) } }
        fun copyForBot(value: String, secret: Boolean) {
            val clip=android.content.ClipData.newPlainText(if(secret) "SIConnect bot key" else "SIConnect MCP address",value)
            if(secret && android.os.Build.VERSION.SDK_INT>=33) clip.description.extras=android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true)
            }
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
            note=if(secret) "Bot key copied. Paste it into your bot's secure token field." else "MCP address copied."
        }
        ControlCenterNavigation(destination,{destination=it}) {wide ->
            when(destination) {
                Destination.HOME -> HomeDashboard(snapshot,wide,connection,connectionDetail,health.startsWith("Ready"),g.privileged.available(),g.privileged.kind.name,if(g.settings.privilegeBackend=="shizuku") shizuku else direct,controls,
                    measurements,busy,note,onReview={reviewId=it.requestId;review=it},onRequest={id->work {inspected=g.requests.get(id)}},
                    onAgent={selectedAgentId=it;destination=Destination.AGENTS},onAgents={destination=Destination.AGENTS},
                    onConnect={setup=true},onRestore={controlHelp=true},onAutomations={destination=Destination.AUTOMATIONS},onActivity={destination=Destination.ACTIVITY},
                    onHealth={work {val r=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="device.runtime"),Origin.LOCAL_OWNER);require(r.state==RequestState.SUCCEEDED) {r.errorMessage ?: "Health unavailable"};measurements=r.result!!.output.jsonObject}},
                    onStop={g.settings.setControls(g.settings.controls.value.copy(remoteExecutionEnabled=false))},
                    onResume={ownerAuthentication.authenticate("Resume remote requests",{g.settings.setControls(g.settings.controls.value.copy(remoteExecutionEnabled=true))},{note=it})},
                    onEnableSemantic={startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))},
                    onEnableNotifications={startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))},onInbox={inbox=true})
                Destination.AGENTS -> AgentsScreen(snapshot,wide,selectedAgentId,
                    onPair={ownerAuthentication.authenticate("Pair new agent",{pairDialog=true},{note=it})},
                    onFamily={id,family,enabled->
                        val apply={work {g.setCapabilityFamilyFromOwner(id,family,enabled)}}
                        if(enabled) ownerAuthentication.authenticate("Allow $family access",apply,{note=it}) else apply()
                    },onShell={allowBot=it},onReconnect={bot->ownerAuthentication.authenticate("Reconnect selected identity",{work {pairCode=g.pairing.reconnect(bot.grant.agentId);pairName=bot.grant.label;pairDialog=true}},{note=it})},
                    onRevoke={bot->work {g.revokeAgentFromOwner(bot.grant.agentId)}},onRequest={id->work {inspected=g.requests.get(id)}},
                    onSemanticApp={id,pkg,enabled->
                        val apply={work {g.setSemanticPackagePermissionFromOwner(id,pkg,enabled)}}
                        if(enabled) ownerAuthentication.authenticate("Allow screen actions in $pkg",apply,{note=it}) else apply()
                    })
                Destination.AUTOMATIONS -> AutomationsScreen(g,authenticate={title,yes,no->ownerAuthentication.authenticate(title,yes,no)},onChanged={controlModel.refresh()},modifier=Modifier.padding(16.dp))
                Destination.ACTIVITY -> ActivityScreen(g.requests,snapshot.revision,labels,{id->g.registry.get(id)?.descriptor?.title ?: id}) {id->work {inspected=g.requests.get(id)}}
                Destination.SETTINGS -> Management("Settings")
            }
        }
        if(activity) ActivityDialog(g.requests,snapshot.revision,labels,title={id -> g.registry.get(id)?.descriptor?.title ?: id},onClose={activity=false},onOpen={id -> work {inspected=g.requests.get(id)}})
        if(inbox) ActionInbox(g,snapshot.revision,{title,yes,no->ownerAuthentication.authenticate(title,yes,no)},onClose={inbox=false},onRequest={id->work {
            val r=requireNotNull(g.requests.get(id));inbox=false
            if(r.state==RequestState.AWAITING_APPROVAL) {reviewId=id;review=r} else inspected=r
        }})
        manageBot?.let { bot -> AlertDialog(onDismissRequest={},properties=androidx.compose.ui.window.DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false),title={Text(botDisplayName(bot,bots))},text={Column(Modifier.heightIn(max=with(androidx.compose.ui.platform.LocalDensity.current) {androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()}*0.55f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            listOf("apps" to ("Apps" to "Install, inspect and remove apps. Sensitive actions still ask first."),
                "files" to ("Managed files" to "SIConnect’s own files only. Other apps’ private data stays isolated."),
                "calendar" to ("Calendar" to "Read and manage selected calendars after Android calendar access is enabled."),
                "ui" to ("Shared screen" to "Reserve the shared screen for coordinated app actions. Broad shell remains separate."),
                "documents" to ("Selected folders" to "Use only folders you connect through Android’s document picker."),
                "weather" to ("Weather" to "Forecasts and deterministic commute calculations; reminders follow their own permission.")).forEach { (family,text) ->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {Text(text.first);Text(text.second,style=MaterialTheme.typography.bodySmall)}
                    val allowed=bot.grant.scopes.any {it=="*" || it=="$family.*"} && bot.grant.denied.none {it=="*" || it=="$family.*"}
                    Switch(allowed,{enabled ->
                        val apply={work {g.setCapabilityFamilyFromOwner(bot.grant.agentId,family,enabled);note="${text.first} permission saved for ${botDisplayName(bot,bots)}"}}
                        if(enabled) ownerAuthentication.authenticate("Allow ${text.first}",apply,{note=it}) else apply()
                    },enabled=!busy)
                }
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(note.isNotBlank()) Text(note)
            Text("Permissions are saved on this phone. This screen stays open until you tap Done. Ask your bot to retry after changing a permission. Previously denied requests stay denied.")
            if(bot.grant.shellMode==ShellMode.BROAD) OutlinedButton(onClick={manageBotId=null;work {g.setShellPermissionFromOwner(bot.grant.agentId,false)}}) {Text("Ask before device control")}
            else OutlinedButton(onClick={manageBotId=null;allowBot=bot}) {Text("Always allow device control…")}
            if(bot.grant.agentId.startsWith("bot_")) OutlinedButton(onClick={ownerAuthentication.authenticate("Reconnect selected bot",{work {
                pairCode=g.pairing.reconnect(bot.grant.agentId);pairName=bot.grant.label
                manageBotId=null;pairError="";pairDialog=true
            }},{note=it})},enabled=!busy) {Text("Reconnect this bot")}
            TextButton(onClick={manageBotId=null;work {g.revokeAgentFromOwner(bot.grant.agentId)}}) {Text("Revoke this bot",color=MaterialTheme.colorScheme.error)}
        }},confirmButton={TextButton(onClick={manageBotId=null}) {Text("Done")}}) }
        if(controlHelp) AlertDialog(onDismissRequest={controlHelp=false},title={Text("Enable full device control")},
            text={Text("SIConnect includes its own control service. No companion app is needed.\n\nOn your connected Mac, double-click Start SIConnect. Then tap Check here.\n\nAndroid requires this authorized setup again after a phone restart. Native tools keep working.")},
            confirmButton={TextButton(onClick={controlHelp=false;work { g.privileged.refresh(); note=if(g.privileged.available()) "Device control is ready" else "Setup has not connected yet" }}) {Text("Check connection")}},
            dismissButton={TextButton(onClick={controlHelp=false}) {Text("Later")}})
        if(setup) AlertDialog(onDismissRequest={setup=false},title={Text("Connect your bot")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(connection==dev.siconnect.app.transport.ConnectionState.ONLINE) "Your gateway is connected. Add the MCP address and your agent key to your bot." else "Paste your SIConnect setup code. It connects this phone to your private gateway.")
            if(config.baseUrl.isNotBlank()) {
                Text(config.baseUrl.trimEnd('/')+"/mcp",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={copyForBot(config.baseUrl.trimEnd('/')+"/mcp",false)}) { Text("Copy address") }
            }
            OutlinedButton(onClick={ownerAuthentication.authenticate("Manage bot pairing",{pairDialog=true;setup=false},{note=it})},enabled=connection==dev.siconnect.app.transport.ConnectionState.ONLINE) { Text("Pair another bot") }
            if(hasBotKey) Button(onClick={ownerAuthentication.authenticate("Copy bot credential",{work {
                val token=requireNotNull(g.botKeyForOwner()) { "This bot key was revoked. Pair a new agent in Advanced." }
                withContext(Dispatchers.Main) { copyForBot(token,true) }
            }},{note=it})},enabled=!busy) { Text("Copy bot key") }
            pairedBot?.let { a ->
                Text(if(a.grant.shellMode==ShellMode.BROAD) "Device control: always allowed" else "Device control: ask each time")
                if(a.grant.shellMode!=ShellMode.BROAD) OutlinedButton(onClick={allowBot=a}) {Text("Always allow device control…")}
                else TextButton(onClick={work {g.setShellPermissionFromOwner(a.grant.agentId,false)}}) {Text("Ask each time again")}
            }
            if(connection!=dev.siconnect.app.transport.ConnectionState.ONLINE) OutlinedTextField(connectionCode,{connectionCode=it},label={Text("Setup code")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),maxLines=3)
        }},confirmButton={TextButton(onClick={work {
            val text=if(connectionCode.trim().startsWith("{")) connectionCode.trim() else String(android.util.Base64.decode(connectionCode.trim(),android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
            val code=Json.parseToJsonElement(text).jsonObject
            val endpoint=code.getValue("url").jsonPrimitive.content
            require(endpoint.startsWith("https://")) {"A secure gateway address is required"}
            g.settings.setEnrollToken(code.getValue("enroll").jsonPrimitive.content)
            g.settings.setGateway(GatewayConfig(endpoint,true)); g.settings.enrolled=false
            withContext(Dispatchers.Main) { startForegroundService(Intent(this@MainActivity,ConnectionService::class.java)) }
            setup=false; note="Connecting your phone…"
        }},enabled=connectionCode.isNotBlank()) {Text("Connect")}},dismissButton={TextButton(onClick={setup=false}) {Text("Close")}})
        if(pairDialog) AlertDialog(onDismissRequest={pairDialog=false;pairCode=null},title={Text(if(pairCode==null) "Pair a new bot" else "Continue pairing ${pairCode!!.label}")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(busy) { LinearProgressIndicator(Modifier.fillMaxWidth());Text("Preparing your secure pairing code…") }
            if(pairError.isNotBlank()) Text(pairError,color=MaterialTheme.colorScheme.error)
            if(pairCode==null) {
                Text("Creates a separate bot with its own permissions, even when another bot has the same name. To preserve an existing bot’s permissions, open its card and choose Reconnect this bot.")
                OutlinedTextField(pairName,{pairName=it.take(60)},label={Text("Bot name")},singleLine=true)
                Text("Add SIConnect’s MCP address in your bot, choose OAuth, and enter the temporary code on the SIConnect sign-in page.")
            } else {
                Text("Bot ID: …${pairCode!!.agentId.takeLast(8)}")
                Text("Expires at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(pairCode!!.expiresAt))}. Works once. Paste it only into your gateway’s SIConnect sign-in page.")
                Text(pairCode!!.code,style=MaterialTheme.typography.bodySmall)
                Text("Closing this screen preserves the invitation. Renewing an expired code preserves this exact bot and its permissions.")
                TextButton(onClick={work {g.pairing.dismissInvitation(pairCode!!.agentId);pairCode=null;pairError=""}},enabled=!busy) {Text("Dismiss invitation (published code expires normally)")}
            }
        }},confirmButton={Button(onClick={work {
            pairError=""
            try {
                val current=pairCode
                pairCode=if(current==null) g.pairing.create(pairName) else g.pairing.renew(current.agentId)
                if(current!=null) withContext(Dispatchers.Main) {copyForBot(requireNotNull(pairCode).code,true)}
            } catch(e:Exception) {
                pairCode=g.settings.pendingPairing()
                pairError=if(e is TimeoutCancellationException) "Gateway did not confirm the code. Check connection and retry this invitation." else e.message ?: "Could not prepare invitation"
            }
        }},enabled=!busy && pairName.isNotBlank()) {Text(if(pairCode==null) "Create new bot" else if(pairCode!!.expiresAt<=System.currentTimeMillis()) "Renew and copy code" else "Copy pairing code")}},dismissButton={TextButton(onClick={pairDialog=false;pairCode=null}) {Text("Close")}})
        review?.let { r -> OwnerApprovalDialog(r,busy=busy,error=note,onClose={reviewId=null;review=null},onDeny={work {review=g.engine.resolveApproval(r.requestId,false,"local-owner",r.argsHash)}},onApprove={always,broad ->
            ownerAuthentication.authenticate("Approve ${r.capabilityId}",{
                if(reviewId==r.requestId) work {review=g.approveFromOwner(r.requestId,r.argsHash,always,broad)}
            },{note=it})
        }) }
        inspected?.let { r -> AlertDialog(onDismissRequest={inspected=null},title={Text(g.registry.get(r.capabilityId)?.descriptor?.title ?: r.capabilityId)},
            text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("${actionStateLabel(r.state)} · ${labels[r.agentId] ?: r.agentId}")
                r.policy?.let {Text(it.reason)}
                if(r.state==RequestState.DENIED && r.policy?.rule in setOf("agent_scope","agent_denylist") && bots.any {it.grant.agentId==r.agentId}) {
                    Button(onClick={inspected=null;activity=false;note="";manageBotId=r.agentId}) {Text("Review this bot’s permissions")}
                }
                Text("Request: ${r.requestId}\nBackend: ${r.result?.backend ?: r.selectedBackend ?: "not executed"}")
                r.assessment?.shellCommand?.let { Text(it) }
                r.errorMessage?.let { Text(it) }
                r.result?.let { Text(it.output.toString(),style=MaterialTheme.typography.bodySmall) }
                HorizontalDivider(); Text("Audit trail",style=MaterialTheme.typography.titleMedium)
                inspectedEvents.reversed().forEach { Text("${it.eventType} · ${it.policyDecision ?: it.executionStatus ?: it.errorCode ?: ""}",style=MaterialTheme.typography.bodySmall) }
            }},confirmButton={TextButton(onClick={inspected=null}) { Text("Close") }}) }
        allowBot?.let { a -> ShellPermissionDialog(botDisplayName(a,bots),busy=busy,allowed=a.grant.shellMode==ShellMode.BROAD,onClose={allowBot=null},onReduce={work {g.setShellPermissionFromOwner(a.grant.agentId,false);allowBot=g.agents.get(a.grant.agentId)}},onAllow={
            ownerAuthentication.authenticate("Always allow device control",{
                if(allowBot?.grant?.agentId==a.grant.agentId) work {g.setShellPermissionFromOwner(a.grant.agentId,true);allowBot=g.agents.get(a.grant.agentId);note="Always allowed. Saved on this phone until you revoke it."}
            },{note=it})
        }) }
        reminder?.let { r -> AlertDialog(onDismissRequest={reminder=null},title={Text(r.title)},
            text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(r.text)
                Text(java.time.Instant.ofEpochMilli(r.fireAtMs).atZone(java.time.ZoneId.systemDefault()).toString())
                Text(if(r.exact) "Precise timing through Android AlarmManager" else "Flexible timing through Android WorkManager")
                r.error?.let {Text(it)}
            }},confirmButton={Column {
                if(!r.exact || r.state=="BLOCKED") Button(onClick={
                    if(!exactAccess) startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName")))
                    else work {
                        val result=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="reminders.update",arguments=buildJsonObject {put("id",r.id);put("exact",true)}),Origin.LOCAL_OWNER)
                        check(result.state==RequestState.SUCCEEDED) {result.errorMessage ?: "Could not schedule precise reminder"}
                        note="Precise reminder saved. Its time and content are unchanged.";reminder=null
                    }
                }) {Text(if(exactAccess) "Use precise time" else "Enable precise reminders")}
                TextButton(onClick={reminder=null}) {Text("Close")}
            }}) }

    }

}
