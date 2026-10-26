package dev.siconnect.app.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siconnect.app.ui.*
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.core.*

@Composable fun AgentsScreen(state:ControlCenterSnapshot,wide:Boolean,initialAgentId:String?,onPair:()->Unit,
    onFamily:(String,String,Boolean)->Unit,onShell:(AgentRecord)->Unit,onReconnect:(AgentRecord)->Unit,
    onRevoke:(AgentRecord)->Unit,onRequest:(String)->Unit,onSemanticApp:(String,String,Boolean)->Unit) {
    var selected by rememberSaveable {mutableStateOf(initialAgentId)}
    LaunchedEffect(initialAgentId) {if(initialAgentId!=null) selected=initialAgentId}
    val agents=state.agents.filterNot {it.grant.agentId.startsWith("local-")}
    val current=agents.firstOrNull {it.grant.agentId==selected}
    @Composable fun list() {
        Text("Agents",style=MaterialTheme.typography.headlineMedium)
        Text("Each identity has its own permissions. Last activity does not prove a bot is currently connected.",style=MaterialTheme.typography.bodySmall)
        Button(onClick=onPair) {Text("Pair new agent")}
        if(agents.isEmpty()) Text("No remote agents paired.")
        agents.forEach {a->OutlinedCard(onClick={selected=a.grant.agentId},modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(botDisplayName(a,agents),style=MaterialTheme.typography.titleMedium)
                Text("Identity …${a.grant.agentId.takeLast(8)} · ${agentState(a)}")
                Text(state.recent.firstOrNull {it.agentId==a.grant.agentId}?.let {"Last action: ${actionStateLabel(it.state)}"} ?: "No recent request",style=MaterialTheme.typography.bodySmall)
            }
        }}
    }
    if(wide) Row(Modifier.fillMaxSize().padding(20.dp),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(.38f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {list()}
        Column(Modifier.weight(.62f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            current?.let {AgentDetail(it,state,onFamily,onShell,onReconnect,onRevoke,onRequest,onSemanticApp)} ?: Text("Select an agent to inspect its access and activity.")
        }
    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(current==null) list() else {
            TextButton(onClick={selected=null}) {Text("Back to agents")}
            AgentDetail(current,state,onFamily,onShell,onReconnect,onRevoke,onRequest,onSemanticApp)
        }
    }
}

@Composable private fun AgentDetail(a:AgentRecord,state:ControlCenterSnapshot,onFamily:(String,String,Boolean)->Unit,
    onShell:(AgentRecord)->Unit,onReconnect:(AgentRecord)->Unit,onRevoke:(AgentRecord)->Unit,onRequest:(String)->Unit,onSemanticApp:(String,String,Boolean)->Unit) {
    Text(botDisplayName(a,state.agents),style=MaterialTheme.typography.headlineSmall)
    Text("Identity …${a.grant.agentId.takeLast(8)} · ${agentState(a)}")
    Text("Device-authenticated credential. OAuth/bearer use is not separately recorded for this identity.",style=MaterialTheme.typography.bodySmall)
    Text("Grant expires: ${a.grant.expiresAtMs?.let {java.util.Date(it).toString()} ?: "No configured expiry"}")
    Text("Permissions",style=MaterialTheme.typography.titleLarge)
    Text("Owner allowance and Android readiness are separate. Higher-risk actions still follow the approval policy.",style=MaterialTheme.typography.bodySmall)
    val usable=agentState(a)=="Active"
    val groups=state.capabilities.groupBy {it.descriptor.id.substringBefore('.')}.toSortedMap()
    groups.filterKeys {it!="shell"}.forEach {(family,caps)->
        val allowed=caps.count {allowed(a,it.descriptor.id)}
        val ready=caps.count {allowed(a,it.descriptor.id) && it.availability.isExecutable}
        OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(familyLabel(family),style=MaterialTheme.typography.titleMedium)
                    Text("$allowed/${caps.size} owner-allowed · $ready ready in Android",style=MaterialTheme.typography.bodySmall)
                }
                Switch(checked=allowed==caps.size && usable,onCheckedChange={onFamily(a.grant.agentId,family,it)},enabled=usable)
            }
            caps.filter {allowed(a,it.descriptor.id) && !it.availability.isExecutable}.take(4).forEach {Text("${it.descriptor.title}: ${it.availability.name.replace('_',' ')}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
        }}
    }
    Text("Automatic screen actions",style=MaterialTheme.typography.titleLarge)
    Text("Clicks and text entry normally require approval. Reviewing on this phone changes the screen and invalidates old node references. For unattended workflows, explicitly allow these actions in a specific app, then have the bot inspect again. Screen leases and fresh-node checks still apply.",style=MaterialTheme.typography.bodySmall)
    val appAllowances=a.grant.standing.filter {it.capabilityId=="ui.click" && it.backend==BackendKind.ACCESSIBILITY && it.actionHash==null && it.constraints.keys==setOf("packageName")}.mapNotNull {it.constraints["packageName"]?.let {value->(value as? kotlinx.serialization.json.JsonPrimitive)?.content}}
    appAllowances.forEach {pkg->Row(Modifier.fillMaxWidth()) {
        Text(pkg,Modifier.weight(1f));TextButton(onClick={onSemanticApp(a.grant.agentId,pkg,false)}) {Text("Remove")}
    }}
    var appPackage by rememberSaveable(a.grant.agentId) {mutableStateOf("")}
    var appLabel by rememberSaveable(a.grant.agentId) {mutableStateOf("")}
    var chooseApp by rememberSaveable(a.grant.agentId) {mutableStateOf(false)}
    var manualPackage by rememberSaveable(a.grant.agentId) {mutableStateOf(false)}
    if(usable) {
        OutlinedButton(onClick={chooseApp=true}) {Text("Choose installed app")}
        if(appPackage.isNotBlank()) Text("Selected: ${appLabel.ifBlank {appPackage}}\n$appPackage",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={manualPackage=!manualPackage}) {Text(if(manualPackage) "Hide manual package entry" else "Advanced: enter package manually")}
        if(manualPackage) OutlinedTextField(appPackage,{appPackage=it.take(200);appLabel=""},label={Text("Exact Android package")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Text("Allows this agent to click any accessible control and enter text in that app, including sends or deletes. Protected owner and lock screens remain excluded. This is separate from arbitrary shell access.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={onSemanticApp(a.grant.agentId,appPackage.trim(),true)},enabled=appPackage.isNotBlank()) {Text("Allow clicks and text in this app")}
    }
    if(chooseApp) InstalledAppPicker(appAllowances.toSet(),onSelect={appPackage=it.packageName;appLabel="${it.label} · ${it.version}";chooseApp=false},onClose={chooseApp=false})
    Text("Privileged shell",style=MaterialTheme.typography.titleLarge)
    Text(if(a.grant.shellMode==ShellMode.BROAD) "Broad automatic shell permission is enabled. This can change apps, settings and data." else "Arbitrary shell commands require the current approval policy.")
    if(usable) OutlinedButton(onClick={onShell(a)}) {Text(if(a.grant.shellMode==ShellMode.BROAD) "Review broad shell permission" else "Configure shell permission")}
    Text("Exact standing actions: ${a.grant.standing.count {it.actionHash!=null}}. Shell standing commands: ${a.grant.shellStandingCommands.size}.")
    if(a.grant.standing.isNotEmpty()) a.grant.standing.take(20).forEach {
        val binding=if(it.actionHash!=null) "exact reviewed action" else if(it.constraints.isNotEmpty()) "selected argument constraints" else "capability allowance"
        Text("${it.capabilityId} · $binding · ${it.backend ?: "any eligible backend"}",style=MaterialTheme.typography.bodySmall)
    }
    Text("Explicit deny rules: ${a.grant.denied.joinToString().ifBlank {"None"}}",style=MaterialTheme.typography.bodySmall)
    if(usable && a.grant.agentId.startsWith("bot_")) OutlinedButton(onClick={onReconnect(a)}) {Text("Reconnect this identity")}
    if(!a.grant.revoked) TextButton(onClick={onRevoke(a)}) {Text("Revoke this agent",color=MaterialTheme.colorScheme.error)}
    Text("Recent requests",style=MaterialTheme.typography.titleLarge)
    state.recent.filter {it.agentId==a.grant.agentId}.take(10).forEach {r->TextButton(onClick={onRequest(r.requestId)}) {Text("${r.capabilityId} · ${actionStateLabel(r.state)}")}}
}
private fun agentState(a:AgentRecord)=when {a.grant.revoked->"Revoked";a.grant.expiresAtMs?.let {it<=System.currentTimeMillis()}==true->"Expired";else->"Active"}
private fun allowed(a:AgentRecord,id:String)=!a.grant.revoked && (a.grant.expiresAtMs?.let {it>System.currentTimeMillis()} ?: true) && a.grant.scopes.any {PolicyEngine.globMatches(it,id)} && a.grant.denied.none {PolicyEngine.globMatches(it,id)}
private fun familyLabel(f:String)=mapOf("device" to "Device diagnostics","apps" to "Apps","notifications" to "Notifications","messages" to "Messaging","calendar" to "Calendar","reminders" to "Reminders","weather" to "Weather","files" to "Managed files","documents" to "Selected documents","ui" to "UI interaction","requests" to "Request results","system" to "System commands","settings" to "System settings","alarms" to "Clock information")[f] ?: f.replaceFirstChar {it.uppercase()}
