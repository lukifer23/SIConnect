package dev.siconnect.app.ui

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.siconnect.app.graph
import dev.siconnect.app.accessibility.SemanticAccessibilityService
import dev.siconnect.app.notifications.NotificationCaptureService
import dev.siconnect.app.data.DocumentRoots
import dev.siconnect.core.*
import kotlinx.coroutines.*

/** Optional access is requested per workflow; native readiness never depends on shell authority. */
@Composable internal fun MainActivity.NativeSetup(onClose:()->Unit,onGateway:()->Unit,onAgents:()->Unit,onAdvanced:()->Unit) {
    var stage by rememberSaveable {mutableIntStateOf(0)}
    var result by remember {mutableStateOf("")}
    var roots by remember {mutableIntStateOf(0)}
    val scope=rememberCoroutineScope()
    val connection by graph.gateway.state.collectAsState()
    val health by graph.health.collectAsState()
    val semantic by SemanticAccessibilityService.connectionState.collectAsState()
    val listener by NotificationCaptureService.connectionState.collectAsState()
    val accessRevision=permissionRevision
    val notificationsGranted=remember(accessRevision) {getSystemService(NotificationManager::class.java).areNotificationsEnabled()}
    val listenerComponent=remember {ComponentName(this,NotificationCaptureService::class.java)}
    val listenerGranted=remember(accessRevision) {getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listenerComponent)}
    val semanticGranted=remember(accessRevision) {AndroidSettings.Secure.getString(contentResolver,AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any {ComponentName.unflattenFromString(it)==ComponentName(this,SemanticAccessibilityService::class.java)}}
    val stages=listOf("Welcome","Android access","Native ready","Gateway","AI agents","Advanced control","Background","Verify")
    LaunchedEffect(permissionRevision) {withContext(Dispatchers.IO) {graph.ready.await();roots=DocumentRoots(this@NativeSetup,graph.db).all().size}}
    fun granted(permission:String)=checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED
    fun settings(action:String,data:Uri?=null) {runCatching {startActivity(Intent(action,data))}.onFailure {result="Android did not expose this Settings page. Open SIConnect’s app info in Android Settings."}}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=720.dp).fillMaxWidth().fillMaxHeight(.96f).safeDrawingPadding().imePadding(),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Set up SIConnect",style=MaterialTheme.typography.headlineSmall)
                Text("${stage+1} of 8 · ${stages[stage]}",style=MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress={(stage+1)/8f},modifier=Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    when(stage) {
                        0 -> {
                            Text("Native tools are useful immediately. Connect your existing gateway and authorized AI agents when ready. Advanced device control is optional and uses Android Wireless debugging authorization.")
                            Text("SIConnect executes under your grants, with exact action review where required. It does not need a local AI model. No setup step creates Cloudflare infrastructure or changes another agent’s permissions.")
                        }
                        1 -> {
                            Text("Enable calendar and notification workflows together, or choose individual access below. Android still requires its own special-access approvals. This does not change remote-agent grants.")
                            val requested=mutableListOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
                            if(Build.VERSION.SDK_INT>=33) requested+=Manifest.permission.POST_NOTIFICATIONS
                            val missing=requested.filterNot {granted(it)}
                            if(missing.isNotEmpty()) Button(onClick={requestNativeAccess(missing.toTypedArray())},modifier=Modifier.fillMaxWidth()) {Text("Enable calendar and notifications")}
                            Text("For all-app shell control, enable advanced device control. Android’s selected-folder picker cannot grant protected folders or other apps’ private storage. Existing authorized broad shell remains available independently.")
                            Text("Notifications: ${if(notificationsGranted) "granted" else "denied / owner action required"}. Used for approvals and reminders.")
                            Button(onClick={if(Build.VERSION.SDK_INT>=33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) requestNativeAccess(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE,packageName))}) {Text(if(notificationsGranted) "Manage notifications" else "Enable notifications")}
                            Text("Calendar: ${if(granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.WRITE_CALENDAR)) "granted" else "not granted"}. Used only for authorized Calendar Provider operations.")
                            if(!granted(Manifest.permission.READ_CALENDAR) || !granted(Manifest.permission.WRITE_CALENDAR)) OutlinedButton(onClick={requestNativeAccess(arrayOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR))}) {Text("Enable calendar workflows")}
                            Text("Notification listener: ${if(listener) "connected" else "backend unavailable / owner action required"}. Separate from ordinary notification permission.")
                            OutlinedButton(onClick={if(listenerGranted && !listener) android.service.notification.NotificationListenerService.requestRebind(listenerComponent) else settings(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)}) {Text(if(listener) "Manage notification access" else if(listenerGranted) "Reconnect notification listener" else "Enable notification access")}
                            Text("Accessibility: ${if(semantic) "connected" else "backend unavailable / owner action required"}. Needed for semantic screen tools, not background native diagnostics.")
                            OutlinedButton(onClick={settings(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)}) {Text(if(semantic) "Manage screen access" else if(semanticGranted) "Restore screen service" else "Enable screen access")}
                            Text("If Android restricts sideloaded services, use App info → menu → Allow restricted settings when Android offers it. SIConnect cannot override that restriction.")
                            TextButton(onClick={settings(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))}) {Text("Open app info")}
                            Text("Selected folders: $roots. Android grants only the folder you choose.")
                            OutlinedButton(onClick={ownerAuthentication.authenticate("Connect selected folder",{documentPicker.launch(null)},{result=it})}) {Text("Choose document folder")}
                            Text(documentStatus)
                            Text("Exact alarms: ${if(graph.reminders.canScheduleExact()) "granted" else "owner action required for precise timing"}. Flexible scheduling remains available.")
                            OutlinedButton(onClick={settings(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName"))}) {Text(if(graph.reminders.canScheduleExact()) "Manage exact alarms" else "Enable exact alarms")}
                            if(permissionStatus.isNotBlank()) Text(permissionStatus)
                        }
                        2 -> {
                            Text(health)
                            Text("Native diagnostics, managed files, and weather with an explicitly configured location do not require shell UID. Calendar, reminders, selected documents, notification actions and semantic screen tools use their respective Android permissions and current agent grants.")
                            Text("Opening Settings does not prove a service connected. The final check reports actual native execution and current backend state.")
                        }
                        3 -> {
                            Text("Gateway: ${connection.name}")
                            Text("Use your existing pre-provisioned HTTPS gateway and its secure setup code. Enrollment is independent of AI-agent grants. SIConnect does not contain Cloudflare API tokens or provision an account on your behalf.")
                            Button(onClick=onGateway) {Text("Connect existing gateway")}
                        }
                        4 -> {
                            Text("Connect AI agent through the existing gateway’s MCP address and an owner-created invitation. Existing identities and grants remain available in Agents. Reconnect that exact agent to preserve its permissions; don’t create a duplicate identity for a scope problem.")
                            Button(onClick=onAgents) {Text("Connect AI agent / manage existing agents")}
                        }
                        5 -> {
                            Text("Enable advanced device control through Android’s own Wireless debugging pairing. This pairs SIConnect’s phone-local ADB identity, separately from AI-agent pairing. The bundled Binder backend remains the runtime executor.")
                            Text("Privileged backend: ${graph.privileged.detail()}")
                            Button(onClick=onAdvanced) {Text("Enable advanced device control")}
                            Text("You can finish native setup without this optional stage.")
                        }
                        6 -> {
                            val ignored=getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                            Text("Battery optimization exemption: ${if(ignored) "granted" else "not granted"}")
                            Text("The owner-enabled remote connection uses a foreground service and a persistent notification. Android and Samsung still control background starts, idle delays and force-stop behavior. Choose suitable battery settings for daily use.")
                            OutlinedButton(onClick={settings(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)}) {Text("Review background battery settings")}
                            Text("After reboot, native scheduling and the owner-enabled gateway restore where Android permits. Shell authority must be verified again; Wireless debugging may need an owner toggle on trusted Wi-Fi.")
                        }
                        7 -> {
                            Text("Runtime: $health\nGateway: ${connection.name}\nBundled backend: ${graph.privileged.detail()}\nAccessibility connected: $semantic\nNotification listener connected: $listener")
                            Button(onClick={scope.launch {
                                result="Checking real native execution…"
                                try {
                                    val r=withContext(Dispatchers.IO) {graph.ready.await();graph.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="device.status"),Origin.LOCAL_OWNER)}
                                    result=if(r.state==RequestState.SUCCEEDED) "Native diagnostics successfully verified. Request ${r.requestId}. Gateway/privilege states above are separate observations." else "Native verification ${r.state}: ${r.errorMessage ?: "inspect Activity"}"
                                } catch(e:Exception) {result="Native verification unavailable: ${e.javaClass.simpleName}"}
                            }}) {Text("Verify native readiness")}
                            Text("Finish saves only that you reviewed setup. It does not grant missing access or report untested remote tools as connected.")
                        }
                    }
                    if(result.isNotBlank()) Text(result)
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick=onClose) {Text("Close")}
                    if(stage>0) TextButton(onClick={stage--;result=""}) {Text("Back")}
                    Button(onClick={if(stage<7) {stage++;result=""} else {getSharedPreferences("owner_setup",android.content.Context.MODE_PRIVATE).edit().putBoolean("reviewed",true).apply();onClose()}}) {Text(if(stage==7) "Finish native setup" else "Next")}
                }
            }
        }
    }
}
