package dev.siconnect.app.ui

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siconnect.app.capabilities.recoverySnapshot
import dev.siconnect.app.graph
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.*

/** Supported owner repairs operate on saved configuration; a refresh itself never mutates it. */
@Composable internal fun MainActivity.RecoverySettings(onConnections:()->Unit,onAdvanced:()->Unit,onTermux:()->Unit) {
    val g=graph;val scope=rememberCoroutineScope()
    val stages by g.startup.stages.collectAsState()
    val connection by g.gateway.state.collectAsState()
    val direct by g.privileged.directStatus.collectAsState()
    val shizuku by g.shizuku.status.collectAsState()
    val controls by g.settings.controls.collectAsState()
    val screen by dev.siconnect.app.accessibility.SemanticAccessibilityService.connectionState.collectAsState()
    val listener by dev.siconnect.app.notifications.NotificationCaptureService.connectionState.collectAsState()
    var snapshot by remember {mutableStateOf<JsonObject?>(null)}
    var revision by remember {mutableIntStateOf(0)}
    var loading by remember {mutableStateOf(true)}
    var error by remember {mutableStateOf<String?>(null)}
    var requestId by rememberSaveable {mutableStateOf<String?>(null)}
    var outcome by remember {mutableStateOf<RequestRecord?>(null)}
    var busy by remember {mutableStateOf(requestId!=null)}
    var submitting by remember {mutableStateOf(false)}
    var lookupRevision by remember {mutableIntStateOf(0)}
    var actionError by rememberSaveable {mutableStateOf<String?>(null)}
    LaunchedEffect(permissionRevision,stages,connection,direct,shizuku,controls,screen,listener,revision) {
        loading=true;error=null
        try {g.ready.await();snapshot=recoverySnapshot(g,"local-owner")}
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {error="Recovery observations could not be loaded. Saved connections and permissions are unchanged."}
        finally {loading=false}
    }
    LaunchedEffect(requestId,lookupRevision) {
        val id=requestId ?: return@LaunchedEffect
        g.engine.recordUpdates.refreshesFor(id).collect {
            try {
                val stored=withContext(Dispatchers.IO) {g.ready.await();g.requests.get(id)}
                if(requestId==id && stored!=null) {outcome=stored;busy=!stored.state.isTerminal;revision++}
                else if(requestId==id && !submitting) {busy=false;actionError="The original repair request is not available. Inspect Activity before requesting another repair."}
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {actionError="The original repair result could not be loaded. Inspect Activity before repeating it."}
        }
    }
    fun repair(capability:String) {
        if(busy) return
        val id=java.util.UUID.randomUUID().toString()
        requestId=id;outcome=null;busy=true;submitting=true;actionError=null
        scope.launch {
            try {
                val result=withContext(Dispatchers.IO) {g.ready.await();g.engine.submit(InvocationRequest(requestId=id,agentId="local-owner",capabilityId=capability),Origin.LOCAL_OWNER)}
                if(requestId==id) {outcome=result;busy=!result.state.isTerminal;revision++}
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {if(requestId==id) {busy=false;actionError="Repair outcome is unconfirmed. The original request remains in Activity; it has not been submitted again."}}
            finally {submitting=false}
        }
    }
    fun openSettings(intent:Intent) {runCatching {startActivity(intent)}.onFailure {actionError="Open Android Settings → SIConnect to review this access."}}
    Panel {
        Text("Recovery center",style=MaterialTheme.typography.titleLarge)
        Text("Saved access and a connected service are separate. These checks show what is available now and the specific steps that need attention.")
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {Notice(it,true)}
        TextButton(onClick={revision++},enabled=!loading) {Text(if(error==null) "Refresh recovery status" else "Retry recovery status")}
        snapshot?.get("observedAtMs")?.jsonPrimitive?.longOrNull?.let {Text("Checked ${java.text.DateFormat.getTimeInstance().format(java.util.Date(it))}",style=MaterialTheme.typography.bodySmall)}
    }
    snapshot?.let {state->
        fun flag(key:String)=state[key]?.jsonPrimitive?.booleanOrNull==true
        Panel {
            SectionHeading("Connection and access")
            Text("Gateway: ${state["gatewayState"]?.jsonPrimitive?.content ?: "Unknown"}")
            Text("Selected control: ${state["selectedBackend"]?.jsonPrimitive?.content ?: "Unknown"} · ${if(flag("selectedBackendAvailable")) "connected" else "unavailable"}")
            Text("Screen: ${if(flag("screenAccessEnabled")) "Android access saved" else "access not enabled"} · ${if(flag("screenServiceConnected")) "connected" else "disconnected"}")
            Text("Notifications: ${if(flag("notificationAccessEnabled")) "Android access saved" else "access not enabled"} · ${if(flag("notificationServiceConnected")) "connected" else "disconnected"}")
            Text("Termux: ${if(flag("termuxInstalled")) "installed" else "not installed"} · ${if(flag("termuxRunPermissionGranted")) "command access granted" else "command access needed"}")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onConnections) {Text("Connection settings")}
                OutlinedButton(onClick=onAdvanced) {Text("Advanced device control")}
                TextButton(onClick=onTermux) {Text("Termux setup")}
            }
        }
        val issues=(state["issues"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}
        if(issues.isEmpty()) Panel {StatusPill("No recovery blockers observed",StatusTone.GOOD);Text("This is a current observation. Individual actions still check their own permissions and readiness.")}
        issues.forEach {issue->Panel {
            val info=issue["severity"]?.jsonPrimitive?.content=="INFO"
            StatusPill(if(info) "Saved owner choice or optional setup" else "Needs attention",if(info) StatusTone.NEUTRAL else StatusTone.ATTENTION)
            Text(issue["description"]?.jsonPrimitive?.content.orEmpty())
            when(issue["suggestedAction"]?.jsonPrimitive?.content) {
                "RESTORE_SAVED_BRIDGE"->if(flag("gatewayEnabled") && flag("advancedControlsEnabled") && flag("selectedBackendAvailable"))
                    OutlinedButton(onClick={repair("system.restore_bridge")},enabled=!busy) {Text("Restore saved bridge")}
                "ANDROID_SCREEN_SETTINGS"->TextButton(onClick={if(!openScreenControlSettings()) actionError="Open Android Accessibility settings to review SIConnect."}) {Text("Review Android screen access")}
                "ANDROID_NOTIFICATION_SETTINGS"-> {
                    if(flag("notificationAccessEnabled")) OutlinedButton(onClick={repair("system.rebind_notifications")},enabled=!busy) {Text("Reconnect saved notification access")}
                    TextButton(onClick={openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,ComponentName(this@RecoverySettings,dev.siconnect.app.notifications.NotificationCaptureService::class.java).flattenToString()))}) {Text("Review Android notification access")}
                }
            }
        }}
    }
    Panel {
        SectionHeading("Startup tasks")
        stages.forEach {stage->
            Text(stage.title,style=MaterialTheme.typography.titleSmall)
            Text("${stage.state.lowercase().replace('_',' ')} · ${stage.attempts} attempts"+(stage.errorType?.let {" · $it"} ?: ""),style=MaterialTheme.typography.bodySmall)
        }
        if(stages.any {!it.required && it.state in setOf("FAILED","INTERRUPTED")}) {
            Text("Retries only the failed maintenance and saved-dispatch stages. Essential authority recovery and uncertain commands are not replayed.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={repair("system.retry_startup")},enabled=!busy) {Text("Retry failed startup tasks")}
        }
    }
    if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Repair request in progress. Its original result is retained.")}
    outcome?.let {record->Text("Last repair: ${actionStateLabel(record.state)}",style=MaterialTheme.typography.titleSmall);record.errorMessage?.let {Notice(it,true)}}
    actionError?.let {Notice(it,true);if(requestId!=null) TextButton(onClick={lookupRevision++},enabled=!submitting) {Text("Refresh original repair result")}}
}
