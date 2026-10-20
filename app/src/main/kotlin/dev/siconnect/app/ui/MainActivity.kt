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
    private val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF8ADCC3),onPrimary=Color(0xFF062B23),background=Color(0xFF0D1218),surface=Color(0xFF151E27),surfaceVariant=Color(0xFF202D39))) {
            Surface(Modifier.fillMaxSize()) { Home() }
        } }
    }

    override fun onStart() {
        super.onStart()
        if(graph.settings.gateway.value.enabled) startForegroundService(Intent(this,ConnectionService::class.java))
    }

    @Composable private fun Home() {
        val g=graph
        val scope=rememberCoroutineScope()
        val config by g.settings.gateway.collectAsState()
        val connection by g.gateway.state.collectAsState()
        val direct by g.privileged.directStatus.collectAsState()
        val controls by g.settings.controls.collectAsState()
        val health by g.health.collectAsState()
        var advanced by remember { mutableStateOf(false) }
        var changed by remember { mutableIntStateOf(0) }
        var pending by remember { mutableStateOf(emptyList<RequestRecord>()) }
        var recent by remember { mutableStateOf(emptyList<RequestRecord>()) }
        var note by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var setup by remember { mutableStateOf(false) }
        var controlHelp by remember { mutableStateOf(false) }
        var connectionCode by remember { mutableStateOf("") }
        var review by remember { mutableStateOf<RequestRecord?>(null) }
        var notifications by remember { mutableStateOf(getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
        fun work(block: suspend ()->Unit) { scope.launch { busy=true; try { withContext(Dispatchers.IO) { g.ready.await(); block() }; changed++ } catch(e: Exception) { note=e.message ?: "Action failed" } finally { busy=false } } }
        LaunchedEffect(changed,health,connection) {
            withContext(Dispatchers.IO) { runCatching { g.ready.await(); recent=g.requests.recent(8); pending=g.requests.listByStates(setOf(RequestState.AWAITING_APPROVAL)) } }
            notifications=getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }
        LaunchedEffect(Unit) { g.engine.recordUpdates.collect { changed++ } }
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
                    Text(if(connection==dev.siconnect.app.transport.ConnectionState.ONLINE) "● CONNECTED" else "● SETUP",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
                }
                Card(shape=RoundedCornerShape(24.dp)) { Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(if(connection==dev.siconnect.app.transport.ConnectionState.ONLINE) "Ready for your bot" else "Connect your phone",style=MaterialTheme.typography.headlineSmall)
                    Text(if(connection==dev.siconnect.app.transport.ConnectionState.ONLINE) "Requests run on this phone. You approve sensitive actions here." else "Enable access, then connect your bot. Every action is checked and logged.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick={setup=true},modifier=Modifier.fillMaxWidth().height(52.dp)) { Text(if(connection==dev.siconnect.app.transport.ConnectionState.ONLINE) "Connection details" else "Connect a bot") }
                    if(config.baseUrl.isNotBlank()) Text(config.baseUrl.trimEnd('/')+"/mcp",style=MaterialTheme.typography.bodySmall)
                } }
                Text("Access",style=MaterialTheme.typography.titleLarge)
                Card(shape=RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Notifications",style=MaterialTheme.typography.titleMedium); Text(if(notifications) "Allowed" else "For approvals and reminders",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick={ if(android.os.Build.VERSION.SDK_INT>=33) permission.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE,packageName)); changed++ }) { Text(if(notifications) "Settings" else "Enable") }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Device control",style=MaterialTheme.typography.titleMedium); Text(if(g.privileged.available()) "Ready · built into SIConnect" else "Native tools ready · full control needs setup",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick={work { g.privileged.refresh(); if(!g.privileged.available()) controlHelp=true }}) { Text(if(g.privileged.available()) "Check" else "Enable") }
                    }
                } }
                if(pending.isNotEmpty()) {
                    Text("Needs your approval",style=MaterialTheme.typography.titleLarge)
                    pending.forEach { r -> Card(shape=RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text(r.agentId,style=MaterialTheme.typography.labelLarge)
                        Text(r.assessment?.shellCommand ?: r.capabilityId,style=MaterialTheme.typography.titleMedium)
                        Text(r.assessment?.sideEffects?.joinToString() ?: "Review the exact action before allowing it")
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Button(onClick={review=r}) { Text("Review") }
                            OutlinedButton(onClick={work { g.engine.resolveApproval(r.requestId,false,"local-owner",r.argsHash) }}) { Text("Deny") }
                        }
                    } } }
                }
                Button(onClick={ work {
                    val r=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="device.status"),Origin.LOCAL_OWNER)
                    check(r.state==RequestState.SUCCEEDED) { r.errorMessage ?: "Device test failed" }
                    note="Device test passed. Read real device status and saved the result."
                } },enabled=!busy,modifier=Modifier.fillMaxWidth().height(52.dp)) { Text(if(busy) "Working…" else "Test this phone") }
                if(note.isNotBlank()) Text(note,color=MaterialTheme.colorScheme.primary)
                if(recent.isNotEmpty()) {
                    Text("Recent activity",style=MaterialTheme.typography.titleLarge)
                    recent.take(4).forEach { r -> Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { Text(r.capabilityId,style=MaterialTheme.typography.bodyMedium); Text(r.agentId,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(when(r.state) {RequestState.SUCCEEDED->"Completed";RequestState.AWAITING_APPROVAL->"Waiting";RequestState.UNKNOWN_OUTCOME->"Check outcome";else->r.state.name.lowercase().replaceFirstChar { it.uppercase() }},style=MaterialTheme.typography.labelMedium)
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
        if(controlHelp) AlertDialog(onDismissRequest={controlHelp=false},title={Text("Enable full device control")},
            text={Text("SIConnect includes its own control service. No companion app is needed.\n\nOn your connected Mac, double-click Start SIConnect. Then tap Check here.\n\nAndroid requires this authorized setup again after a phone restart. Native tools keep working.")},
            confirmButton={TextButton(onClick={controlHelp=false;work { g.privileged.refresh(); note=if(g.privileged.available()) "Device control is ready" else "Setup has not connected yet" }}) {Text("Check connection")}},
            dismissButton={TextButton(onClick={controlHelp=false}) {Text("Later")}})
        if(setup) AlertDialog(onDismissRequest={setup=false},title={Text("Connect your bot")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(connection==dev.siconnect.app.transport.ConnectionState.ONLINE) "Your gateway is connected. Add the MCP address and your agent key to your bot." else "Paste your SIConnect setup code. It connects this phone to your private gateway.")
            OutlinedTextField(connectionCode,{connectionCode=it},label={Text("Setup code")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),maxLines=3)
            if(config.baseUrl.isNotBlank()) Text(config.baseUrl.trimEnd('/')+"/mcp")
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
        review?.let { r -> AlertDialog(onDismissRequest={review=null},title={Text("Allow this action once?")},
            text={Text("${r.agentId}\n\n${r.assessment?.shellCommand ?: r.arguments}\n\n${r.assessment?.sideEffects?.joinToString()}")},
            confirmButton={Button(onClick={review=null;work { val result=g.engine.resolveApproval(r.requestId,true,"local-owner",r.argsHash); note="Action ${result.state.name.lowercase()}" }}) {Text("Allow once")}},
            dismissButton={TextButton(onClick={review=null}) {Text("Back")}}) }
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
        LaunchedEffect(Unit) { g.engine.recordUpdates.collect { refresh++ } }
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
                        Text("${cap.availability} · ${cap.descriptor.backend} · physical verification: see coverage ledger")
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
                            if(!a.grant.agentId.startsWith("local-")) Button(onClick={ action { g.agents.put(a.copy(grant=a.grant.copy(revoked=true))) } },enabled=!a.grant.revoked) { Text("Revoke ${a.grant.label}") }
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
        approve?.let { r -> AlertDialog(onDismissRequest={approve=null},title={Text("Approve exact action once?")},
            text={Text("${r.agentId}\n${r.assessment?.shellCommand ?: r.arguments}\n${r.assessment?.sideEffects?.joinToString()}")},
            confirmButton={Button(onClick={approve=null; action { val result=g.engine.resolveApproval(r.requestId,true,"local-owner",r.argsHash); message="${result.state}: ${result.errorCode ?: "completed"}" } }) { Text("Approve this action") }},
            dismissButton={TextButton(onClick={approve=null}) { Text("Back") }}) }
    }
}
