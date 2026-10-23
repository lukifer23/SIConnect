package dev.siconnect.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siconnect.app.ui.*
import dev.siconnect.app.transport.ConnectionState
import dev.siconnect.core.*
import kotlinx.serialization.json.JsonObject

@Composable fun HomeDashboard(state:ControlCenterSnapshot,wide:Boolean,connection:ConnectionState,detail:String,
    nativeReady:Boolean,privilegedReady:Boolean,backend:String,identity:String,controls:DeviceControls,
    measurements:JsonObject?,busy:Boolean,note:String,onReview:(RequestRecord)->Unit,onRequest:(String)->Unit,
    onAgent:(String)->Unit,onAgents:()->Unit,onConnect:()->Unit,onRestore:()->Unit,onAutomations:()->Unit,
    onActivity:()->Unit,onHealth:()->Unit,onStop:()->Unit,onResume:()->Unit,
    onEnableSemantic:()->Unit,onEnableNotifications:()->Unit,onInbox:()->Unit,onSetup:()->Unit) {
    val remote=state.agents.filterNot {it.grant.agentId.startsWith("local-")}
    val labels=state.agents.associate {it.grant.agentId to botDisplayName(it,state.agents)}
    val status=when { !controls.remoteExecutionEnabled->"Remote control paused";connection!=ConnectionState.ONLINE->"Disconnected";!nativeReady || !privilegedReady->"Limited";else->"Ready"}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("SIConnect",style=MaterialTheme.typography.headlineLarge)
        val semantic=state.capabilities.firstOrNull {it.descriptor.id=="ui.inspect"}
        if(semantic!=null && !semantic.availability.isExecutable) SetupAttentionCard(
            if(state.semanticEnabledInSettings) "Restore semantic screen control" else "Enable semantic screen control",
            if(state.semanticEnabledInSettings) "Android access is enabled, but the service is disconnected. Open Accessibility settings, turn SIConnect off and on, then return." else "Let your authorized agents find buttons, read the current screen and enter text directly. Enable SIConnect in Android Accessibility settings.",
            if(state.semanticEnabledInSettings) "Restore screen access" else "Enable screen access",onEnableSemantic)
        val notification=state.capabilities.firstOrNull {it.descriptor.id=="notifications.actions"}
        if(notification!=null && !notification.availability.isExecutable) SetupAttentionCard(
            "Connect notification access",
            "Review notification metadata and use supported app actions. Android needs your notification-listener permission.",
            "Enable notification access",onEnableNotifications)
        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(status,style=MaterialTheme.typography.headlineMedium)
            Text("Gateway: ${connection.name.lowercase().replace('_',' ')} · Native: ${if(nativeReady) "ready" else "starting"}")
            Text("Full device control: ${if(privilegedReady) "ready" else "setup required"} · $backend")
            if(privilegedReady) Text(identity.lineSequence().firstOrNull().orEmpty().substringBefore(" groups="),style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(detail,style=MaterialTheme.typography.bodySmall)
            state.recent.firstOrNull {r->r.state==RequestState.SUCCEEDED && remote.any {it.grant.agentId==r.agentId}}?.let {
                Text("Last successful bot action: ${time(it.updatedAtMs)}",style=MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onConnect) {Text("Connection")}
                if(!privilegedReady) TextButton(onClick=onRestore) {Text("Restore control")}
            }
            if(controls.remoteExecutionEnabled) OutlinedButton(onClick=onStop) {Text("Emergency stop",color=MaterialTheme.colorScheme.error)}
            else Button(onClick=onResume) {Text("Resume remote requests")}
        }}
        TextButton(onClick=onSetup) {Text("Setup and readiness")}
        if(note.isNotBlank()) Text(note,color=MaterialTheme.colorScheme.primary)
        state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        @Composable fun attention() {
            Text("Action Inbox",style=MaterialTheme.typography.titleLarge)
            val proposed=state.proposals.filter {it.state in setOf(ProposalState.PROPOSED,ProposalState.VIEWED)}
            Text(if(state.pendingProposalCount==0) "No follow-ups waiting for review." else "${state.pendingProposalCount} follow-ups waiting for review. Nothing executes until you accept.")
            proposed.take(3).forEach {p->OutlinedCard(onClick=onInbox,modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp)) {Text(p.title,style=MaterialTheme.typography.titleMedium);Text("Agent …${p.agentId.takeLast(8)} · ${p.risk}",style=MaterialTheme.typography.bodySmall)}}}
            OutlinedButton(onClick=onInbox) {Text("Review Action Inbox")}
            Text("Needs attention",style=MaterialTheme.typography.titleLarge)
            if(state.pending.isEmpty() && privilegedReady && connection==ConnectionState.ONLINE) Text("No pending approvals or connection problems.")
            state.pending.take(10).forEach {r->Card(Modifier.fillMaxWidth().clickable {onReview(r)}) {Column(Modifier.padding(16.dp)) {
                Text(labels[r.agentId] ?: "Agent …${r.agentId.takeLast(6)}",style=MaterialTheme.typography.titleMedium)
                Text(r.assessment?.summary ?: r.capabilityId)
                Text("${r.assessment?.risk} · expires ${time(r.approvalExpiresAtMs ?: r.expiresAtMs)}",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={onReview(r)}) {Text("Review exact action")}
            }}}
            state.automations.filter {it.scheduleState in setOf("FAILED","BLOCKED","UNKNOWN_OUTCOME","MISSED")}.take(5).forEach {a->
                OutlinedButton(onClick=onAutomations) {Text("${a.id}: ${a.scheduleState}")}
            }
            if(!privilegedReady) OutlinedButton(onClick=onRestore) {Text("Full control needs authorized setup")}
            if(connection!=ConnectionState.ONLINE) OutlinedButton(onClick=onConnect) {Text("Review connection")}
            Text("Agents",style=MaterialTheme.typography.titleLarge)
            remote.filterNot {it.grant.revoked}.take(4).forEach {a->OutlinedCard(onClick={onAgent(a.grant.agentId)},modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {Text(labels[a.grant.agentId] ?: a.grant.label);Text(state.recent.firstOrNull {it.agentId==a.grant.agentId}?.let {"Last action ${time(it.updatedAtMs)} · ${actionStateLabel(it.state)}"} ?: "Paired · no recent action",style=MaterialTheme.typography.bodySmall)}
            }}
            TextButton(onClick=onAgents) {Text("Manage agents and permissions")}
        }
        @Composable fun upcoming() {
            Text("Upcoming",style=MaterialTheme.typography.titleLarge)
            if(state.reminders.isEmpty() && state.automations.none {it.enabled}) Text("No upcoming reminders or automations.")
            state.reminders.take(4).forEach {r->OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp)) {Text(r.title);Text("${time(r.fireAtMs)} · ${r.state}",style=MaterialTheme.typography.bodySmall)}}}
            state.automations.filter {it.enabled}.sortedBy {it.nextAtMs}.take(4).forEach {a->TextButton(onClick=onAutomations) {Text("${a.id} · ${time(a.nextAtMs)} · ${a.scheduleState}")}}
            Button(onClick=onAutomations) {Text("Create or manage automation")}
            Text("Recent activity",style=MaterialTheme.typography.titleLarge)
            state.recent.take(5).forEach {r->Column(Modifier.fillMaxWidth().clickable {onRequest(r.requestId)}.padding(vertical=8.dp)) {
                Text(r.capabilityId,style=MaterialTheme.typography.titleSmall);Text("${labels[r.agentId] ?: "Local workflow"} · ${actionStateLabel(r.state)} · ${time(r.updatedAtMs)}",style=MaterialTheme.typography.bodySmall)
            }}
            TextButton(onClick=onActivity) {Text("All activity and results")}
            RuntimeHealthCard(measurements,busy,onHealth)
        }
        if(wide) Row(horizontalArrangement=Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {attention()}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {upcoming()}
        } else {attention();upcoming()}
    }
}
private fun time(ms:Long)=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(ms))

@Composable private fun SetupAttentionCard(title:String,explanation:String,action:String,onEnable:()->Unit) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(title,style=MaterialTheme.typography.titleLarge)
            Text(explanation,style=MaterialTheme.typography.bodyMedium)
            Button(onClick=onEnable,modifier=Modifier.fillMaxWidth()) {Text(action)}
        }
    }
}
