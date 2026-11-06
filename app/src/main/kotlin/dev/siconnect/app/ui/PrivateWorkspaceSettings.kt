package dev.siconnect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siconnect.app.graph
import dev.siconnect.core.*
import kotlinx.coroutines.*

@Composable internal fun MainActivity.PrivateWorkspaceSettings() {
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Private app workspaces",style=MaterialTheme.typography.titleMedium)
        Text("Supported apps can run on a separate display while you use this phone. Native background actions remain the first choice.")
        Text("Private workspaces use the installed app’s account and data and leave your shared screen available while the phone is awake and unlocked. Advanced device control and separate agent/per-app permissions are required. App authentication stays protected. Workspaces expire within ten minutes and close when SIConnect or its backend restarts.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled=!busy && graph.privileged.available(),onClick={
            busy=true
            scope.launch {
                try {
                    val result=withContext(Dispatchers.IO) {
                        graph.ready.await()
                        graph.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="headless.stop_all"),Origin.LOCAL_OWNER)
                    }
                    message=if(result.state==RequestState.SUCCEEDED) "All private app workspaces closed." else result.errorMessage ?: "Workspace cleanup could not be confirmed; inspect Activity."
                } catch(e:CancellationException) {throw e} catch(_:Exception) {message="Could not confirm cleanup. Check advanced device control and Activity."}
                finally {busy=false}
            }
        }) {Text(if(busy) "Closing…" else "Close all private workspaces")}
        if(message.isNotBlank()) Text(message,style=MaterialTheme.typography.bodySmall)
    }}
}
