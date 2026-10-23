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
    nativeReady:Boolean,privilegedReady:Boolean,controls:DeviceControls,
    measurements:JsonObject?,busy:Boolean,note:String,onReview:(RequestRecord)->Unit,onRequest:(String)->Unit,
    onAgent:(String)->Unit,onAgents:()->Unit,onConnect:()->Unit,onRestore:()->Unit,onAutomations:()->Unit,
    onActivity:()->Unit,onHealth:()->Unit,onStop:()->Unit,onResume:()->Unit,
    onInbox:()->Unit,onSetup:()->Unit) {
    val remote=state.agents.filterNot {it.grant.agentId.startsWith("local-")}
    val labels=state.agents.associate {it.grant.agentId to botDisplayName(it,state.agents)}
    val status=when { !controls.remoteExecutionEnabled->"New remote actions blocked";!nativeReady->"Starting…";connection==ConnectionState.ONLINE->"Connected";else->"Native features ready"}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("SIConnect",style=MaterialTheme.typography.headlineLarge)
        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(status,style=MaterialTheme.typography.headlineSmall)
            Text(if(!controls.remoteExecutionEnabled) "New remote actions and pending remote approvals are blocked. Active work continues until cancelled; local schedules stay enabled." else if(connection==ConnectionState.ONLINE) "Your phone can receive authorized agent requests." else detail)
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text("Native features: ${if(nativeReady) "ready" else "starting"}",Modifier.weight(1f))
                TextButton(onClick=onSetup) {Text("Access")}
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text("Device control: ${if(privilegedReady) "available" else "optional setup needed"}",Modifier.weight(1f))
                TextButton(onClick=onRestore) {Text(if(privilegedReady) "Manage" else "Set up")}
            }
            state.recent.firstOrNull {r->r.state==RequestState.SUCCEEDED && remote.any {it.grant.agentId==r.agentId}}?.let {
                Text("Last successful bot action: ${time(it.updatedAtMs)}",style=MaterialTheme.typography.labelMedium)
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onConnect) {Text("Gateway")}
                OutlinedButton(onClick=onAgents) {Text("Connect AI agent")}
            }
            if(controls.remoteExecutionEnabled) OutlinedButton(onClick=onStop) {Text("Block new remote actions",color=MaterialTheme.colorScheme.error)}
            else Button(onClick=onResume) {Text("Allow new remote actions")}
            if(state.activeRequests.isNotEmpty()) TextButton(onClick=onActivity) {Text("Review active requests (${state.activeRequests.size}${if(state.activeRequests.size==50) "+" else ""})")}
        }}
        val missingAccess=state.capabilities.filter {it.descriptor.id in setOf("ui.inspect","notifications.actions") && !it.availability.isExecutable}
        if(missingAccess.isNotEmpty()) OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("More native access",style=MaterialTheme.typography.titleMedium)
            Text("Screen or notification actions need attention. Other ready tools remain available.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=onSetup) {Text("Review Android access")}
        }}
        if(note.isNotBlank()) Text(note,color=MaterialTheme.colorScheme.primary)
        state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        @Composable fun attention() {
            Text("Action Inbox",style=MaterialTheme.typography.titleLarge)
            val proposed=state.proposals.filter {it.state in setOf(ProposalState.PROPOSED,ProposalState.VIEWED)}
            Text(if(state.pendingProposalCount==0) "No follow-ups waiting for review." else "${state.pendingProposalCount} follow-ups waiting for review. Nothing executes until you accept.")
            proposed.take(3).forEach {p->OutlinedCard(onClick=onInbox,modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp)) {Text(p.title,style=MaterialTheme.typography.titleMedium);Text("Agent …${p.agentId.takeLast(8)} · ${p.risk}",style=MaterialTheme.typography.bodySmall)}}}
            OutlinedButton(onClick=onInbox) {Text("Review Action Inbox")}
            if(state.pending.isNotEmpty()) Text("Approval requests",style=MaterialTheme.typography.titleLarge)
            state.pending.take(10).forEach {r->Card(Modifier.fillMaxWidth().clickable {onReview(r)}) {Column(Modifier.padding(16.dp)) {
                Text(labels[r.agentId] ?: "Agent …${r.agentId.takeLast(6)}",style=MaterialTheme.typography.titleMedium)
                Text(r.assessment?.summary ?: r.capabilityId)
                Text("${r.assessment?.risk} · expires ${time(r.approvalExpiresAtMs ?: r.expiresAtMs)}",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={onReview(r)}) {Text("Review exact action")}
            }}}
            state.automations.filter {it.scheduleState in setOf("FAILED","BLOCKED","UNKNOWN_OUTCOME","MISSED")}.take(5).forEach {a->
                OutlinedButton(onClick=onAutomations) {Text("${a.id}: ${a.scheduleState}")}
            }
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
