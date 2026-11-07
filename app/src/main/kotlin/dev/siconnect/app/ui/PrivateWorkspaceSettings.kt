package dev.siconnect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siconnect.app.graph
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable internal fun MainActivity.PrivateWorkspaceSettings() {
    val scope=rememberCoroutineScope()
    val controls by graph.settings.controls.collectAsState()
    val direct by graph.privileged.directStatus.collectAsState()
    val shizuku by graph.shizuku.status.collectAsState()
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    var failed by remember {mutableStateOf(false)}
    var readiness by remember {mutableStateOf<JsonObject?>(null)}
    var checkedAt by remember {mutableLongStateOf(0L)}
    var confirmClose by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(direct,shizuku,controls.privilegedEnabled) {readiness=null}
    fun runCapability(id:String) {
        if(busy) return
        busy=true;message="";failed=false
        scope.launch {
            try {
                val result=withContext(Dispatchers.IO) {
                    graph.ready.await()
                    graph.engine.submit(InvocationRequest(agentId="local-owner",capabilityId=id),Origin.LOCAL_OWNER)
                }
                if(result.state==RequestState.SUCCEEDED) {
                    if(id=="headless.status") {
                        readiness=result.result?.output as? JsonObject
                        checkedAt=System.currentTimeMillis()
                        message="Readiness checked through the selected backend."
                    } else {
                        val closed=(result.result?.output as? JsonObject)?.get("closed")?.jsonPrimitive?.intOrNull
                        message=closed?.let {"Closed $it private app workspaces."} ?: "All private app workspaces closed."
                        readiness=null
                    }
                } else {
                    failed=true
                    message="${result.errorMessage ?: actionStateLabel(result.state)}. Request ${result.requestId} is retained in Activity."
                }
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {failed=true;message="Could not confirm the outcome. Check advanced device control and Activity before repeating the action."}
            finally {busy=false}
        }
    }
    Panel {
        Text("Private app workspaces",style=MaterialTheme.typography.titleMedium)
        Text("Run supported apps on a separate display while keeping your shared screen available.",style=MaterialTheme.typography.bodyMedium)
        Text("Uses each installed app’s existing account and data. The phone must stay awake and unlocked, with advanced device control and separate agent/app allowances.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        StatusPill(when { !controls.privilegedEnabled->"Advanced routing paused";graph.privileged.available()->"Advanced control available";else->"Advanced control needed"},if(graph.privileged.available() && controls.privilegedEnabled) StatusTone.GOOD else StatusTone.ATTENTION)
        OutlinedButton(enabled=!busy && graph.privileged.available() && controls.privilegedEnabled,onClick={runCapability("headless.status")}) {Text(if(busy) "Checking…" else "Check workspace readiness")}
        readiness?.let {result->
            val ready=result["privateUiReady"]?.jsonPrimitive?.booleanOrNull==true
            StatusPill(if(ready) "Phone ready for private app work" else "Phone needs attention",if(ready) StatusTone.GOOD else StatusTone.ATTENTION)
            Text("Physical screen: ${if(result["physicalDisplayAwake"]?.jsonPrimitive?.booleanOrNull==true) "awake" else "not awake"}\nPhone lock: ${if(result["physicalDeviceLocked"]?.jsonPrimitive?.booleanOrNull==true) "locked" else "unlocked"}",style=MaterialTheme.typography.bodySmall)
            Text("Checked ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(checkedAt))}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Panel {
        Text("Close private work",style=MaterialTheme.typography.titleMedium)
        Text("Close private displays for every agent. Unsaved work inside those app screens may be lost; already completed external actions cannot be undone.",style=MaterialTheme.typography.bodyMedium)
        OutlinedButton(enabled=!busy && graph.privileged.available(),onClick={confirmClose=true}) {Text("Close all private workspaces")}
        Text("Workspaces expire within ten minutes and close when SIConnect or its backend restarts. Protected authentication stays on the phone’s owner-controlled surfaces.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if(message.isNotBlank()) Notice(message,failed)
    if(confirmClose) AlertDialog(onDismissRequest={confirmClose=false},title={Text("Close every private workspace?")},
        text={Text("This closes private app displays belonging to all agents. Unsaved in-app work may be lost. It does not undo actions those apps have already completed.")},
        confirmButton={TextButton(enabled=!busy,onClick={confirmClose=false;runCapability("headless.stop_all")}) {Text("Close workspaces")}},
        dismissButton={TextButton(onClick={confirmClose=false}) {Text("Keep working")}})
}
