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
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Management() } } }
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
