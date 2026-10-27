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

@Composable private fun SettingsAccessItem(title:String,status:String,action:String,onAction:()->Unit,explanation:String?=null) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title,style=MaterialTheme.typography.titleMedium)
                    Text(status,style=MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick=onAction) {Text(action)}
            }
            explanation?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        }
    }
}

@Composable internal fun MainActivity.Management(initialTab:String="Settings",onSetup:()->Unit,onGateway:()->Unit,onAdvancedControl:()->Unit) {
        val g=graph; val scope=rememberCoroutineScope()
        val health by g.health.collectAsState(); val shizuku by g.shizuku.status.collectAsState(); val direct by g.privileged.directStatus.collectAsState(); val connection by g.gateway.state.collectAsState()
        val controls by g.settings.controls.collectAsState()
        val connectionDetail by g.gateway.detail.collectAsState()
        val semanticConnected by dev.siconnect.app.accessibility.SemanticAccessibilityService.connectionState.collectAsState()
        val listenerConnected by dev.siconnect.app.notifications.NotificationCaptureService.connectionState.collectAsState()
        val accessRevision=permissionRevision
        val notificationsEnabled=remember(accessRevision) {getSystemService(NotificationManager::class.java).areNotificationsEnabled()}
        val calendarGranted=remember(accessRevision) {checkSelfPermission(Manifest.permission.READ_CALENDAR)==android.content.pm.PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.WRITE_CALENDAR)==android.content.pm.PackageManager.PERMISSION_GRANTED}
        val listenerComponent=remember {ComponentName(this,dev.siconnect.app.notifications.NotificationCaptureService::class.java)}
        val listenerGranted=remember(accessRevision) {getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listenerComponent)}
        val semanticGranted=remember(accessRevision) {AndroidSettings.Secure.getString(contentResolver,AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any {ComponentName.unflattenFromString(it)==ComponentName(this,dev.siconnect.app.accessibility.SemanticAccessibilityService::class.java)}}
        var showTools by rememberSaveable {mutableStateOf(false)}
        var showConnectionDetails by rememberSaveable {mutableStateOf(false)}
        var tab by rememberSaveable { mutableStateOf(initialTab) }
        var refresh by remember { mutableIntStateOf(0) }; var message by remember { mutableStateOf("") }
        var events by remember { mutableStateOf(emptyList<Event>()) }
        var skills by remember { mutableStateOf(emptyList<SkillDefinition>()) }
        var roots by remember { mutableStateOf(emptyList<DocumentRoot>()) }
        var capabilities by remember { mutableStateOf(emptyList<CapabilityStatus>()) }
        var skillJson by remember { mutableStateOf("") }
        var url by remember { mutableStateOf(g.settings.gateway.value.baseUrl) }
        var gatewaySecret by remember { mutableStateOf("") }
        var approve by remember { mutableStateOf<RequestRecord?>(null) }
        fun action(block: suspend ()->Unit) { scope.launch { try { withContext(Dispatchers.IO) { g.ready.await(); block() }; refresh++ } catch(e: CancellationException) {throw e} catch(e: Exception) { message=e.message ?: e.javaClass.simpleName } } }
        LaunchedEffect(refresh,tab,health,shizuku,permissionRevision) {
            withContext(Dispatchers.IO) {
                try {
                    g.ready.await()
                    when(tab) {
                        "Settings"->roots=DocumentRoots(this@Management,g.db).all()
                        "Capabilities"->capabilities=g.registry.statuses()
                        "Skills"->skills=g.skills.all(includeDisabled=true)
                        "Diagnostics"->events=g.events.query(limit=50)
                    }
                } catch(e:CancellationException) {throw e} catch(e:Exception) {message="Could not refresh settings: ${e.javaClass.simpleName}"}
            }
        }
        LaunchedEffect(tab) { if(tab=="Diagnostics") g.engine.recordUpdates.collect { refresh++ } }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(if(tab=="Settings") "Settings" else tab,style=MaterialTheme.typography.headlineMedium)
            if(showTools) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("Settings","Capabilities","Skills","Diagnostics").forEach {t->FilterChip(selected=tab==t,onClick={tab=t},label={Text(t)})}
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
                        OutlinedButton(onClick={action {
                            val r=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="device.runtime",arguments=buildJsonObject {put("checkDatabase",true)}),Origin.LOCAL_OWNER)
                            require(r.state==RequestState.SUCCEEDED) {r.errorMessage ?: "Database check could not complete"}
                            message="Live database integrity: "+r.result!!.output.jsonObject.getValue("databaseIntegrity").jsonPrimitive.content
                        }}) {Text("Check database health")}
                        Button(onClick={ action {
                            val file=java.io.File(noBackupFilesDir,"diagnostics.jsonl")
                            file.writeText(g.events.query(limit=5000).joinToString("\n") { e -> AppJson.encodeToString(Event.serializer(),e.copy(agentId=null,deviceId=null,detail=null)) })
                            message="Redacted export saved privately to no_backup/diagnostics.jsonl; retrieve using run-as in debug builds"
                        } }) { Text("Export redacted JSONL") }
                        Text(g.gateway.detail.collectAsState().value)
                        events.forEach { e -> Text("${e.eventType} · ${e.capabilityId ?: "connection"} · ${e.executionStatus ?: ""}\n${e.errorCode ?: ""} ${e.requestId ?: ""}") }
                    }
                    "Settings" -> {
                        SettingsAccessItem("Gateway",if(connection==dev.siconnect.app.transport.ConnectionState.ONLINE) "Connected" else connectionDetail,"Manage",onGateway)
                        SettingsAccessItem("Advanced device control",if(g.privileged.available()) "Available · ${if(g.settings.privilegeBackend=="direct") "bundled service" else "Shizuku"}" else "Optional · setup or recovery needed","Open",onAdvancedControl)
                        TextButton(onClick=onSetup) {Text("Guided setup and verification")}
                        Text("Android access",style=MaterialTheme.typography.titleLarge)
                        Text("Access belongs to this phone. Agent permissions are managed separately in Agents.",style=MaterialTheme.typography.bodySmall)
                        if(permissionStatus.isNotBlank()) Text(permissionStatus)
                        SettingsAccessItem("Approval and reminder alerts",if(notificationsEnabled) "Allowed" else "Off",if(notificationsEnabled) "Manage" else "Enable",{
                            if(!notificationsEnabled && android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                                requestNativeAccess(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                            else startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE,packageName))
                        })
                        SettingsAccessItem("Screen control",if(semanticConnected) "Connected" else if(semanticGranted) "Enabled · service disconnected" else "Not enabled",if(semanticGranted) "Manage" else "Enable",{
                            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                        },if(semanticGranted && !semanticConnected) "Android has enabled access but disconnected the service. Turn SIConnect off and on in Accessibility settings." else null)
                        SettingsAccessItem("Notification actions",if(listenerConnected) "Connected" else if(listenerGranted) "Allowed · service disconnected" else "Not enabled",if(listenerGranted && !listenerConnected) "Reconnect" else if(listenerGranted) "Manage" else "Enable",{
                            if(listenerGranted && !listenerConnected) {
                                android.service.notification.NotificationListenerService.requestRebind(listenerComponent)
                                message="Reconnection requested. Status changes only when Android connects the notification listener."
                            } else startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        })
                        if(listenerGranted && !listenerConnected) TextButton(onClick={startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}) {Text("Review notification access in Android")}
                        SettingsAccessItem("Calendar",if(calendarGranted) "Read and write allowed" else "Access needed",if(calendarGranted) "Manage" else "Enable",{
                            if(calendarGranted) startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
                            else requestNativeAccess(arrayOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR))
                        })
                        Text("Connected folders",style=MaterialTheme.typography.titleMedium)
                        Text(if(roots.isEmpty()) "No folders selected. Android lets you choose shared folders; protected app storage is excluded." else "${roots.size} selected. Android access and each agent’s document grant are required.",style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick={ownerAuthentication.authenticate("Connect document folder",{documentPicker.launch(null)},{documentStatus=it})}) {Text("Choose folder")}
                        if(documentStatus.isNotBlank()) Text(documentStatus)
                        roots.forEach {root->SettingsAccessItem(root.label,"Selected folder","Disconnect",{action {DocumentRoots(this@Management,g.db).remove(root.id);g.gateway.refreshCatalog();documentStatus="";message="Folder access removed"}})}
                        val exact=g.reminders.canScheduleExact()
                        SettingsAccessItem("Precise reminders",if(exact) "Allowed" else "Flexible timing available","Manage",{startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName")))})
                        val batteryExempt=getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                        SettingsAccessItem("Background operation",if(batteryExempt) "Battery exemption allowed" else "Android battery optimization applies","Manage",{startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))})
                        Text("Appearance",style=MaterialTheme.typography.titleMedium)
                        val appearance by g.settings.appearance.collectAsState()
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("SYSTEM" to "System","LIGHT" to "Light","DARK" to "Dark").forEach {(value,label)->FilterChip(selected=appearance==value,onClick={g.settings.setAppearance(value)},label={Text(label)})}
                        }
                        CommuteSettings()
                        TextButton(onClick={showConnectionDetails=!showConnectionDetails}) {Text(if(showConnectionDetails) "Hide advanced connection settings" else "Advanced connection settings")}
                        if(showConnectionDetails) {
                            Row(verticalAlignment=Alignment.CenterVertically) {Text("Route privileged requests",Modifier.weight(1f));Switch(controls.privilegedEnabled,{enabled->
                                if(enabled) ownerAuthentication.authenticate("Enable privileged execution",{g.settings.setControls(g.settings.controls.value.copy(privilegedEnabled=true))},{message=it})
                                else g.settings.setControls(g.settings.controls.value.copy(privilegedEnabled=false))
                            })}
                            Text("Selected backend: ${if(g.settings.privilegeBackend=="direct") "Bundled service" else "Shizuku"}. Selection never changes automatically.")
                            if(g.settings.privilegeBackend!="direct") OutlinedButton(onClick={ownerAuthentication.authenticate("Select bundled authority",{action {g.settings.privilegeBackend="direct";g.privileged.refresh()}},{message=it})}) {Text("Use bundled service")}
                            if(g.settings.privilegeBackend!="shizuku") OutlinedButton(onClick={ownerAuthentication.authenticate("Select Shizuku authority",{action {g.settings.privilegeBackend="shizuku";g.privileged.refresh()}},{message=it})}) {Text("Use optional Shizuku")}
                            if(g.settings.privilegeBackend=="shizuku") OutlinedButton(onClick={g.shizuku.requestPermission()}) {Text("Authorize Shizuku")}
                            Text(if(g.settings.privilegeBackend=="direct") direct else shizuku,style=MaterialTheme.typography.bodySmall)
                            OutlinedTextField(url,{url=it},label={Text("Gateway HTTPS URL")},modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(gatewaySecret,{gatewaySecret=it},label={Text("Existing device credential · optional")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                            OutlinedButton(onClick={
                                val selectedUrl=url;val selectedSecret=gatewaySecret
                                ownerAuthentication.authenticate("Change gateway connection",{action {
                                    require(selectedUrl.startsWith("https://") || (BuildConfig.DEBUG && selectedUrl.startsWith("http://127.0.0.1:"))) {"HTTPS required"}
                                    g.gateway.stop()
                                    if(selectedSecret.isNotBlank()) g.settings.setDeviceSecret(selectedSecret)
                                    g.settings.setGateway(GatewayConfig(selectedUrl,true));gatewaySecret=""
                                    withContext(Dispatchers.Main) {startForegroundService(Intent(this@Management,ConnectionService::class.java))}
                                    message="Connecting with saved configuration"
                                }},{message=it})
                            },enabled=url.isNotBlank()) {Text("Save connection settings")}
                            TextButton(onClick={g.settings.setGateway(g.settings.gateway.value.copy(enabled=false));stopService(Intent(this@Management,ConnectionService::class.java))}) {Text("Disconnect gateway")}
                            if(BuildConfig.DEBUG) TextButton(onClick={ownerAuthentication.authenticate("Rotate development credential",{action {g.createDevelopmentAgent();message="Development credential rotated"}},{message=it})}) {Text("Rotate development credential")}
                        }
                        TextButton(onClick={showTools=!showTools}) {Text(if(showTools) "Hide advanced tools" else "Advanced tools")}
                        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",style=MaterialTheme.typography.bodySmall)

                    }
                }
            }
            if(tab!="Settings") TextButton(onClick={refresh++}) { Text("Refresh") }
        }
        approve?.let { r -> OwnerApprovalDialog(r,error=message,onClose={approve=null},onDeny={action {approve=g.engine.resolveApproval(r.requestId,false,"local-owner",r.argsHash)}},onApprove={always,broad ->
            ownerAuthentication.authenticate("Approve ${r.capabilityId}",{
                if(approve?.requestId==r.requestId) action {approve=g.approveFromOwner(r.requestId,r.argsHash,always,broad)}
            },{message=it})
        }) }
    }
