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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.conflate
import kotlinx.serialization.json.*

@Composable internal fun MainActivity.PrivateWorkspaceSettings() {
    val scope=rememberCoroutineScope()
    val controls by graph.settings.controls.collectAsState()
    val direct by graph.privileged.directStatus.collectAsState()
    val shizuku by graph.shizuku.status.collectAsState()
    var busy by remember {mutableStateOf(false)}
    var message by rememberSaveable {mutableStateOf("")}
    var failed by rememberSaveable {mutableStateOf(false)}
    var requestId by rememberSaveable {mutableStateOf<String?>(null)}
    var lookupRevision by remember {mutableIntStateOf(0)}
    var loading by remember {mutableStateOf(requestId!=null)}
    var lookupFailed by remember {mutableStateOf(false)}
    var readiness by remember {mutableStateOf<JsonObject?>(null)}
    var checkedAt by remember {mutableLongStateOf(0L)}
    var confirmClose by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(direct,shizuku,controls.privilegedEnabled) {readiness=null}
    fun showResult(result:RequestRecord) {
        if(result.requestId!=requestId) return
        busy=!result.state.isTerminal
        lookupFailed=false
        if(result.state==RequestState.SUCCEEDED) {
            val output=result.result?.output as? JsonObject
            failed=false
            if(result.capabilityId=="headless.status") {
                readiness=output?.takeIf {result.result?.backend==graph.privileged.kind}
                checkedAt=result.updatedAtMs
                failed=readiness==null
                message=when {output==null->"Readiness details are no longer available. Run a fresh check to observe the phone now.";readiness==null->"The saved check used a different backend. Check readiness through the currently selected backend.";else->"Saved workspace check from the selected backend. Recheck after the phone locks or its backend restarts."}
            } else {
                val closed=output?.get("closed")?.jsonPrimitive?.intOrNull
                failed=closed==null
                message=closed?.let {"Closed $it private app workspaces."} ?: "The backend did not return a workspace count. Check this request in Activity before repeating it."
                readiness=null
            }
        } else {
            failed=result.state.isTerminal
            message="${result.errorMessage ?: actionStateLabel(result.state)}. Request ${result.requestId} is retained in Activity."
        }
    }
    LaunchedEffect(requestId,lookupRevision) {
        val id=requestId ?: return@LaunchedEffect
        graph.engine.recordUpdates.refreshesFor(id).collect {
            loading=true
            try {
                val result=withContext(Dispatchers.IO) {graph.ready.await();graph.requests.get(id)}
                if(requestId==id && result!=null) showResult(result)
                else if(!busy) {lookupFailed=true;failed=true;message="The previous request could not be found. Check Activity before repeating an action."}
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {lookupFailed=true;failed=true;message="The saved request could not be loaded. Retry reads its existing result without running the action again."}
            finally {loading=false}
        }
    }
    fun runCapability(id:String) {
        if(busy) return
        busy=true;message="";failed=false
        val idempotentRequestId=java.util.UUID.randomUUID().toString()
        requestId=idempotentRequestId
        scope.launch {
            var admitted:RequestRecord?=null
            try {
                val result=withContext(Dispatchers.IO) {
                    graph.ready.await()
                    graph.engine.submit(InvocationRequest(requestId=idempotentRequestId,agentId="local-owner",capabilityId=id),Origin.LOCAL_OWNER)
                }
                admitted=result
                showResult(result)
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {failed=true;message="Could not confirm the outcome. Check advanced device control and Activity before repeating the action."}
            finally {if(requestId==idempotentRequestId && admitted==null) busy=false}
        }
    }
    Panel {
        Text("Private app workspaces",style=MaterialTheme.typography.titleMedium)
        Text("Run supported apps on a separate display while keeping your shared screen available.",style=MaterialTheme.typography.bodyMedium)
        Text("Uses each installed app’s existing account and data. The phone must stay awake and unlocked, with advanced device control and separate agent/app allowances.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        StatusPill(when { !controls.privilegedEnabled->"Advanced routing paused";graph.privileged.available()->"Advanced control available";else->"Advanced control needed"},if(graph.privileged.available() && controls.privilegedEnabled) StatusTone.GOOD else StatusTone.ATTENTION)
        OutlinedButton(enabled=!busy && !loading && graph.privileged.available() && controls.privilegedEnabled,onClick={runCapability("headless.status")}) {Text(if(busy) "Request in progress…" else "Check workspace readiness")}
        readiness?.let {result->
            val ready=result["privateUiReady"]?.jsonPrimitive?.booleanOrNull
            StatusPill(when(ready) {true->"Last check: phone ready";false->"Last check: phone needs attention";null->"Readiness was not confirmed"},if(ready==true) StatusTone.GOOD else StatusTone.ATTENTION)
            fun observed(key:String,yes:String,no:String)=when(result[key]?.jsonPrimitive?.booleanOrNull) {true->yes;false->no;null->"unknown"}
            Text("Physical screen: ${observed("physicalDisplayAwake","awake","not awake")}\nPhone lock: ${observed("physicalDeviceLocked","locked","unlocked")}",style=MaterialTheme.typography.bodySmall)
            Text("Checked ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(checkedAt))}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Panel {
        Text("Close private work",style=MaterialTheme.typography.titleMedium)
        Text("Close private displays for every agent. Unsaved work inside those app screens may be lost; already completed external actions cannot be undone.",style=MaterialTheme.typography.bodyMedium)
        OutlinedButton(enabled=!busy && !loading && graph.privileged.available(),onClick={confirmClose=true}) {Text("Close all private workspaces")}
        Text("Workspaces expire within ten minutes and close when SIConnect or its backend restarts. Protected authentication stays on the phone’s owner-controlled surfaces.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if(loading && !busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Loading the original request’s saved result…",style=MaterialTheme.typography.bodySmall)}
    if(message.isNotBlank()) Notice(message,failed)
    if(lookupFailed) TextButton(onClick={lookupRevision++},enabled=!loading) {Text("Retry loading saved result")}
    if(confirmClose) AlertDialog(onDismissRequest={confirmClose=false},title={Text("Close every private workspace?")},
        text={Text("This closes private app displays belonging to all agents. Unsaved in-app work may be lost. It does not undo actions those apps have already completed.")},
        confirmButton={TextButton(enabled=!busy,onClick={confirmClose=false;runCapability("headless.stop_all")}) {Text("Close workspaces")}},
        dismissButton={TextButton(onClick={confirmClose=false}) {Text("Keep working")}})
}
