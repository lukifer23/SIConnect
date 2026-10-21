package dev.siconnect.app.ui

import android.Manifest
import android.app.*
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
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

open class MainActivity: ComponentActivity() {
    private var permissionRevision by mutableIntStateOf(0)
    private val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRevision++;graph.gateway.refreshCatalog() }
    override fun onResume() { super.onResume(); permissionRevision++;graph.gateway.refreshCatalog() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);permissionRevision++ }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF8ADCC3),onPrimary=Color(0xFF062B23),background=Color(0xFF0D1218),surface=Color(0xFF151E27),surfaceVariant=Color(0xFF202D39))) {
            Surface(Modifier.fillMaxSize()) { Home() }
        } }
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
        val controls by g.settings.controls.collectAsState()
        val health by g.health.collectAsState()
        var advanced by remember { mutableStateOf(false) }
        var changed by remember { mutableIntStateOf(0) }
        var pending by remember { mutableStateOf(emptyList<RequestRecord>()) }
        var recent by remember { mutableStateOf(emptyList<RequestSummary>()) }
        var deniedApps by remember { mutableStateOf(emptyList<RequestRecord>()) }
        var activity by rememberSaveable { mutableStateOf(false) }
        var note by remember { mutableStateOf("") }
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
        var bots by remember { mutableStateOf(emptyList<AgentRecord>()) }
        var manageBotId by rememberSaveable { mutableStateOf<String?>(null) }
        val manageBot=bots.firstOrNull { it.grant.agentId==manageBotId }
        var labels by remember { mutableStateOf(emptyMap<String,String>()) }
        var reminders by remember { mutableStateOf(emptyList<dev.siconnect.app.capabilities.Reminder>()) }
        var reminder by remember { mutableStateOf<dev.siconnect.app.capabilities.Reminder?>(null) }
        var exactAccess by remember { mutableStateOf(false) }
        var notifications by remember { mutableStateOf(getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
        fun work(block: suspend ()->Unit) { scope.launch { busy=true; try { withContext(Dispatchers.IO) { g.ready.await(); block() }; changed++ } catch(e: Exception) { note=e.message ?: "Action failed" } finally { busy=false } } }
        LaunchedEffect(changed,health,connection,permissionRevision) {
            withContext(Dispatchers.IO) { runCatching { g.ready.await(); recent=g.requests.recentSummaries(100);deniedApps=g.requests.recentDeniedApps(); pending=g.requests.listByStates(setOf(RequestState.AWAITING_APPROVAL)); val agentRecords=g.agents.all();bots=agentRecords.filter { !it.grant.agentId.startsWith("local-") && !it.grant.revoked };labels=agentRecords.associate { it.grant.agentId to botDisplayName(it,agentRecords) }
                reminders=g.reminders.all().filter { it.state in setOf("SCHEDULED","BLOCKED") }.sortedBy { it.fireAtMs };exactAccess=g.reminders.canScheduleExact()
                reminder=reminder?.let { g.reminders.get(it.id) }
            }.onFailure {note="Could not load activity: ${it.javaClass.simpleName}"} }
            notifications=getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }
        LaunchedEffect(reviewId,changed) {
            review=withContext(Dispatchers.IO) { g.ready.await();reviewId?.let(g.requests::get) }
        }
        LaunchedEffect(Unit) { g.engine.recordUpdates.collect { record -> changed++;if(reviewId==record.requestId) review=record } }
        LaunchedEffect(pending,permissionRevision) {
            val target=intent.getStringExtra("requestId")
            if(target!=null) {
                val stored=withContext(Dispatchers.IO) { g.ready.await();g.requests.get(target) }
                if(stored!=null) {
                    advanced=false;reviewId=stored.requestId;review=stored
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
        if(advanced) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                TextButton(onClick={advanced=false}) { Text("← Back to connection") }
                Box(Modifier.weight(1f)) { Management() }
            }
            return
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=22.dp,vertical=20.dp),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Column(Modifier.widthIn(max=640.dp).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("SIConnect",style=MaterialTheme.typography.headlineLarge); Text("Your phone. Your bots.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(when(connection) {
                        dev.siconnect.app.transport.ConnectionState.ONLINE->"CONNECTED"
                        dev.siconnect.app.transport.ConnectionState.BACKOFF->"RECONNECTING"
                        dev.siconnect.app.transport.ConnectionState.AUTH_FAILED->"NEEDS ATTENTION"
                        dev.siconnect.app.transport.ConnectionState.DISABLED->if(config.baseUrl.isBlank()) "SETUP" else "PAUSED"
                        else->"CONNECTING"
                    },color=if(connection==dev.siconnect.app.transport.ConnectionState.AUTH_FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
                }
                if(pending.isNotEmpty()) {
                    Text("Needs your approval",style=MaterialTheme.typography.titleLarge)
                    pending.forEach { r -> Card(shape=RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text(labels[r.agentId] ?: r.agentId,style=MaterialTheme.typography.labelLarge)
                        Text(r.assessment?.shellCommand ?: r.capabilityId,style=MaterialTheme.typography.titleMedium,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(r.assessment?.sideEffects?.joinToString() ?: "Review the exact action before allowing it")
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Button(onClick={note="";reviewId=r.requestId;review=r}) { Text("Review") }
                            OutlinedButton(onClick={work { g.engine.resolveApproval(r.requestId,false,"local-owner",r.argsHash) }}) { Text("Deny") }
                        }
                    } } }
                }
                Card(shape=RoundedCornerShape(24.dp)) { Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(if(connection==dev.siconnect.app.transport.ConnectionState.ONLINE) "Ready for your bot" else if(config.enabled && config.baseUrl.isNotBlank()) "Restoring your connection" else "Connect your phone",style=MaterialTheme.typography.headlineSmall)
                    if(config.baseUrl.isNotBlank()) Text(connectionDetail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(connection==dev.siconnect.app.transport.ConnectionState.ONLINE) "Requests run on this phone. You approve sensitive actions here." else "Enable access, then connect your bot. Every action is checked and logged.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    recent.firstOrNull { r -> bots.any {it.grant.agentId==r.agentId} }?.let { last ->
                        Text("Last bot request: ${java.time.Instant.ofEpochMilli(last.createdAtMs).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("h:mm:ss a"))}",style=MaterialTheme.typography.labelLarge)
                    }
                    Button(onClick={setup=true},modifier=Modifier.fillMaxWidth().height(52.dp)) { Text(if(connection==dev.siconnect.app.transport.ConnectionState.ONLINE) "Connection details" else "Connect a bot") }
                    if(config.baseUrl.isNotBlank()) Text(config.baseUrl.trimEnd('/')+"/mcp",style=MaterialTheme.typography.bodySmall)
                } }
                deniedApps.filter { it.policy?.rule in setOf("agent_scope","agent_denylist") }
                    .distinctBy { it.agentId }.filter { r -> bots.any { bot -> bot.grant.agentId==r.agentId && (bot.grant.scopes.none { PolicyEngine.globMatches(it,r.capabilityId) } || bot.grant.denied.any { PolicyEngine.globMatches(it,r.capabilityId) }) } }.take(3).forEach { r ->
                        Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text("${labels[r.agentId] ?: "Your bot"} needs app permission",style=MaterialTheme.typography.titleMedium)
                            Text("The phone received this request and blocked it. This connection is working; app management is not enabled for this bot.")
                            Text(r.capabilityId,style=MaterialTheme.typography.bodySmall)
                            Button(onClick={note="";manageBotId=r.agentId}) {Text("Review this bot’s permissions")}
                        } }
                    }
                Text("Access",style=MaterialTheme.typography.titleLarge)
                Card(shape=RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Notifications",style=MaterialTheme.typography.titleMedium); Text(if(notifications) "Allowed" else "For approvals and reminders",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick={ if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE,packageName)); changed++ }) { Text(if(notifications) "Settings" else "Enable") }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Device control",style=MaterialTheme.typography.titleMedium); Text(if(g.privileged.available()) "Ready · built into SIConnect" else "Native tools ready · full control needs setup",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick={work { g.privileged.refresh(); if(!g.privileged.available()) controlHelp=true }}) { Text(if(g.privileged.available()) "Check" else "Enable") }
                    }
                } }
                if(bots.isNotEmpty()) {
                    Text("Your bots",style=MaterialTheme.typography.titleLarge)
                    bots.sortedByDescending { bot -> recent.firstOrNull { it.agentId==bot.grant.agentId }?.createdAtMs ?: 0 }.forEach { bot -> Card(onClick={manageBotId=bot.grant.agentId},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(botDisplayName(bot,bots),style=MaterialTheme.typography.titleMedium)
                        Text(if(bot.grant.shellMode==ShellMode.BROAD) "Device control always allowed" else "Sensitive actions ask first",style=MaterialTheme.typography.bodySmall)
                        Text("Tap to review or change permissions",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                        val last=recent.firstOrNull { it.agentId==bot.grant.agentId }
                        Text(last?.let { "Last request: ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it.updatedAtMs))} · ${it.state.name.lowercase().replace('_',' ')}" } ?: "Paired locally · no tool call recorded yet",style=MaterialTheme.typography.bodySmall)
                    } } }
                }
                Button(onClick={ work {
                    val r=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="device.status"),Origin.LOCAL_OWNER)
                    check(r.state==RequestState.SUCCEEDED) { r.errorMessage ?: "Device test failed" }
                    note="Device test passed. Read real device status and saved the result."
                } },enabled=!busy,modifier=Modifier.fillMaxWidth().height(52.dp)) { Text(if(busy) "Working…" else "Test this phone") }
                if(note.isNotBlank()) Text(note,color=MaterialTheme.colorScheme.primary)
                if(reminders.isNotEmpty()) {
                    Text("Upcoming reminders",style=MaterialTheme.typography.titleLarge)
                    reminders.take(5).forEach { r -> Card(Modifier.fillMaxWidth().clickable { reminder=r },shape=RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(r.title,style=MaterialTheme.typography.titleMedium)
                            Text(java.time.Instant.ofEpochMilli(r.fireAtMs).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")))
                            Text(if(r.state=="BLOCKED") "Needs attention · ${r.error}" else if(r.exact) "Precise time · saved on this phone" else "Flexible delivery · tap for precise time",style=MaterialTheme.typography.labelSmall)
                        }
                    } }
                }
                if(recent.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("Activity",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                        TextButton(onClick={activity=true}) {Text("View all")}
                    }
                    recent.take(8).forEach { r -> Row(Modifier.fillMaxWidth().clickable { work { inspected=g.requests.get(r.requestId) } }.padding(vertical=6.dp)) {
                        Column(Modifier.weight(1f)) { Text(g.registry.get(r.capabilityId)?.descriptor?.title ?: r.capabilityId,style=MaterialTheme.typography.bodyMedium); Text("${labels[r.agentId] ?: r.agentId} · ${java.time.Instant.ofEpochMilli(r.createdAtMs).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("h:mm:ss a"))}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(actionStateLabel(r.state),style=MaterialTheme.typography.labelMedium)
                    } }
                }
                HorizontalDivider()
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Allow bot requests"); Text(if(controls.remoteExecutionEnabled) "Turn off to stop new bot actions" else "Bot actions are blocked",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(controls.remoteExecutionEnabled,{ g.settings.setControls(controls.copy(remoteExecutionEnabled=it)) })
                }
                TextButton(onClick={advanced=true}) { Text("Advanced · permissions, agents & logs") }
                Text(if(health.startsWith("Ready")) "On-device engine ready" else health,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(activity) ActivityDialog(g.requests,changed,labels,title={id -> g.registry.get(id)?.descriptor?.title ?: id},onClose={activity=false},onOpen={id -> work {inspected=g.requests.get(id)}})
        manageBot?.let { bot -> AlertDialog(onDismissRequest={},properties=androidx.compose.ui.window.DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false),title={Text(botDisplayName(bot,bots))},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            listOf("apps" to ("Apps" to "Install, inspect and remove apps. Sensitive actions still ask first."),
                "files" to ("Managed files" to "SIConnect’s own files only. Other apps’ private data stays isolated.")).forEach { (family,text) ->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {Text(text.first);Text(text.second,style=MaterialTheme.typography.bodySmall)}
                    val allowed=bot.grant.scopes.any {it=="*" || it=="$family.*"} && bot.grant.denied.none {it=="*" || it=="$family.*"}
                    Switch(allowed,{enabled -> work {g.setCapabilityFamilyFromOwner(bot.grant.agentId,family,enabled);note="${text.first} permission saved for ${botDisplayName(bot,bots)}"}},enabled=!busy)
                }
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(note.isNotBlank()) Text(note)
            Text("Permissions are saved on this phone. This screen stays open until you tap Done. Ask your bot to retry after changing a permission. Previously denied requests stay denied.")
            if(bot.grant.shellMode==ShellMode.BROAD) OutlinedButton(onClick={manageBotId=null;work {g.setShellPermissionFromOwner(bot.grant.agentId,false)}}) {Text("Ask before device control")}
            else OutlinedButton(onClick={manageBotId=null;allowBot=bot}) {Text("Always allow device control…")}
            if(bot.grant.agentId.startsWith("bot_")) OutlinedButton(onClick={work {
                pairCode=g.pairing.reconnect(bot.grant.agentId);pairName=bot.grant.label
                manageBotId=null;pairError="";pairDialog=true
            }},enabled=!busy) {Text("Reconnect this bot")}
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
            OutlinedButton(onClick={pairDialog=true;setup=false},enabled=connection==dev.siconnect.app.transport.ConnectionState.ONLINE) { Text("Pair another bot") }
            if(hasBotKey) Button(onClick={work {
                val token=requireNotNull(g.botKeyForOwner()) { "This bot key was revoked. Pair a new agent in Advanced." }
                withContext(Dispatchers.Main) { copyForBot(token,true) }
            }},enabled=!busy) { Text("Copy bot key") }
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
            work { review=g.approveFromOwner(r.requestId,r.argsHash,always,broad) }
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
        allowBot?.let { a -> ShellPermissionDialog(botDisplayName(a,bots),busy=busy,allowed=a.grant.shellMode==ShellMode.BROAD,onClose={allowBot=null},onAllow={
            work {g.setShellPermissionFromOwner(a.grant.agentId,true);allowBot=g.agents.get(a.grant.agentId);note="Always allowed. Saved on this phone until you revoke it."}
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

    @Composable private fun Management() {
        val g=graph; val scope=rememberCoroutineScope()
        val health by g.health.collectAsState(); val shizuku by g.shizuku.status.collectAsState(); val direct by g.privileged.directStatus.collectAsState(); val connection by g.gateway.state.collectAsState()
        val controls by g.settings.controls.collectAsState()
        var tab by remember { mutableStateOf(if(intent.getStringExtra("requestId")!=null) "Approvals" else "Dashboard") }
        var refresh by remember { mutableIntStateOf(0) }; var message by remember { mutableStateOf("") }
        var requests by remember { mutableStateOf(emptyList<RequestRecord>()) }
        var agents by remember { mutableStateOf(emptyList<AgentRecord>()) }
        var events by remember { mutableStateOf(emptyList<Event>()) }
        var skills by remember { mutableStateOf(emptyList<SkillDefinition>()) }
        var automations by remember { mutableStateOf(emptyList<Automation>()) }
        var capabilities by remember { mutableStateOf(emptyList<CapabilityStatus>()) }
        var skillJson by remember { mutableStateOf("") }
        var url by remember { mutableStateOf(g.settings.gateway.value.baseUrl) }
        var gatewaySecret by remember { mutableStateOf("") }
        var latitude by remember { mutableStateOf(g.settings.weather.latitude?.toString() ?: "") }
        var longitude by remember { mutableStateOf(g.settings.weather.longitude?.toString() ?: "") }
        var approve by remember { mutableStateOf<RequestRecord?>(null) }
        fun action(block: suspend ()->Unit) { scope.launch { try { withContext(Dispatchers.IO) { g.ready.await(); block() }; refresh++ } catch(e: Exception) { message=e.message ?: e.javaClass.simpleName } } }
        LaunchedEffect(refresh,tab,health,shizuku) {
            withContext(Dispatchers.IO) {
                runCatching { g.ready.await(); requests=g.requests.recent(100); agents=g.agents.all(); events=g.events.query(limit=50)
                    skills=g.skills.all(); automations=g.scheduler.all(); capabilities=g.registry.statuses() }
            }
        }
        LaunchedEffect(Unit) { g.engine.recordUpdates.collect { record -> refresh++;if(approve?.requestId==record.requestId) approve=record } }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("SIConnect",style=MaterialTheme.typography.headlineMedium)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("Dashboard","Capabilities","Agents","Approvals","Automations","Skills","Diagnostics","Settings").forEach { t -> TextButton(onClick={tab=t}) { Text(t) } }
            }
            if(message.isNotBlank()) Text(message,Modifier.padding(vertical=8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                when(tab) {
                    "Dashboard" -> {
                        Text(health); Text("Device: ${g.settings.deviceId} · ${android.os.Build.MODEL}")
                        Text("Privilege (${g.settings.privilegeBackend}): ${if(g.settings.privilegeBackend=="direct") direct else shizuku}"); Text("Gateway: $connection")
                        Text("Agents: ${agents.count { !it.grant.revoked }} · Pending: ${requests.count { it.state==RequestState.AWAITING_APPROVAL }}")
                        Row { Text("Remote execution",Modifier.weight(1f)); Switch(controls.remoteExecutionEnabled,{ g.settings.setControls(controls.copy(remoteExecutionEnabled=it)) }) }
                        Text("Turn remote execution off for emergency stop. In-flight mutations may already have occurred.")
                        Row { Text("Privileged execution",Modifier.weight(1f)); Switch(controls.privilegedEnabled,{ g.settings.setControls(controls.copy(privilegedEnabled=it)) }) }
                        Button(onClick={ action { val r=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="device.status"),Origin.LOCAL_OWNER); message=r.result?.output.toString() } }) { Text("Read live device status") }
                        requests.firstOrNull()?.let { Text("Last: ${it.capabilityId} · ${it.state}") }
                    }
                    "Capabilities" -> capabilities.forEach { cap ->
                        Text(cap.descriptor.id,style=MaterialTheme.typography.titleMedium)
                        Text("${cap.availability} · ${cap.descriptor.backend} · ${cap.descriptor.interaction}")
                        Text(cap.detail ?: cap.descriptor.description)
                        if(cap.descriptor.permissions.isNotEmpty()) Text(cap.descriptor.permissions.joinToString())
                        HorizontalDivider()
                    }
                    "Agents" -> {
                        Text("Pairing defaults to least privilege. Development pairing explicitly adds shell scope; each arbitrary command still requires owner approval.")
                        if(BuildConfig.DEBUG) Button(onClick={ action { message="Development credential created; available to this APK's owner via run-as in no_backup/development-client.json"; g.createDevelopmentAgent() } }) { Text("Create / rotate development client") }
                        agents.forEach { a ->
                            Text("${a.grant.label} · ${if(a.grant.revoked) "REVOKED" else "active"}")
                            Text("Scopes: ${a.grant.scopes.joinToString()}\nShell: ${a.grant.shellMode}\nExpires: ${a.grant.expiresAtMs ?: "no expiry"}")
                            if(!a.grant.agentId.startsWith("local-")) Button(onClick={ action { g.revokeAgentFromOwner(a.grant.agentId) } },enabled=!a.grant.revoked) { Text("Revoke ${a.grant.label}") }
                        }
                    }
                    "Approvals" -> {
                        val pending=requests.filter { it.state==RequestState.AWAITING_APPROVAL }
                        if(pending.isEmpty()) Text("No pending approvals")
                        pending.forEach { r ->
                            Text("${r.agentId} · ${r.capabilityId}",style=MaterialTheme.typography.titleMedium)
                            Text("${r.assessment?.risk} · target ${r.assessment?.target ?: g.settings.deviceId}")
                            Text(r.assessment?.shellCommand ?: r.arguments.toString())
                            Text("Consequences: ${r.assessment?.sideEffects?.joinToString()}\nExpires: ${java.time.Instant.ofEpochMilli(r.approvalExpiresAtMs ?: r.expiresAtMs)}")
                            Row { Button(onClick={approve=r}) { Text("Approve once") }; TextButton(onClick={ action { g.engine.resolveApproval(r.requestId,false,"local-owner",r.argsHash) } }) { Text("Deny") } }
                            HorizontalDivider()
                        }
                    }
                    "Automations" -> {
                        Text("Deferrable jobs use WorkManager; exact jobs require special alarm access. Missed intervals coalesce. Time zone is recorded per task.")
                        Button(onClick={ action { val now=System.currentTimeMillis(); g.scheduler.put(Automation("diagnostics-$now","diagnostics.snapshot",now+60_000)); message="Native diagnostics scheduled in one minute (deferrable)" } }) { Text("Schedule diagnostics in one minute") }
                        automations.forEach { a -> Text("${a.id}\nNext: ${java.time.Instant.ofEpochMilli(a.nextAtMs)} · ${a.zoneId}\nLast: ${a.lastState ?: "never"} ${a.error ?: ""}")
                            Row { Text("Enabled",Modifier.weight(1f)); Switch(a.enabled,{ enabled -> action { g.scheduler.setEnabled(a.id,enabled) } }) }
                        }
                    }
                    "Skills" -> {
                        OutlinedTextField(skillJson,{skillJson=it},label={Text("Install JSON skill")},modifier=Modifier.fillMaxWidth(),maxLines=8)
                        Button(onClick={ action { g.skills.install(SkillJson.parse(skillJson)); message="Skill validated and installed" } },enabled=skillJson.isNotBlank()) { Text("Validate and install") }
                        skills.forEach { def ->
                            Text("${def.id} v${def.version} · ${def.steps.size} steps")
                            Row { Button(onClick={ action { val id="skill:${java.util.UUID.randomUUID()}"; val r=g.runner.start(def,JsonObject(emptyMap()),ExecutionContext(id,id,"local-owner",g.settings.deviceId),Origin.LOCAL_OWNER); message="${r.runId}: ${r.state}" } }) { Text("Run") }
                                TextButton(onClick={ action { g.skills.enable(def.id,false) } }) { Text("Disable") }
                                TextButton(onClick={ action { g.skills.remove(def.id) } }) { Text("Remove") } }
                        }
                    }
                    "Diagnostics" -> {
                        Button(onClick={ action {
                            val file=java.io.File(noBackupFilesDir,"diagnostics.jsonl")
                            file.writeText(g.events.query(limit=5000).joinToString("\n") { e -> AppJson.encodeToString(Event.serializer(),e.copy(agentId=null,deviceId=null,detail=null)) })
                            message="Redacted export saved privately to no_backup/diagnostics.jsonl; retrieve using run-as in debug builds"
                        } }) { Text("Export redacted JSONL") }
                        Text(g.gateway.detail.collectAsState().value)
                        events.forEach { e -> Text("${e.eventType} · ${e.capabilityId ?: "connection"} · ${e.executionStatus ?: ""}\n${e.errorCode ?: ""} ${e.requestId ?: ""}") }
                    }
                    "Settings" -> {
                        Button(onClick={ if(android.os.Build.VERSION.SDK_INT >= 33) permission.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE,packageName)) }) { Text("Allow approval and reminder notifications") }
                        Button(onClick={ startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Notification listener access") }
                        Button(onClick={ g.shizuku.requestPermission() }) { Text("Authorize / reconnect Shizuku") }
                        Text("Selected authority: ${g.settings.privilegeBackend}")
                        Text(direct)
                        Button(onClick={ action { g.settings.privilegeBackend="direct"; g.privileged.refresh() } }) { Text("Use built-in service / check connection") }
                        Button(onClick={ action { g.settings.privilegeBackend="shizuku"; g.privileged.refresh() } }) { Text("Select optional Shizuku backend") }
                        Text("Built-in service needs an authorized setup start after reboot. Native features work immediately. SIConnect never switches backends automatically.")
                        Text(shizuku)
                        Button(onClick={ permission.launch(arrayOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)) }) { Text("Calendar access (calendar tools not yet implemented)") }
                        Button(onClick={ startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName"))) }) { Text("Exact alarm special access") }
                        Button(onClick={ startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Background battery settings") }
                        Text("For Samsung restricted settings, open SIConnect's app-info menu and allow restricted settings if Android blocks listener access.")
                        OutlinedTextField(url,{url=it},label={Text("Gateway HTTPS URL")},modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(gatewaySecret,{gatewaySecret=it},label={Text("Device credential (stored with Keystore encryption)")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
                        Button(onClick={ action {
                            require(url.startsWith("https://") || (BuildConfig.DEBUG && url.startsWith("http://127.0.0.1:"))) { "HTTPS required" }
                            if(gatewaySecret.isNotBlank()) g.settings.setDeviceSecret(gatewaySecret)
                            g.settings.setGateway(GatewayConfig(url,true))
                            withContext(Dispatchers.Main) { startForegroundService(Intent(this@MainActivity,ConnectionService::class.java)) }
                            message="Connection enabled"
                        } },enabled=url.isNotBlank()) { Text("Save and connect") }
                        Button(onClick={ g.settings.setGateway(g.settings.gateway.value.copy(enabled=false)); stopService(Intent(this@MainActivity,ConnectionService::class.java)) }) { Text("Disconnect gateway") }
                        OutlinedTextField(latitude,{latitude=it},label={Text("Weather latitude")})
                        OutlinedTextField(longitude,{longitude=it},label={Text("Weather longitude")})
                        Button(onClick={ action { val lat=latitude.toDouble(); val lon=longitude.toDouble(); require(lat in -90.0..90.0 && lon in -180.0..180.0); g.settings.weather=g.settings.weather.copy(latitude=lat,longitude=lon); message="Location configured" } }) { Text("Save weather location") }
                        Text("Weather data: Open-Meteo, CC BY 4.0. Free endpoint is for personal/non-commercial use.")
                    }
                }
            }
            TextButton(onClick={refresh++}) { Text("Refresh runtime state") }
        }
        approve?.let { r -> OwnerApprovalDialog(r,error=message,onClose={approve=null},onDeny={action {approve=g.engine.resolveApproval(r.requestId,false,"local-owner",r.argsHash)}},onApprove={always,broad ->
            action { approve=g.approveFromOwner(r.requestId,r.argsHash,always,broad) }
        }) }
    }
}

/** Shared owner review: one path for Home and detailed management. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun OwnerApprovalDialog(r: RequestRecord,busy:Boolean=false,error:String="",onClose:()->Unit,onDeny:()->Unit,onApprove:(Boolean,Boolean)->Unit) {
    var broad by remember(r.requestId) { mutableStateOf(false) }
    val critical=r.assessment?.risk==RiskClass.CRITICAL
    val pending=r.state==RequestState.AWAITING_APPROVAL
    val expired=System.currentTimeMillis()>=(r.approvalExpiresAtMs ?: r.expiresAtMs)
    val canDecide=pending && !busy && !expired
    BasicAlertDialog(onDismissRequest={},properties=androidx.compose.ui.window.DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false)) {
        Surface(shape=RoundedCornerShape(28.dp),tonalElevation=6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*0.85f).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(!pending) "Action ${r.state.name.lowercase().replace('_',' ')}" else if(broad) "Always allow shell commands?" else "Allow this action?",style=MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(r.agentId)
                    Text("Review stays open until you close it.",style=MaterialTheme.typography.bodySmall)
                    if(pending) Text("Expires at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(r.approvalExpiresAtMs ?: r.expiresAtMs))}")
                    if(broad && pending) Text("This bot may run any command using this phone's privileged shell without asking again. This can change apps, settings and data. Critical operations still require approval. Manage this permission under Your bots.")
                    else {
                        Text(r.assessment?.shellCommand ?: r.arguments.toString())
                        Text("Risk: ${r.assessment?.risk}\n${r.assessment?.sideEffects?.joinToString() ?: ""}")
                        if(pending) Text("Always allow this action saves only these exact arguments and this backend. Other actions still follow your policy.",style=MaterialTheme.typography.bodySmall)
                    }
                    if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Saving your decision and checking current permissions…")}
                    if(expired && pending) Text("This request expired. Ask the bot to submit a new request.",color=MaterialTheme.colorScheme.error)
                    r.errorMessage?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
                }
                Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(canDecide) {
                        if(broad) Button(onClick={onApprove(false,true)},modifier=Modifier.fillMaxWidth()) {Text("Always allow shell commands")}
                        else {
                            Button(onClick={onApprove(false,false)},modifier=Modifier.fillMaxWidth()) {Text("Allow once")}
                            if(!critical) OutlinedButton(onClick={onApprove(true,false)},modifier=Modifier.fillMaxWidth()) {Text("Always allow this action")}
                            if(!critical && r.assessment?.shellCommand!=null) TextButton(onClick={broad=true}) {Text("Allow all shell commands…")}
                        }
                        TextButton(onClick=onDeny) {Text("Deny",color=MaterialTheme.colorScheme.error)}
                    }
                    TextButton(onClick={if(broad && pending) broad=false else onClose()},enabled=!busy) {Text(if(broad && pending) "Back" else "Close")}
                }
            }
        }
    }
}

@Composable private fun ShellPermissionDialog(label: String,busy:Boolean=false,allowed:Boolean=false,onClose:()->Unit,onAllow:()->Unit) {
    AlertDialog(onDismissRequest={},properties=androidx.compose.ui.window.DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false),title={Text("Always allow device control?")},
        text={Text("$label will be allowed to run arbitrary shell commands and read its request results without asking each time. This can change apps, settings and data. Critical operations still require approval.\n\nThis choice is saved on your phone across app restarts. Emergency stop and agent revocation still apply. You can switch back to asking each time under Your bots.")},
        confirmButton={if(allowed) Button(onClick=onClose) {Text("Saved · Close")} else Button(onClick=onAllow,enabled=!busy) {Text(if(busy) "Saving…" else "Always allow")}},dismissButton={if(!allowed) TextButton(onClick=onClose,enabled=!busy) {Text("Back")}})
}

/** A display name never selects an authenticated identity. Distinguish existing same-name clients. */
private fun botDisplayName(bot:AgentRecord,bots:List<AgentRecord>):String {
    val label=if(bot.grant.label=="ADB development client") "Personal bot" else bot.grant.label
    return if(bots.count { it.grant.label==bot.grant.label }>1) "$label · ${bot.grant.agentId.takeLast(6)}" else label
}
