package dev.siconnect.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.siconnect.app.graph
import dev.siconnect.app.bootstrap.*
import kotlinx.coroutines.*

@Composable internal fun MainActivity.AdvancedControlSetup(onClose:()->Unit) {
    val bootstrap=graph.bootstrap
    val state by bootstrap.status.collectAsState()
    val fingerprint by bootstrap.fingerprint.collectAsState()
    val lastFailure by bootstrap.lastFailure.collectAsState()
    val scope=rememberCoroutineScope()
    var endpoint by remember {mutableStateOf<LocalAdbEndpoint?>(null)}
    var code by remember {mutableStateOf("")}
    var advanced by rememberSaveable {mutableStateOf(false)}
    var port by rememberSaveable {mutableStateOf("")}
    var message by remember {mutableStateOf("")}
    var finding by remember {mutableStateOf(false)}
    var discoveryJob by remember {mutableStateOf<Job?>(null)}
    var active by remember {mutableStateOf(true)}
    var generation by remember {mutableIntStateOf(0)}
    var recovery by remember {mutableStateOf(bootstrap.recoveryEnabled)}
    var configureRecovery by rememberSaveable {mutableStateOf(false)}
    DisposableEffect(Unit) {onDispose {active=false;generation++}}
    LaunchedEffect(permissionRevision) {bootstrap.refresh()}
    fun authorize(title:String,action:()->Unit) {
        val current=++generation
        ownerAuthentication.authenticate(title,{if(active && current==generation) action()},{message=it;code=""})
    }
    fun findPairing() {
        discoveryJob=scope.launch {
            finding=true;message="";endpoint=null
            try {endpoint=bootstrap.discover(true,!bootstrap.hasLocalPermission)}
            catch(_:CancellationException) {message="No pairing service selected. Keep Android’s pairing dialog open and try again."}
            catch(e:Exception) {message=e.message ?: "Could not find Android’s pairing service"}
            finally {finding=false}
        }
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=600.dp).fillMaxWidth().fillMaxHeight(.94f).safeDrawingPadding().imePadding(),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Advanced device control",style=MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    if(bootstrap.pairedAt>0L) {
                        Row {Text("Reconnect automatically",Modifier.weight(1f));Switch(recovery,{enabled->
                            if(enabled) authorize("Restore control using saved Android authorization") {bootstrap.setRecoveryFromOwner(true);recovery=true}
                            else {bootstrap.setRecoveryFromOwner(false);recovery=false}
                        },enabled=!state.busy)}
                        Text("Reuses this phone’s saved pairing after connection or service loss. Android may still require you to enable Wireless debugging after reboot.",style=MaterialTheme.typography.bodySmall)
                    }
                    if(state.phase==BootstrapPhase.PRIVILEGED_READY && !configureRecovery) {
                        Text("Control verified",style=MaterialTheme.typography.titleLarge)
                        Text(state.detail)
                        state.identity?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                        if(bootstrap.pairedAt==0L) Button(onClick={configureRecovery=true}) {Text("Set up phone-only recovery")}
                    } else {
                        Text("Native features are ready. Enable shell control with Android’s Wireless debugging authorization.")
                        if(bootstrap.pairedAt==0L) {
                            Text("1. Open Wireless debugging",style=MaterialTheme.typography.titleMedium)
                            Text("Developer options → Wireless debugging → Pair device with pairing code. Keep Settings beside SIConnect in split-screen or a pop-up.")
                            OutlinedButton(onClick={runCatching {startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))}.onFailure {message="Open Android Settings → Developer options → Wireless debugging"}}) {Text("Open Settings beside SIConnect")}
                            Text("2. Enter Android’s code",style=MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick={findPairing()},enabled=!state.busy && !finding) {Text(if(endpoint==null) "Find this phone" else "Phone found · ${endpoint!!.port}")}
                            OutlinedTextField(code,{code=it.filter {c->c in '0'..'9'}.take(6)},label={Text("6-digit pairing code")},singleLine=true,
                                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                            Button(onClick={
                                val selected=endpoint;val entered=code
                                if(selected==null) message="Tap Find this phone while Android’s pairing dialog is open."
                                else authorize("Pair and enable device control") {bootstrap.pairAndEnableFromOwner(selected,entered);code=""}
                            },enabled=endpoint!=null && code.length==6 && !state.busy && !finding,modifier=Modifier.fillMaxWidth()) {Text("Pair and enable")}
                            Text("Confirm once with Android. SIConnect connects, starts its bundled service, verifies UID 2000, then disconnects ADB.",style=MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Paired with Android",style=MaterialTheme.typography.titleMedium)
                            Text("Enable Wireless debugging on trusted Wi-Fi if needed, then restore. Android may ask you to select this phone’s connection service.")
                            Button(onClick={if(bootstrap.recoveryEnabled) bootstrap.recoverIfEnabled() else authorize("Restore advanced control") {bootstrap.restoreFromOwner()}},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) {Text(if(bootstrap.recoveryEnabled) "Retry connection" else "Restore control")}
                            OutlinedButton(onClick={runCatching {startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))}.onFailure {message="Open Android Settings → Developer options"}}) {Text("Open Settings beside SIConnect")}
                        }
                        Text(state.detail)
                    }
                    if(state.busy || finding) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(message.isNotBlank()) Text(message,color=MaterialTheme.colorScheme.error)
                    lastFailure?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                    TextButton(onClick={advanced=!advanced}) {Text(if(advanced) "Hide advanced options" else "Advanced options")}
                    if(advanced) {
                        Text("Android 17’s service picker avoids broad local-network permission. If discovery is unavailable, you can explicitly allow local discovery and enter the pairing port.")
                        if(android.os.Build.VERSION.SDK_INT>=37) {
                            if(bootstrap.hasLocalPermission) Text("Local-network access granted")
                            else OutlinedButton(onClick={permission.launch(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK))}) {Text("Allow local discovery")}
                        }
                        OutlinedTextField(port,{port=it.filter {c->c in '0'..'9'}.take(5)},label={Text("Manual pairing port")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                        TextButton(onClick={try {endpoint=bootstrap.manualEndpoint("127.0.0.1",port);message="Manual pairing port selected"} catch(e:Exception) {message=e.message ?: "Port unavailable"}}) {Text("Use pairing port")}
                        Text("Recovery never turns debugging on. Android may require an owner toggle after reboot. Broad authorized shell can reach beyond narrower UI permissions. AI-agent pairing is separate.")
                        Text("${state.phase}\nHost key: ${fingerprint ?: "not created"}\nLast verified: ${if(bootstrap.lastBootstrap==0L) "never" else java.util.Date(bootstrap.lastBootstrap)}\nVerification time: ${state.elapsedMs?.let {"$it ms"} ?: "not measured"}",style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={authorize("Restart bundled service after update") {bootstrap.bootstrapFromOwner(null,restart=true)}},enabled=!state.busy) {Text("Restart after app update")}
                        TextButton(onClick={authorize("Remove local host identity; require new pairing") {bootstrap.resetIdentityFromOwner()}},enabled=!state.busy) {Text("Forget local pairing")}
                        TextButton(onClick={startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}) {Text("Android app permissions")}
                    }
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(state.busy || finding) TextButton(onClick={generation++;discoveryJob?.cancel();bootstrap.cancel();code=""}) {Text("Cancel")}
                    TextButton(onClick={bootstrap.refresh()},enabled=!state.busy) {Text("Check status")}
                    TextButton(onClick={active=false;generation++;code="";onClose()}) {Text("Done")}
                }
            }
        }
    }
}
