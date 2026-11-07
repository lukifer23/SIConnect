package dev.siconnect.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.siconnect.app.graph
import dev.siconnect.app.termux.TermuxBridge
import dev.siconnect.app.termux.TermuxOwnerChecks
import dev.siconnect.app.termux.TermuxStatus
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

private const val OWNER_CHECKS="termux_owner_checks"
private const val PREFIX="/data/data/com.termux/files/usr/bin/"
private data class TermuxOwnerState(val status:TermuxStatus,val probe:RequestRecord?,val codex:RequestRecord?,val probeOutput:JsonObject?,val codexOutput:JsonObject?)

/** Package checks, journal reads, and owner-check bookkeeping stay off the composition thread. */
@Composable internal fun MainActivity.TermuxSettings(onReview:(RequestRecord)->Unit) {
    val g=graph
    val scope=rememberCoroutineScope()
    val revision=permissionRevision
    var refresh by remember {mutableIntStateOf(0)}
    var state by remember {mutableStateOf<TermuxOwnerState?>(null)}
    var message by remember {mutableStateOf("")}
    var submitting by remember {mutableStateOf(false)}
    LaunchedEffect(revision,refresh) {
        try {
            val bridge=withContext(Dispatchers.IO) {g.ready.await();TermuxBridge.get(g.context)}
            merge(bridge.updates.map {Unit},g.engine.recordUpdates.filter {it.capabilityId.startsWith("termux.") && it.agentId=="local-owner"}.map {Unit})
                .onStart {emit(Unit)}.conflate().collect {
                state=withContext(Dispatchers.IO) {
                    val saved=g.context.getSharedPreferences(OWNER_CHECKS,Context.MODE_PRIVATE)
                    fun check(key:String)=saved.getString(key,null)?.let {g.requests.get(it)}
                    val probe=check("probe");val codex=check("codex")
                    fun output(record:RequestRecord?):JsonObject?=record?.let {
                        try {bridge.result("local-owner",it.requestId)}
                        catch(e:CapabilityException) {if(e.code=="TERMUX_RESULT_NOT_FOUND") it.result?.output as? JsonObject else throw e}
                    }
                    TermuxOwnerState(bridge.status(),probe,codex,output(probe),output(codex))
                }
            }
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {message="Termux status could not refresh: ${e.javaClass.simpleName}"}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        message=if(granted) "Android command permission granted. Configure Termux, then verify background execution." else "Command permission was not granted. You can review it in SIConnect’s Android app permissions."
        g.gateway.refreshCatalog()
        permissionRevision++
        refresh++
    }
    fun open(intent:Intent) {
        try {startActivity(intent)}
        catch(_:ActivityNotFoundException) {message="Android could not open this page. Open the app’s settings manually."}
        catch(_:SecurityException) {message="Android did not allow this page to open. Review the app in Android Settings."}
    }
    fun runCheck(codex:Boolean) {
        if(submitting) return
        submitting=true
        scope.launch {
            try {
                val result=withContext(Dispatchers.IO) {
                    g.ready.await()
                    val id=UUID.randomUUID().toString()
                    check(g.context.getSharedPreferences(OWNER_CHECKS,Context.MODE_PRIVATE).edit().putString(if(codex) "codex" else "probe",id).commit()) {"The owner check could not be saved"}
                    g.engine.submit(InvocationRequest(requestId=id,agentId="local-owner",capabilityId="termux.execute",arguments=buildJsonObject {
                        put("executable",PREFIX+if(codex) "sh" else "id")
                        put("arguments",JsonArray((if(codex) TermuxOwnerChecks.codexArguments() else listOf("-u")).map(::JsonPrimitive)))
                        put("timeoutMs",30_000);put("maxOutputBytes",1024)
                    }),Origin.LOCAL_OWNER)
                }
                if(result.state==RequestState.AWAITING_APPROVAL) onReview(result)
                refresh++
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {message=e.message ?: "The background check could not complete";refresh++}
            finally {submitting=false}
        }
    }
    Text("Termux background commands",style=MaterialTheme.typography.titleLarge)
    Text("Run tools in Termux’s own app environment without opening a terminal screen. This is separate from the privileged Android shell and uses Termux’s files, packages, and accounts.",style=MaterialTheme.typography.bodyMedium)
    if(message.isNotBlank()) Notice(message)
    val current=state
    if(current==null) {
        if(message.isBlank()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Checking this phone’s Termux setup…",style=MaterialTheme.typography.bodySmall)
        } else OutlinedButton(onClick={message="";refresh++}) {Text("Retry status check")}
    } else {
        val status=current.status
        val pending=submitting || listOfNotNull(current.probe,current.codex).any {!it.state.isTerminal}
        val canCheck=status.installed && status.appEnabled && status.serviceAvailable && status.resultApiSupported!=false && status.runCommandPermissionGranted && !pending
        Panel {
            SectionHeading("Connection readiness")
            StatusPill(when { !status.installed->"Termux not installed";!status.appEnabled->"Termux disabled";!status.serviceAvailable->"Command service unavailable";status.resultApiSupported==false->"Termux update needed";!status.runCommandPermissionGranted->"Android permission needed";status.allowExternalAppsState=="CONFIRMED_ALLOWED"->"Command callback observed";status.allowExternalAppsState=="REJECTED"->"Termux configuration needs attention";else->"Background verification needed"},
                if(canCheck && status.allowExternalAppsState=="CONFIRMED_ALLOWED") StatusTone.GOOD else StatusTone.ATTENTION)
            if(status.installed) Text("Termux ${status.versionName ?: "version unknown"} · app UID ${status.uid ?: "unknown"}",style=MaterialTheme.typography.bodySmall)
            if(status.resultApiSupported==false) Text("This Termux version does not support the command-result callback. Update Termux before verifying background execution.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            Text("Android command permission: ${if(status.runCommandPermissionGranted) "granted" else "not granted"}",style=MaterialTheme.typography.bodyMedium)
            Text("External commands: ${when(status.allowExternalAppsState) {"CONFIRMED_ALLOWED"->"allowed at the last successful callback";"REJECTED"->"rejected by Termux";else->"not yet confirmed by a command callback"}}",style=MaterialTheme.typography.bodyMedium)
            status.lastSuccessfulCommandAtMs?.let {Text("Last successful background command: ${termuxTime(it)}",style=MaterialTheme.typography.bodySmall)}
            if(status.pendingExecutionCount>0) Text("${status.pendingExecutionCount} command results still pending. A timeout does not prove a Termux process stopped.",style=MaterialTheme.typography.bodySmall)
            status.lastErrorMessage?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error,maxLines=6)}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Button(onClick={runCheck(false)},enabled=canCheck) {Text("Verify background execution")}
                TextButton(onClick={refresh++},enabled=!submitting) {Text("Refresh status")}
            }
            current.probe?.let {record->
                val output=current.probeOutput
                val uid=output?.get("stdout")?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull()
                val sameIdentity=sameTermuxInstall(output,status)
                val verified=sameIdentity && output?.get("commandSucceeded")?.jsonPrimitive?.booleanOrNull==true && output["callbackReceived"]?.jsonPrimitive?.booleanOrNull==true && uid!=null && uid==status.uid
                Text(when {verified->"Verified: command completed under Termux UID $uid.";!record.state.isTerminal->"Background verification is ${actionStateLabel(record.state).lowercase()}.";output?.get("callbackReceived")?.jsonPrimitive?.booleanOrNull==true && !sameIdentity->"The saved verification belongs to an earlier Termux installation. Verify this installation again.";else->"Background execution was not verified. ${record.errorMessage ?: "Review the command result in Activity."}"},style=MaterialTheme.typography.bodySmall)
                Text("Last update ${termuxTime(output?.get("callbackAtMs")?.jsonPrimitive?.longOrNull ?: record.updatedAtMs)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(record.state==RequestState.AWAITING_APPROVAL) TextButton(onClick={onReview(record)}) {Text("Review background check")}
            }
        }
        ExpandablePanel("Owner setup",if(status.runCommandPermissionGranted && status.allowExternalAppsState=="CONFIRMED_ALLOWED") "Configured · review Android permission and Termux settings" else "Two owner choices, then a real background check",
            initiallyExpanded=!status.runCommandPermissionGranted || status.allowExternalAppsState!="CONFIRMED_ALLOWED") {
            Text("1. Allow SIConnect to run commands in Termux. Android may list this under Additional permissions.",style=MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={permission.launch("com.termux.permission.RUN_COMMAND")},enabled=status.installed && !status.runCommandPermissionGranted && !pending) {Text("Allow command permission")}
                TextButton(onClick={open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}) {Text("SIConnect app permissions")}
            }
            Text("2. In Termux, edit ~/.termux/termux.properties and set this property. Keep your other settings, then run termux-reload-settings.",style=MaterialTheme.typography.bodyMedium)
            Text("allow-external-apps = true",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Termux external commands","allow-external-apps = true"));message="Property copied. Paste it into your Termux settings file."}) {Text("Copy property")}
                TextButton(onClick={val intent=packageManager.getLaunchIntentForPackage("com.termux");if(intent!=null) open(intent) else message="Termux is not available to open."},enabled=status.installed && status.appEnabled) {Text("Open Termux")}
                TextButton(onClick={open(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent")))}) {Text("Setup guide")}
            }
            Text("3. Return here and verify background execution. Installing Termux or opening its screen does not verify command access or account sign-in.",style=MaterialTheme.typography.bodyMedium)
        }
        ExpandablePanel("Codex in Termux","Explicitly check the CLI’s own sign-in status") {
            Text("This runs codex login status in a background Termux shell. It does not start login, change credentials, or display authentication output.",style=MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick={runCheck(true)},enabled=canCheck) {Text("Check Codex sign-in")}
            current.codex?.let {record->
                val output=current.codexOutput
                val exit=output?.get("exitCode")?.jsonPrimitive?.intOrNull
                val sameIdentity=sameTermuxInstall(output,status)
                val succeeded=sameIdentity && output?.get("callbackReceived")?.jsonPrimitive?.booleanOrNull==true && output["commandSucceeded"]?.jsonPrimitive?.booleanOrNull==true && exit==0
                Text(when {succeeded->"Codex confirmed sign-in at this check.";!record.state.isTerminal->"Sign-in check is ${actionStateLabel(record.state).lowercase()}.";output?.get("callbackReceived")?.jsonPrimitive?.booleanOrNull==true && !sameIdentity->"The saved check belongs to an earlier Termux app identity. Check this installation again.";exit==127->"Codex CLI was not found in this Termux login shell.";else->"Codex sign-in was not confirmed. Review Codex in Termux; no credentials were changed."},style=MaterialTheme.typography.bodyMedium)
                Text("Last update ${termuxTime(output?.get("callbackAtMs")?.jsonPrimitive?.longOrNull ?: record.updatedAtMs)}. This is a saved observation, not a live authentication guarantee.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(record.state==RequestState.AWAITING_APPROVAL) TextButton(onClick={onReview(record)}) {Text("Review sign-in check")}
            } ?: Text("Codex installation and sign-in have not been checked here.",style=MaterialTheme.typography.bodySmall)
        }
    }
}

private fun sameTermuxInstall(output:JsonObject?,status:TermuxStatus)=status.uid!=null && status.versionCode!=null && status.firstInstallAtMs!=null &&
    output?.get("packageUid")?.jsonPrimitive?.intOrNull==status.uid &&
    output["packageVersionCode"]?.jsonPrimitive?.longOrNull==status.versionCode &&
    output["packageFirstInstallAtMs"]?.jsonPrimitive?.longOrNull==status.firstInstallAtMs

private fun termuxTime(at:Long)=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(at))
