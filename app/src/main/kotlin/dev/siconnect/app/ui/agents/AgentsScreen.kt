package dev.siconnect.app.ui.agents

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import dev.siconnect.app.R
import dev.siconnect.app.ui.*
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.core.*

@Composable fun AgentsScreen(state:ControlCenterSnapshot,wide:Boolean,initialAgentId:String?,onPair:()->Unit,
    onFamily:(String,String,Boolean)->Unit,onShell:(AgentRecord)->Unit,onReconnect:(AgentRecord)->Unit,
    onRevoke:(AgentRecord)->Unit,onRequest:(String)->Unit,onSemanticApp:(String,String,Boolean)->Unit,onHeadlessApp:(String,String,Boolean)->Unit,onSharedScreen:(String,Boolean)->Unit,
    onFullControl:(AgentRecord,Boolean)->Unit,busy:Boolean=false,onInitialAgentConsumed:()->Unit={}) {
    var selected by rememberSaveable {mutableStateOf(initialAgentId)}
    var query by rememberSaveable {mutableStateOf("")}
    LaunchedEffect(initialAgentId) {if(initialAgentId!=null) {selected=initialAgentId;onInitialAgentConsumed()}}
    val agents=state.agents.filterNot {it.grant.agentId.startsWith("local-")}
    val current=agents.firstOrNull {it.grant.agentId==selected}
    BackHandler(current!=null) {selected=null}
    @Composable fun list() {
        OutlinedTextField(query,{query=it},label={Text("Find an agent")},singleLine=true,modifier=Modifier.fillMaxWidth())
        val filtered=agents.filter {botDisplayName(it,agents).contains(query,true)}
        if(filtered.isEmpty()) EmptyState(if(agents.isEmpty()) "Your agents belong here" else "No matching agents",
            if(agents.isEmpty()) "Pair a bot and give it access to the tools you choose." else "Try a different name.",R.drawable.ic_nav_agents)
        filtered.forEach {a->Card(onClick={selected=a.grant.agentId},modifier=Modifier.fillMaxWidth(),
            colors=CardDefaults.cardColors(containerColor=if(selected==a.grant.agentId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    IconBadge(R.drawable.ic_nav_agents)
                    Column(Modifier.weight(1f)) {
                        Text(botDisplayName(a,agents),style=MaterialTheme.typography.titleMedium)
                        Text("Identity …${a.grant.agentId.takeLast(6)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    StatusPill(agentState(a),if(agentState(a)=="Active") StatusTone.GOOD else StatusTone.NEUTRAL)
                    StatusPill(if(a.grant.fullControl) "Full control" else "Custom access",if(a.grant.fullControl) StatusTone.ATTENTION else StatusTone.NEUTRAL)
                }
                Text(state.recent.firstOrNull {it.agentId==a.grant.agentId}?.let {"Last request: ${actionStateLabel(it.state)}"} ?: "Paired · no recent request",style=MaterialTheme.typography.bodySmall)
            }
        }}
        Text("Paired identities retain their permissions. A paired identity is separate from a currently connected client.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    PageLayout {
        PageHeader("Agents","One place for your bots and their permissions.") {Button(onClick=onPair,enabled=!busy) {Text("Pair agent")}}
        if(wide) Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(.36f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {list()}
            Column(Modifier.weight(.64f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                current?.let {key(it.grant.agentId) {AgentDetail(it,state,onFamily,onShell,onReconnect,onRevoke,onRequest,onSemanticApp,onHeadlessApp,onSharedScreen,onFullControl,busy)}}
                    ?: EmptyState("Choose an agent","Review its tools, app access, connection, and recent work.",R.drawable.ic_nav_agents)
            }
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            if(current==null) list() else {
                TextButton(onClick={selected=null}) {Text("‹ All agents")}
                key(current.grant.agentId) {AgentDetail(current,state,onFamily,onShell,onReconnect,onRevoke,onRequest,onSemanticApp,onHeadlessApp,onSharedScreen,onFullControl,busy)}
            }
        }
    }
}

@Composable private fun AgentDetail(a:AgentRecord,state:ControlCenterSnapshot,onFamily:(String,String,Boolean)->Unit,
    onShell:(AgentRecord)->Unit,onReconnect:(AgentRecord)->Unit,onRevoke:(AgentRecord)->Unit,onRequest:(String)->Unit,onSemanticApp:(String,String,Boolean)->Unit,onHeadlessApp:(String,String,Boolean)->Unit,onSharedScreen:(String,Boolean)->Unit,
    onFullControl:(AgentRecord,Boolean)->Unit,busy:Boolean) {
    var tab by rememberSaveable(a.grant.agentId) {mutableStateOf("Permissions")}
    var query by rememberSaveable(a.grant.agentId) {mutableStateOf("")}
    var revoke by remember {mutableStateOf(false)}
    val usable=agentState(a)=="Active"
    val fullControl=a.grant.fullControl
    val editable=usable && !busy && !fullControl && a.fullAccessPreviousGrant==null
    Panel {
        Text(botDisplayName(a,state.agents),style=MaterialTheme.typography.headlineSmall)
        StatusPill(agentState(a),if(usable) StatusTone.GOOD else StatusTone.NEUTRAL)
        Text("Identity …${a.grant.agentId.takeLast(8)}",style=MaterialTheme.typography.bodySmall)
    }
    FullControlCard(a,botDisplayName(a,state.agents),usable,fullControl,busy,
        state.pending.filter {it.agentId==a.grant.agentId},state.capabilities.associate {it.descriptor.id to it.descriptor.title},onFullControl)
    if(!fullControl && a.fullAccessPreviousGrant!=null) Panel {
        Text("An earlier broad-access profile is saved. Enable Full control above to upgrade it, or restore your custom permissions before editing individual options.",style=MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick={onFullControl(a,false)},enabled=!busy) {Text("Restore custom access")}
    }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("Permissions","App access","Activity","Connection").forEach {name->FilterChip(tab==name,{tab=name},label={Text(name)})}
    }
    when(tab) {
        "Permissions" -> {
            if(fullControl) FullControlCoverage(a,busy,onFullControl)
            else {

            Text("Choose what this agent can do.",style=MaterialTheme.typography.titleMedium)
            Text("Android access and your agent’s allowance work together. Actions that need approval still ask you.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Panel {
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {Text("Shared-screen actions",style=MaterialTheme.typography.titleMedium);Text("Allow this agent to work on your visible screen.",style=MaterialTheme.typography.bodySmall)}
                    Switch(a.grant.allowSharedScreen,{onSharedScreen(a.grant.agentId,it)},enabled=editable)
                }
                if(!a.grant.allowSharedScreen) Text("Native background tools and permitted private displays remain available. Shared-screen launches and arbitrary shell are blocked.",style=MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(query,{query=it},label={Text("Find a permission")},singleLine=true,modifier=Modifier.fillMaxWidth())
            val groups=state.capabilities.filterNot {it.descriptor.ownerOnly}.groupBy {it.descriptor.id.substringBefore('.')}.toSortedMap()
                .filterKeys {it!="shell" && familyLabel(it).contains(query,true)}
            if(groups.isEmpty()) EmptyState("No matching permissions","Try a tool category such as calendar, apps, or files.")
            groups.forEach {(family,caps)->
                val count=caps.count {allowed(a,it.descriptor.id)}
                val ready=caps.count {allowed(a,it.descriptor.id) && it.availability.isExecutable &&
                    (a.grant.allowSharedScreen || it.descriptor.interaction in setOf(InteractionMode.BACKGROUND,InteractionMode.ISOLATED_SCREEN))}
                Panel {
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(familyLabel(family),style=MaterialTheme.typography.titleMedium)
                            Text("$count of ${caps.size} allowed · $ready ready",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(count==caps.size && usable,{onFamily(a.grant.agentId,family,it)},enabled=editable)
                    }
                    caps.filter {allowed(a,it.descriptor.id) && !it.availability.isExecutable}.take(3).forEach {
                        Text("${it.descriptor.title}: ${it.availability.name.lowercase().replace('_',' ')}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                    }
                }
            }
            ExpandablePanel("Privileged shell",if(a.grant.shellMode==ShellMode.BROAD) "Broad automatic access enabled" else "Commands follow your approval policy") {
                Text("Shell access can change apps, settings, and data. Review this permission separately from individual tool categories.")
                if(usable) OutlinedButton(onClick={onShell(a)},enabled=editable) {Text("Review shell permission")}
                Text("${a.grant.shellStandingCommands.size} standing shell commands",style=MaterialTheme.typography.bodySmall)
            }
            ExpandablePanel("Standing actions & deny rules","Detailed policy for this identity") {
                Text("${a.grant.standing.count {it.actionHash!=null}} exact standing actions",style=MaterialTheme.typography.titleSmall)
                a.grant.standing.take(20).forEach {Text("${it.capabilityId} · ${if(it.actionHash!=null) "Exact reviewed action" else "Selected allowance"}",style=MaterialTheme.typography.bodySmall)}
                Text("Explicit deny rules: ${a.grant.denied.joinToString().ifBlank {"None"}}",style=MaterialTheme.typography.bodySmall)
            }
                    }
        }
        "App access" -> {
            if(fullControl) {
                Panel {
                    Text("Full control covers supported apps",style=MaterialTheme.typography.titleMedium)
                    Text("This agent can use available screen, private-workspace, and shell tools across apps without adding separate app allowances or receiving per-action SIConnect approval prompts. Android permissions, fingerprint/PIN, app sign-in, and protected owner controls remain in place.",style=MaterialTheme.typography.bodyMedium)
                    Text("Switch to custom access to return to your previous per-app permissions.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick={onFullControl(a,false)},enabled=!busy) {Text("Switch to custom access")}
                }
            } else {

            Notice("Per-app allowances can authorize clicks, text entry, sends, or deletes without another prompt. Protected authentication and owner screens stay excluded.")
            val appAllowances=a.grant.standing.filter {it.capabilityId=="ui.click" && it.backend==BackendKind.ACCESSIBILITY && it.actionHash==null && it.constraints.keys==setOf("packageName")}
                .mapNotNull {(it.constraints["packageName"] as? kotlinx.serialization.json.JsonPrimitive)?.content}
            val privateAllowances=a.grant.standing.filter {it.capabilityId=="headless.open" && it.actionHash==null && it.constraints.keys==setOf("packageName")}
                .mapNotNull {rule->(rule.constraints["packageName"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let {it to rule.backend}}
            SectionHeading("Screen allowances")
            if(appAllowances.isEmpty()) Text("No apps have automatic screen access.",style=MaterialTheme.typography.bodyMedium)
            appAllowances.forEach {pkg->Panel {Text(pkg,style=MaterialTheme.typography.titleSmall);TextButton(onClick={onSemanticApp(a.grant.agentId,pkg,false)},enabled=editable) {Text("Remove screen access")}}}
            SectionHeading("Private workspaces")
            Text("Uses the app’s existing account on a separate display. Requires advanced control and an awake, unlocked phone.",style=MaterialTheme.typography.bodyMedium)
            if(privateAllowances.isEmpty()) Text("No apps have automatic private access.",style=MaterialTheme.typography.bodyMedium)
            privateAllowances.forEach {(pkg,backend)->Panel {Text(pkg,style=MaterialTheme.typography.titleSmall);Text("Backend: $backend",style=MaterialTheme.typography.bodySmall);TextButton(onClick={onHeadlessApp(a.grant.agentId,pkg,false)},enabled=editable) {Text("Remove private access")}}}
            var appPackage by rememberSaveable(a.grant.agentId) {mutableStateOf("")}
            var appLabel by rememberSaveable(a.grant.agentId) {mutableStateOf("")}
            var chooseApp by rememberSaveable(a.grant.agentId) {mutableStateOf(false)}
            var manual by rememberSaveable(a.grant.agentId) {mutableStateOf(false)}
            if(usable) Panel {
                Text("Add an app allowance",style=MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick={chooseApp=true},enabled=editable) {Text("Choose installed app")}
                if(appPackage.isNotBlank()) Text("${appLabel.ifBlank {appPackage}}\n$appPackage",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={manual=!manual},enabled=editable) {Text(if(manual) "Hide package entry" else "Enter package manually")}
                if(manual) OutlinedTextField(appPackage,{appPackage=it.take(200);appLabel=""},label={Text("Android package")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=editable)
                Button(onClick={onSemanticApp(a.grant.agentId,appPackage.trim(),true)},enabled=editable && appPackage.isNotBlank()) {Text("Allow screen actions")}
                OutlinedButton(onClick={onHeadlessApp(a.grant.agentId,appPackage.trim(),true)},enabled=editable && appPackage.isNotBlank()) {Text("Allow private actions")}
            }
            if(chooseApp) InstalledAppPicker(appAllowances.toSet(),onSelect={appPackage=it.packageName;appLabel="${it.label} · ${it.version}";chooseApp=false},onClose={chooseApp=false})
                    }
        }
        "Activity" -> {
            val recent=state.recent.filter {it.agentId==a.grant.agentId}
            if(recent.isEmpty()) EmptyState("No recent requests","This agent’s latest actions and results will appear here.")
            recent.take(20).forEach {r->NavigationCard(state.capabilities.firstOrNull {it.descriptor.id==r.capabilityId}?.descriptor?.title ?: r.capabilityId,
                actionStateLabel(r.state),{onRequest(r.requestId)},icon=R.drawable.ic_nav_activity)}
        }
        "Connection" -> {
            Panel {
                Text("Keep this identity",style=MaterialTheme.typography.titleMedium)
                Text("Reconnect the existing client to retain this agent’s permissions. Create a separate identity for a different bot.")
                Text("Grant expiry: ${a.grant.expiresAtMs?.let {java.util.Date(it).toString()} ?: "No expiry configured"}",style=MaterialTheme.typography.bodySmall)
                if(usable && a.grant.agentId.startsWith("bot_")) Button(onClick={onReconnect(a)},enabled=!busy) {Text("Reconnect this agent")}
                else if(usable) Text("This identity uses its saved private credential. Connection details are available from Home.",style=MaterialTheme.typography.bodySmall)
            }
            if(!a.grant.revoked) Panel {
                Text("Revoke access",style=MaterialTheme.typography.titleMedium)
                Text("Stop this identity from making further requests. You can pair a new identity later.",style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick={revoke=true},enabled=!busy) {Text("Revoke this agent",color=MaterialTheme.colorScheme.error)}
            }
        }
    }
    if(revoke) AlertDialog(onDismissRequest={revoke=false},title={Text("Revoke ${botDisplayName(a,state.agents)}?")},
        text={Text("This identity will no longer be allowed to request actions. Already running work must be reviewed separately in Activity.")},
        confirmButton={TextButton(onClick={revoke=false;onRevoke(a)},enabled=!busy) {Text("Revoke access")}},dismissButton={TextButton(onClick={revoke=false}) {Text("Keep agent")}})
}
/** The checked state comes only from the saved AgentRecord, never an optimistic local toggle. */
@Composable private fun FullControlCard(a:AgentRecord,label:String,usable:Boolean,enabled:Boolean,busy:Boolean,
    pending:List<RequestRecord>,capabilityTitles:Map<String,String>,onChange:(AgentRecord,Boolean)->Unit) {
    var review by rememberSaveable(a.grant.agentId) {mutableStateOf(false)}
    Card(colors=CardDefaults.cardColors(containerColor=if(enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Access mode",style=MaterialTheme.typography.labelLarge)
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("Full control",style=MaterialTheme.typography.titleLarge)
                    Text(if(enabled && usable) "On · saved with no expiry" else if(enabled) "Saved · agent ${agentState(a).lowercase()}" else "Off · using custom permissions",style=MaterialTheme.typography.bodySmall)
                }
                Switch(checked=enabled,onCheckedChange={next->if(next) review=true else onChange(a,false)},enabled=(!a.grant.revoked || enabled) && !busy,
                    modifier=Modifier.semantics {contentDescription="Full control for $label"})
            }
            Text(if(enabled && usable) "All SIConnect actions available to this agent run automatically, without per-action SIConnect approval. Full control stays enabled across restarts with no expiry until you turn it off or revoke the agent."
                else if(enabled) "This profile is saved, but the agent is currently ${agentState(a).lowercase()} and cannot run actions. Turning full control off restores its previous custom permissions without reactivating the agent."
                else "Authorize this agent once for automatic access to all available SIConnect tools, including screen control, private app workspaces, and arbitrary shell commands.",style=MaterialTheme.typography.bodyMedium)
            Text("Includes reading, changing, and deleting apps or data, sending messages, changing accounts, and arbitrary shell commands. Android fingerprint/PIN, permission and sign-in prompts, protected owner controls, and your remote pause control remain in place.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(enabled) Text("Turning this off restores the custom permissions saved before full control was enabled.",style=MaterialTheme.typography.bodySmall)
            if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Applying your change…",style=MaterialTheme.typography.bodySmall)}
        }
    }
    if(review) AlertDialog(onDismissRequest={if(!busy) review=false},title={Text("Give $label full control?")},
        text={Column(Modifier.heightIn(max=with(androidx.compose.ui.platform.LocalDensity.current) {androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()}*.55f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("This agent will be able to read, change, and delete apps or data, control the shared screen, use private app workspaces, and run arbitrary shell commands. SIConnect will not ask for per-action approval.")
            Text("This includes sending messages, deleting content, installing or removing apps, and changing settings. Grant this only to an agent you trust.")
            Text("Authenticate once on this phone. This choice has no expiry and remains saved until you disable it or revoke this agent. Turning it off restores its previous custom permissions and expiry. Android’s own fingerprint/PIN, permission and app sign-in prompts, and protected owner controls still apply.",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Waiting requests · ${pending.size}",style=MaterialTheme.typography.titleMedium)
            Text("Eligible requests already waiting for this agent will continue under the new profile. Expired or unavailable requests will not be restarted.",style=MaterialTheme.typography.bodyMedium)
            if(pending.isEmpty()) Text("No requests are currently waiting for this agent.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            pending.take(5).forEach {request->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(capabilityTitles[request.capabilityId] ?: request.capabilityId,style=MaterialTheme.typography.titleSmall)
                    request.assessment?.summary?.let {summary->
                        Text(summary,style=MaterialTheme.typography.bodySmall,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
            if(pending.size>5) Text("${pending.size-5} more waiting requests for this agent.",style=MaterialTheme.typography.bodySmall)

        }},confirmButton={Button(onClick={review=false;onChange(a,true)},enabled=!a.grant.revoked && !busy) {Text("Authenticate and enable")}},
        dismissButton={TextButton(onClick={review=false},enabled=!busy) {Text("Keep custom access")}})
}

@Composable private fun FullControlCoverage(a:AgentRecord,busy:Boolean,onChange:(AgentRecord,Boolean)->Unit) {
    Panel {
        Text("One saved permission profile",style=MaterialTheme.typography.titleMedium)
        listOf("All available capability families","Shared-screen and private app actions","Broad shell access","No per-action SIConnect approvals","No expiry until you disable access or revoke the agent").forEach {Text("• $it",style=MaterialTheme.typography.bodyMedium)}
        Text("Individual permission controls are managed in custom mode. Switching back restores the profile you had before enabling full control.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick={onChange(a,false)},enabled=!busy) {Text("Switch to custom access")}
    }
}
private fun agentState(a:AgentRecord)=when {a.grant.revoked->"Revoked";a.grant.expiresAtMs?.let {it<=System.currentTimeMillis()}==true->"Expired";else->"Active"}
private fun allowed(a:AgentRecord,id:String)=!a.grant.revoked && (a.grant.expiresAtMs?.let {it>System.currentTimeMillis()} ?: true) && a.grant.scopes.any {PolicyEngine.globMatches(it,id)} && a.grant.denied.none {PolicyEngine.globMatches(it,id)}
private fun familyLabel(f:String)=mapOf("device" to "Device diagnostics","apps" to "Apps","notifications" to "Notifications","messages" to "Messaging","calendar" to "Calendar","reminders" to "Reminders","weather" to "Weather","location" to "Phone location","commute" to "Maps commute","files" to "Managed files","documents" to "Selected documents","ui" to "UI interaction","headless" to "Private app workspaces","termux" to "Termux commands","requests" to "Request results","system" to "System commands","settings" to "System settings","alarms" to "Clock information")[f] ?: f.replaceFirstChar {it.uppercase()}
