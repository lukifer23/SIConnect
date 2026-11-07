package dev.siconnect.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siconnect.app.R
import dev.siconnect.app.ui.*
import dev.siconnect.app.transport.ConnectionState
import dev.siconnect.core.*
import kotlinx.serialization.json.JsonObject

@Composable fun HomeDashboard(state:ControlCenterSnapshot,wide:Boolean,connection:ConnectionState,detail:String,
    nativeReady:Boolean,privilegedReady:Boolean,controls:DeviceControls,
    measurements:JsonObject?,busy:Boolean,onReview:(RequestRecord)->Unit,onRequest:(String)->Unit,
    onAgent:(String)->Unit,onAgents:()->Unit,onConnect:()->Unit,onRestore:()->Unit,onAutomations:()->Unit,
    onActivity:()->Unit,onHealth:()->Unit,onStop:()->Unit,onResume:()->Unit,
    onInbox:()->Unit,onSetup:()->Unit,onReminder:(String)->Unit) {
    val remote=state.agents.filterNot {it.grant.agentId.startsWith("local-")}
    val active=remote.filterNot {it.grant.revoked}
    val labels=state.agents.associate {it.grant.agentId to botDisplayName(it,state.agents)}
    val titles=state.capabilities.associate {it.descriptor.id to it.descriptor.title}
    val online=connection==ConnectionState.ONLINE
    val paused=!controls.remoteExecutionEnabled
    PageLayout {
        PageHeader(if(wide) "Overview" else "Your phone, connected.",if(wide) "Your phone, on your terms." else "Manage your agents, review their work, and stay in control.","SIConnect")
        @Composable fun connectionPanel() {
            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(if(wide) 20.dp else 24.dp),verticalArrangement=Arrangement.spacedBy(if(wide) 12.dp else 16.dp)) {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        StatusPill(if(paused) "Actions paused" else if(online) "Gateway connected" else "Gateway offline",if(paused) StatusTone.ATTENTION else if(online) StatusTone.GOOD else StatusTone.NEUTRAL)
                        if(state.activeRequests.isNotEmpty()) StatusPill("${state.activeRequests.size} running")
                    }
                    Text(when {paused->"You’re in control.";!nativeReady->"Getting things ready…";online->"Ready when you are.";else->"Your local tools are ready."},style=MaterialTheme.typography.headlineMedium)
                    Text(if(paused) "New remote actions are paused. Local schedules and work already running continue." else if(online) if(wide) "Your agents can reach this phone securely." else "Your agents can securely reach this phone with the permissions you’ve chosen." else detail,
                        style=MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Button(onClick=onConnect) {Text(if(online) "Connection details" else "Connect gateway")}
                        TextButton(onClick=if(paused) onResume else onStop) {Text(if(paused) "Resume agent actions" else "Pause agent actions")}
                    }
                }
            }
        }
        @Composable fun accessPanel() {
            Panel {
                SectionHeading("Phone readiness")
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {Text("Android access",style=MaterialTheme.typography.titleSmall);Text(if(nativeReady) "Native features ready" else "Starting…",style=MaterialTheme.typography.bodySmall)}
                    TextButton(onClick=onSetup) {Text("Review")}
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {Text("Device control",style=MaterialTheme.typography.titleSmall);Text(if(privilegedReady) "Available" else "Optional setup",style=MaterialTheme.typography.bodySmall)}
                    TextButton(onClick=onRestore) {Text(if(privilegedReady) "Manage" else "Set up")}
                }
                if(state.capabilities.any {it.descriptor.id in setOf("ui.inspect","notifications.actions") && !it.availability.isExecutable}) Text("Screen or notification tools need attention in Android access.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)
            }
        }
            @Composable fun review() {
                SectionHeading("Needs your attention",if(state.pendingProposalCount>0) "Open inbox" else null,onInbox)
                if(state.pending.isEmpty() && state.pendingProposalCount==0) {
                    if(wide) Panel {StatusPill("All caught up",StatusTone.GOOD);Text("No approvals or suggestions waiting.",style=MaterialTheme.typography.bodySmall)}
                    else EmptyState("You’re all caught up","Requests that need approval and agent suggestions will appear here.",R.drawable.ic_nav_activity)
                }
                if(state.pendingProposalCount>0) NavigationCard("${state.pendingProposalCount} agent suggestions","Review proposed follow-ups. They wait for you to accept.",onInbox,icon=R.drawable.ic_nav_activity)
                state.pending.take(5).forEach {r->Panel {
                    StatusPill("Approval needed",StatusTone.ATTENTION)
                    Text(labels[r.agentId] ?: "Agent",style=MaterialTheme.typography.titleMedium)
                    Text(r.assessment?.summary ?: titles[r.capabilityId] ?: r.capabilityId,style=MaterialTheme.typography.bodyMedium)
                    Text("Expires ${time(r.approvalExpiresAtMs ?: r.expiresAtMs)}",style=MaterialTheme.typography.bodySmall)
                    Button(onClick={onReview(r)}) {Text("Review action")}
                }}
                state.automations.filter {it.scheduleState in setOf("FAILED","BLOCKED","UNKNOWN_OUTCOME","MISSED")}.take(3).forEach {a->
                    NavigationCard(a.label.ifBlank {"Automation needs attention"},a.scheduleState.lowercase().replace('_',' '),onAutomations,icon=R.drawable.ic_nav_automations)
                }
        }
        @Composable fun agentsPanel() {
                SectionHeading("Your agents · ${active.size}","Manage",onAgents)
                if(active.isEmpty()) EmptyState("Connect your first agent","Pair an AI agent, then choose what it can do on this phone.",R.drawable.ic_nav_agents,"Pair an agent",onAgents)
                active.take(3).forEach {a->NavigationCard(labels[a.grant.agentId] ?: a.grant.label,
                    state.recent.firstOrNull {it.agentId==a.grant.agentId}?.let {"${actionStateLabel(it.state)} · ${time(it.updatedAtMs)}"} ?: "Paired · waiting for its first request",
                    {onAgent(a.grant.agentId)},icon=R.drawable.ic_nav_agents)}
            }
            @Composable fun updates() {
                SectionHeading("Coming up","Automations",onAutomations)
                if(state.reminders.isEmpty() && state.automations.none {it.enabled}) EmptyState("Make room for a routine","Schedule a reminder, health check, or commute forecast.",R.drawable.ic_nav_automations,"Create automation",onAutomations)
                state.reminders.take(3).forEach {r->NavigationCard(r.title,time(r.fireAtMs),{onReminder(r.id)},icon=R.drawable.ic_nav_automations)}
                state.automations.filter {it.enabled}.sortedBy {it.nextAtMs}.take(3).forEach {a->NavigationCard(a.label.ifBlank {"Scheduled automation"},time(a.nextAtMs),onAutomations,icon=R.drawable.ic_nav_automations)}
        }
        @Composable fun recentPanel() {
                SectionHeading("Recent activity","View all",onActivity)
                if(state.recent.isEmpty()) EmptyState("A clear view of every action","Completed work and results will appear here.")
                state.recent.take(4).forEach {r->Card(onClick={onRequest(r.requestId)},modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(titles[r.capabilityId] ?: r.capabilityId,style=MaterialTheme.typography.titleMedium)
                        Text("${labels[r.agentId] ?: "Local workflow"} · ${time(r.updatedAtMs)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        StatusPill(actionStateLabel(r.state),requestTone(r.state))
                    }
                }}
            }
        state.error?.let {Notice(it,true)}
        if(wide) Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(.44f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                connectionPanel();accessPanel();updates()
                ExpandablePanel("Device health","On-demand runtime diagnostics") {RuntimeHealthCard(measurements,busy,onHealth)}
                Spacer(Modifier.height(8.dp))
            }
            Column(Modifier.weight(.56f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                review();agentsPanel();recentPanel();Spacer(Modifier.height(8.dp))
            }
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            connectionPanel();accessPanel();review();agentsPanel();updates();recentPanel()
            ExpandablePanel("Device health","On-demand runtime diagnostics") {RuntimeHealthCard(measurements,busy,onHealth)}
            Spacer(Modifier.height(8.dp))
        }
    }
}
private fun time(ms:Long)=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(ms))
