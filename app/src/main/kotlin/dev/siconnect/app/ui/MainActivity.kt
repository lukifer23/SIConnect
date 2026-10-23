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
    internal var permissionStatus by mutableStateOf("")
    internal val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permissionStatus=if(grants.isEmpty()) "Android access request cancelled." else if(grants.values.all {it}) "Requested Android access is granted." else "Android did not grant all requested access. Open app permissions to review denied or restricted access; other features remain available."
        getSharedPreferences("android_access_requests",MODE_PRIVATE).edit().apply {grants.keys.forEach {putBoolean(it,true)}}.apply()
        permissionRevision++;graph.gateway.refreshCatalog()
    }
    internal fun requestNativeAccess(requested:Array<String>) {
        val missing=requested.filter {checkSelfPermission(it)!=android.content.pm.PackageManager.PERMISSION_GRANTED}
        if(missing.isEmpty()) return
        val history=getSharedPreferences("android_access_requests",MODE_PRIVATE)
        if(missing.any {history.getBoolean(it,false) && !shouldShowRequestPermissionRationale(it)}) {
            permissionStatus="Android requires you to change this access in app permissions. Other ready tools remain available."
            startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
        } else permission.launch(missing.toTypedArray())
    }
    override fun onResume() { super.onResume(); permissionStatus="";permissionRevision++;graph.gateway.refreshCatalog();if(::controlModel.isInitialized) controlModel.refresh() }
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
        val connectionHistoryRevision by g.gateway.historyRevision.collectAsState()
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
        var controlHelp by rememberSaveable { mutableStateOf(false) }
        var nativeSetup by rememberSaveable {mutableStateOf(!getSharedPreferences("owner_setup",MODE_PRIVATE).getBoolean("reviewed",false) && g.settings.gateway.value.baseUrl.isBlank())}
        var connectionCode by remember { mutableStateOf("") }
        var pairName by remember { mutableStateOf("Dots") }
        var pairError by remember { mutableStateOf("") }
        var pairDialog by remember { mutableStateOf(false) }
        var pairCode by remember { mutableStateOf<dev.siconnect.app.transport.PairingInvitation?>(null) }
        var hasBotKey by remember { mutableStateOf(false) }
        var legacyCredential by remember {mutableStateOf(false)}
        var replaceGateway by remember {mutableStateOf(false)}
        var gatewayError by remember {mutableStateOf("")}
        var showConnectionHistory by rememberSaveable {mutableStateOf(false)}
        var connectionHistory by remember {mutableStateOf(emptyList<Event>())}
        var historyError by remember {mutableStateOf("")}
        LaunchedEffect(setup,showConnectionHistory,connectionHistoryRevision) {
            if(setup && showConnectionHistory) {
                try {
                    connectionHistory=withContext(Dispatchers.IO) {g.ready.await();g.events.query(eventTypePrefix="gateway.",limit=10)}
                    historyError=""
                } catch(e:CancellationException) {throw e}
                  catch(_:Exception) {historyError="Connection history is temporarily unavailable"}
            }
        }
        var allowBot by remember { mutableStateOf<AgentRecord?>(null) }
        var reviewId by rememberSaveable { mutableStateOf<String?>(null) }
        var review by remember { mutableStateOf<RequestRecord?>(null) }
        var inspected by remember { mutableStateOf<RequestRecord?>(null) }
        var inspectedEvents by remember { mutableStateOf(emptyList<Event>()) }
        val bots=snapshot.agents.filterNot {it.grant.agentId.startsWith("local-")}
        val labels=snapshot.agents.associate {it.grant.agentId to botDisplayName(it,snapshot.agents)}
        val reminders=snapshot.reminders
        var reminder by remember { mutableStateOf<dev.siconnect.app.capabilities.Reminder?>(null) }
        var exactAccess by remember { mutableStateOf(false) }
        var notifications by remember { mutableStateOf(getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
        fun work(block: suspend ()->Unit) { scope.launch { busy=true; try { withContext(Dispatchers.IO) { g.ready.await(); block() }; changed++ } catch(e: CancellationException) {throw e} catch(e: Exception) { note=e.message ?: "Action failed" } finally { busy=false } } }
        LaunchedEffect(changed,permissionRevision) {
            controlModel.refresh();exactAccess=g.reminders.canScheduleExact()
            notifications=getSystemService(NotificationManager::class.java).areNotificationsEnabled()
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
        LaunchedEffect(setup,changed) { if(setup) withContext(Dispatchers.IO) { g.ready.await(); val key=g.botKeyForOwner(); hasBotKey=key!=null } }
        LaunchedEffect(setup) {if(setup) {replaceGateway=false;legacyCredential=false;gatewayError=""}}
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
                Destination.HOME -> HomeDashboard(snapshot,wide,connection,connectionDetail,health.startsWith("Ready"),g.privileged.available(),controls,
                    measurements,busy,note,onReview={reviewId=it.requestId;review=it},onRequest={id->work {inspected=g.requests.get(id)}},
                    onAgent={selectedAgentId=it;destination=Destination.AGENTS},onAgents={destination=Destination.AGENTS},
                    onConnect={setup=true},onRestore={controlHelp=true},onAutomations={destination=Destination.AUTOMATIONS},onActivity={destination=Destination.ACTIVITY},
                    onHealth={work {val r=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="device.runtime"),Origin.LOCAL_OWNER);require(r.state==RequestState.SUCCEEDED) {r.errorMessage ?: "Health unavailable"};measurements=r.result!!.output.jsonObject}},
                    onStop={g.settings.setControls(g.settings.controls.value.copy(remoteExecutionEnabled=false))},
                    onResume={ownerAuthentication.authenticate("Resume remote requests",{g.settings.setControls(g.settings.controls.value.copy(remoteExecutionEnabled=true))},{note=it})},
                    onInbox={inbox=true},onSetup={destination=Destination.SETTINGS})
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
                Destination.SETTINGS -> Management("Settings",onSetup={nativeSetup=true},onGateway={setup=true},onAdvancedControl={controlHelp=true})
            }
        }
        if(activity) ActivityDialog(g.requests,snapshot.revision,labels,title={id -> g.registry.get(id)?.descriptor?.title ?: id},onClose={activity=false},onOpen={id -> work {inspected=g.requests.get(id)}})
        if(inbox) ActionInbox(g,snapshot.revision,{title,yes,no->ownerAuthentication.authenticate(title,yes,no)},onClose={inbox=false},onRequest={id->work {
            val r=requireNotNull(g.requests.get(id));inbox=false
            if(r.state==RequestState.AWAITING_APPROVAL) {reviewId=id;review=r} else inspected=r
        }})
        if(nativeSetup) NativeSetup(onClose={nativeSetup=false},onGateway={nativeSetup=false;setup=true},onAgents={nativeSetup=false;destination=Destination.AGENTS},onAdvanced={nativeSetup=false;controlHelp=true})
        if(controlHelp) AdvancedControlSetup {controlHelp=false}
        if(setup) AlertDialog(onDismissRequest={setup=false;connectionCode=""},title={Text("Gateway connection")},text={Column(
            Modifier.heightIn(max=with(androidx.compose.ui.platform.LocalDensity.current) {androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()}*.6f).verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(connection==dev.siconnect.app.transport.ConnectionState.ONLINE) "Connected. This phone can receive authorized agent requests." else connectionDetail)
            if(connection!=dev.siconnect.app.transport.ConnectionState.ONLINE && config.enabled)
                Text("Native schedules continue using available Android access. Interrupted actions are not automatically resubmitted.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={showConnectionHistory=!showConnectionHistory}) {Text(if(showConnectionHistory) "Hide connection history" else "Connection history")}
            if(showConnectionHistory) {
                if(historyError.isNotBlank()) Text(historyError,color=MaterialTheme.colorScheme.error)
                else if(connectionHistory.isEmpty()) Text("No retained connection events",style=MaterialTheme.typography.bodySmall)
                connectionHistory.forEach {event->
                    val time=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(event.timestampMs))
                    Text("$time · "+when(event.eventType) {
                        "gateway.connected"->"Connected"
                        "gateway.paused"->"Remote access paused by owner"
                        "gateway.interrupted"->event.detail ?: "Connection interrupted"
                        "gateway.result_delivery_failed"->"Result delivery interrupted. Retrieve the original request outcome before retrying."
                        else->"Gateway message could not be processed"
                    },style=MaterialTheme.typography.bodySmall)
                }
                Text("Stored on this phone under the existing event retention policy. Pairing codes and credentials are excluded.",style=MaterialTheme.typography.bodySmall)
            }
            if(config.baseUrl.isNotBlank()) {
                Text(config.baseUrl.trimEnd('/')+"/mcp",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={copyForBot(config.baseUrl.trimEnd('/')+"/mcp",false)}) {Text("Copy MCP address")}
                if(connection!=dev.siconnect.app.transport.ConnectionState.ONLINE) Button(onClick={
                    val reconnect={work {
                        g.gateway.stop();g.settings.setGateway(g.settings.gateway.value.copy(enabled=true))
                        withContext(Dispatchers.Main) {startForegroundService(Intent(this@MainActivity,ConnectionService::class.java))}
                    }}
                    if(config.enabled) reconnect() else ownerAuthentication.authenticate("Enable existing gateway",reconnect,{gatewayError=it})
                },enabled=!busy) {Text("Reconnect existing gateway")}
                if(config.enabled) OutlinedButton(onClick={work {
                    g.gateway.pauseFromOwner()
                    withContext(Dispatchers.Main) {stopService(Intent(this@MainActivity,ConnectionService::class.java))}
                    gatewayError=""
                }},enabled=!busy) {Text("Pause remote access")}
                TextButton(onClick={setup=false;destination=Destination.AGENTS}) {Text("Manage AI agents")}
                TextButton(onClick={replaceGateway=!replaceGateway}) {Text(if(replaceGateway) "Keep existing gateway" else "Use a different gateway")}
            }
            if(config.baseUrl.isBlank() || replaceGateway) {
                Text("Paste the secure setup code from your existing gateway. Cloudflare provisioning is separate.")
                OutlinedTextField(connectionCode,{connectionCode=it;gatewayError=""},label={Text("Gateway setup code")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),maxLines=3,modifier=Modifier.fillMaxWidth())
                Button(onClick={
                    val parsed=runCatching {dev.siconnect.app.bootstrap.GatewaySetupCode.parse(connectionCode)}
                    if(parsed.isFailure) gatewayError=parsed.exceptionOrNull()?.message ?: "Invalid setup code"
                    else ownerAuthentication.authenticate("Enroll with this gateway",{work {
                        val code=parsed.getOrThrow()
                        g.gateway.stop();g.settings.setEnrollToken(code.enrollment)
                        g.settings.setGateway(GatewayConfig(code.endpoint,true));g.settings.enrolled=false
                        withContext(Dispatchers.Main) {startForegroundService(Intent(this@MainActivity,ConnectionService::class.java))}
                        connectionCode="";setup=false;note="Connecting your phone…"
                    }},{gatewayError=it})
                },enabled=connectionCode.isNotBlank() && !busy) {Text("Connect gateway")}
            }
            if(gatewayError.isNotBlank()) Text(gatewayError,color=MaterialTheme.colorScheme.error)
            if(hasBotKey) {
                TextButton(onClick={legacyCredential=!legacyCredential}) {Text(if(legacyCredential) "Hide legacy credential" else "Advanced: existing bearer credential")}
                if(legacyCredential) {
                    Text("This is the existing shared bearer identity. Manage its permissions in Agents. Copying it does not create another agent.",style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={ownerAuthentication.authenticate("Copy existing bearer credential",{work {
                        val token=requireNotNull(g.botKeyForOwner()) {"Existing credential is unavailable; inspect Agents"}
                        withContext(Dispatchers.Main) {copyForBot(token,true)}
                    }},{gatewayError=it})},enabled=!busy) {Text("Copy existing credential")}
                }
            }
        }},confirmButton={TextButton(onClick={setup=false;connectionCode="";gatewayError=""}) {Text("Done")}})
        if(pairDialog) AlertDialog(onDismissRequest={pairDialog=false;pairCode=null},title={Text(if(pairCode==null) "Pair a new bot" else "Continue pairing ${pairCode!!.label}")},text={Column(Modifier.heightIn(max=with(androidx.compose.ui.platform.LocalDensity.current) {androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()}*.6f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
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
                    Button(onClick={inspected=null;activity=false;note="";selectedAgentId=r.agentId;destination=Destination.AGENTS}) {Text("Review this bot’s permissions")}
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
