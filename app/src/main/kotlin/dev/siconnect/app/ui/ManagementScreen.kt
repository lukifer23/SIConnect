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

@Composable internal fun MainActivity.Management(initialTab:String="Settings") {
        val g=graph; val scope=rememberCoroutineScope()
        val health by g.health.collectAsState(); val shizuku by g.shizuku.status.collectAsState(); val direct by g.privileged.directStatus.collectAsState(); val connection by g.gateway.state.collectAsState()
        val controls by g.settings.controls.collectAsState()
        var tab by rememberSaveable { mutableStateOf(initialTab) }
        var refresh by remember { mutableIntStateOf(0) }; var message by remember { mutableStateOf("") }
                var agents by remember { mutableStateOf(emptyList<AgentRecord>()) }
        var events by remember { mutableStateOf(emptyList<Event>()) }
        var skills by remember { mutableStateOf(emptyList<SkillDefinition>()) }
        var automations by remember { mutableStateOf(emptyList<Automation>()) }
        var roots by remember { mutableStateOf(emptyList<DocumentRoot>()) }
        var wakeTime by rememberSaveable {mutableStateOf("")}
        var departureTime by rememberSaveable {mutableStateOf("")}
        var eveningTime by rememberSaveable {mutableStateOf("20:00")}
        var weatherZone by rememberSaveable {mutableStateOf(java.time.ZoneId.systemDefault().id)}
        var capabilities by remember { mutableStateOf(emptyList<CapabilityStatus>()) }
        var skillJson by remember { mutableStateOf("") }
        var automationJson by rememberSaveable { mutableStateOf("") }
        var url by remember { mutableStateOf(g.settings.gateway.value.baseUrl) }
        var gatewaySecret by remember { mutableStateOf("") }
        var latitude by remember { mutableStateOf(g.settings.weather.latitude?.toString() ?: "") }
        var longitude by remember { mutableStateOf(g.settings.weather.longitude?.toString() ?: "") }
        var approve by remember { mutableStateOf<RequestRecord?>(null) }
        fun action(block: suspend ()->Unit) { scope.launch { try { withContext(Dispatchers.IO) { g.ready.await(); block() }; refresh++ } catch(e: CancellationException) {throw e} catch(e: Exception) { message=e.message ?: e.javaClass.simpleName } } }
        LaunchedEffect(refresh,tab,health,shizuku,permissionRevision) {
            withContext(Dispatchers.IO) {
                runCatching { g.ready.await(); agents=g.agents.all(); events=g.events.query(limit=50)
                    skills=g.skills.all(includeDisabled=true); automations=g.scheduler.all(); capabilities=g.registry.statuses();roots=DocumentRoots(this@Management,g.db).all() }
            }
        }
        LaunchedEffect(Unit) { g.engine.recordUpdates.collect { record -> refresh++;if(approve?.requestId==record.requestId) approve=record } }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("SIConnect",style=MaterialTheme.typography.headlineMedium)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("Settings","Capabilities","Skills","Diagnostics","Automations").forEach { t -> TextButton(onClick={tab=t}) { Text(t) } }
            }
            if(message.isNotBlank()) Text(message,Modifier.padding(vertical=8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                when(tab) {
                    "Capabilities" -> capabilities.forEach { cap ->
                        Text(cap.descriptor.id,style=MaterialTheme.typography.titleMedium)
                        Text("${cap.availability} · ${cap.descriptor.backend} · ${cap.descriptor.interaction}")
                        Text(cap.detail ?: cap.descriptor.description)
                        if(cap.descriptor.permissions.isNotEmpty()) Text(cap.descriptor.permissions.joinToString())
                        HorizontalDivider()
                    }
                    "Automations" -> {
                        Text("Schedules wait for their durable skill to finish. Approval waits stay visible. Wall-clock recurrence uses the task’s explicit timezone; deferrable delivery may be delayed by Android.")
                        OutlinedTextField(automationJson,{automationJson=it},label={Text("Automation JSON")},modifier=Modifier.fillMaxWidth(),maxLines=8)
                        Button(onClick={val definition=automationJson;ownerAuthentication.authenticate("Save automation",{action {val saved=g.scheduler.put(AppJson.decodeFromString(Automation.serializer(),definition));message="${saved.id}: ${saved.scheduleState}"}},{message=it})},enabled=automationJson.isNotBlank()) {Text("Validate and save schedule")}
                        Text("Evening commute weather",style=MaterialTheme.typography.titleMedium)
                        Text("Uses your configured weather location. Checks tomorrow’s forecast, warns about winter/cold weather and schedules rain reminders. Your Clock alarms are never changed.")
                        OutlinedTextField(wakeTime,{wakeTime=it},label={Text("Fallback wake time HH:mm")})
                        OutlinedTextField(departureTime,{departureTime=it},label={Text("Departure HH:mm")})
                        OutlinedTextField(eveningTime,{eveningTime=it},label={Text("Evening check HH:mm")})
                        OutlinedTextField(weatherZone,{weatherZone=it},label={Text("Timezone")})
                        Button(onClick={
                            val wake=wakeTime;val depart=departureTime;val evening=eveningTime;val zone=weatherZone
                            ownerAuthentication.authenticate("Enable commute weather",{action {
                                require(g.settings.weather.latitude!=null && g.settings.weather.longitude!=null) {"Configure your weather location in Settings first"}
                                val input=buildJsonObject {put("timezone",zone);put("wakeTime",wake);put("departureTime",depart)}
                                dev.siconnect.app.capabilities.WeatherCommute.plan(input,System.currentTimeMillis(),null,g.settings.weather.latitude,g.settings.weather.longitude)
                                val rule=dev.siconnect.app.automation.Recurrence(dev.siconnect.app.automation.RecurrenceKind.DAILY,evening)
                                val now=System.currentTimeMillis();val next=requireNotNull(rule.nextAfter(now,now,zone))
                                g.enableWeatherAutomationFromOwner()
                                val old=g.scheduler.get("weather-commute")
                                g.scheduler.put(Automation("weather-commute","weather.commute_check",next,zoneId=zone,recurrence=rule,inputs=input,revision=old?.revision ?: 0))
                                message="Evening commute check saved. Morning rain reminders are deferrable; Android may delay them."
                            }},{message=it})
                        },enabled=wakeTime.isNotBlank() && departureTime.isNotBlank()) {Text("Enable daily weather checks")}
                        HorizontalDivider()
                        Button(onClick={ action { val now=System.currentTimeMillis(); g.scheduler.put(Automation("diagnostics-$now","diagnostics.snapshot",now+60_000)); message="Native diagnostics scheduled in one minute (deferrable)" } }) { Text("Schedule diagnostics in one minute") }
                        automations.forEach { a -> Text("${a.id}\nNext: ${java.time.Instant.ofEpochMilli(a.nextAtMs)} · ${a.zoneId}\nStatus: ${a.scheduleState} · Last: ${a.lastState ?: "never"} ${a.error ?: ""}")
                            Row { Text("Enabled",Modifier.weight(1f)); Switch(a.enabled,{ enabled -> if(enabled) ownerAuthentication.authenticate("Enable automation",{action {g.scheduler.setEnabled(a.id,true)}},{message=it}) else action {g.scheduler.setEnabled(a.id,false)} }) }
                            TextButton(onClick={automationJson=AppJson.encodeToString(Automation.serializer(),a)}) {Text("Edit schedule")}
                            if(a.lastRunId!=null && a.lastState in listOf("READY","RUNNING","AWAITING_APPROVAL")) TextButton(onClick={action {g.scheduler.cancelRun(a.lastRunId)}}) {Text("Cancel active run")}
                        }
                    }
                    "Skills" -> {
                        OutlinedTextField(skillJson,{skillJson=it},label={Text("Install JSON skill")},modifier=Modifier.fillMaxWidth(),maxLines=8)
                        Button(onClick={val definition=skillJson;ownerAuthentication.authenticate("Install workflow",{action {g.skills.install(SkillJson.parse(definition));message="Skill validated and installed"}},{message=it})},enabled=skillJson.isNotBlank()) { Text("Validate and install") }
                        skills.forEach { def ->
                            Text("${def.id} v${def.version} · ${def.steps.size} steps")
                            Row { Button(onClick={ownerAuthentication.authenticate("Run owner workflow",{action {val id="skill:${java.util.UUID.randomUUID()}";val r=g.runner.start(def,JsonObject(emptyMap()),ExecutionContext(id,id,"local-owner",g.settings.deviceId),Origin.LOCAL_OWNER);message="${r.runId}: ${r.state}"}},{message=it})}) { Text("Run") }
                                val enabled=g.skills.isEnabled(def.id)
                                TextButton(onClick={if(enabled) action {g.skills.enable(def.id,false)} else ownerAuthentication.authenticate("Enable skill",{action {g.skills.enable(def.id,true)}},{message=it})}) {Text(if(enabled) "Disable" else "Enable")}
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
                        Row {Text("Privileged execution",Modifier.weight(1f));Switch(controls.privilegedEnabled,{enabled ->
                            if(enabled) ownerAuthentication.authenticate("Enable privileged execution",{g.settings.setControls(g.settings.controls.value.copy(privilegedEnabled=true))},{message=it})
                            else g.settings.setControls(g.settings.controls.value.copy(privilegedEnabled=false))
                        })}
                        Button(onClick={startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))}) {Text("Semantic screen access")}
                        Text("Enable SIConnect in Android Accessibility settings. Samsung may require Allow restricted settings in app info first. Protected owner and lock surfaces remain unavailable.")
                        if(BuildConfig.DEBUG) TextButton(onClick={ownerAuthentication.authenticate("Rotate development credential",{action {g.createDevelopmentAgent();message="Development credential rotated"}},{message=it})}) {Text("Advanced: rotate development credential")}
                        Text("Appearance",style=MaterialTheme.typography.titleMedium)
                        val appearance by g.settings.appearance.collectAsState()
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("SYSTEM" to "System","LIGHT" to "Light","DARK" to "Dark").forEach {(value,label) ->
                                FilterChip(selected=appearance==value,onClick={g.settings.setAppearance(value)},label={Text(label)})
                            }
                        }
                        Text("Uses Android’s system font and text-size setting.",style=MaterialTheme.typography.bodySmall)
                        Button(onClick={ if(android.os.Build.VERSION.SDK_INT >= 33) permission.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE,packageName)) }) { Text("Allow approval and reminder notifications") }
                        Button(onClick={ startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Notification listener access") }
                        Button(onClick={ g.shizuku.requestPermission() }) { Text("Authorize / reconnect Shizuku") }
                        Text("Selected authority: ${g.settings.privilegeBackend}")
                        Text(direct)
                        Button(onClick={ ownerAuthentication.authenticate("Select built-in authority",{action { g.settings.privilegeBackend="direct"; g.privileged.refresh() }},{message=it}) }) { Text("Use built-in service / check connection") }
                        Button(onClick={ ownerAuthentication.authenticate("Select Shizuku authority",{action { g.settings.privilegeBackend="shizuku"; g.privileged.refresh() }},{message=it}) }) { Text("Select optional Shizuku backend") }
                        Text("Built-in service needs an authorized setup start after reboot. Native features work immediately. SIConnect never switches backends automatically.")
                        Text(shizuku)
                        Button(onClick={ permission.launch(arrayOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)) }) { Text("Calendar access") }
                        Text("Connected folders",style=MaterialTheme.typography.titleMedium)
                        Text("Android grants access only to the folder you select. Bots also need Selected folders permission.")
                        Button(onClick={ownerAuthentication.authenticate("Connect document folder",{documentPicker.launch(null)},{documentStatus=it})}) {Text("Choose a folder")}
                        if(documentStatus.isNotBlank()) Text(documentStatus)
                        roots.forEach {root ->
                            Text(root.label)
                            TextButton(onClick={action {DocumentRoots(this@Management,g.db).remove(root.id);message="Folder access removed"}}) {Text("Disconnect folder")}
                        }
                        Button(onClick={ startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName"))) }) { Text("Exact alarm special access") }
                        Button(onClick={ startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Background battery settings") }
                        Text("For Samsung restricted settings, open SIConnect's app-info menu and allow restricted settings if Android blocks listener access.")
                        OutlinedTextField(url,{url=it},label={Text("Gateway HTTPS URL")},modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(gatewaySecret,{gatewaySecret=it},label={Text("Device credential (stored with Keystore encryption)")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
                        Button(onClick={
                            val selectedUrl=url;val selectedSecret=gatewaySecret
                            ownerAuthentication.authenticate("Change gateway connection",{action {
                                require(selectedUrl.startsWith("https://") || (BuildConfig.DEBUG && selectedUrl.startsWith("http://127.0.0.1:"))) {"HTTPS required"}
                                if(selectedSecret.isNotBlank()) g.settings.setDeviceSecret(selectedSecret)
                                g.settings.setGateway(GatewayConfig(selectedUrl,true))
                                gatewaySecret=""
                                withContext(Dispatchers.Main) {startForegroundService(Intent(this@Management,ConnectionService::class.java))}
                                message="Connection enabled"
                            }},{message=it})
                        },enabled=url.isNotBlank()) {Text("Save and connect") }
                        Button(onClick={ g.settings.setGateway(g.settings.gateway.value.copy(enabled=false)); stopService(Intent(this@Management,ConnectionService::class.java)) }) { Text("Disconnect gateway") }
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
            ownerAuthentication.authenticate("Approve ${r.capabilityId}",{
                if(approve?.requestId==r.requestId) action {approve=g.approveFromOwner(r.requestId,r.argsHash,always,broad)}
            },{message=it})
        }) }
    }
