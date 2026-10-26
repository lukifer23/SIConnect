package dev.siconnect.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.data.*
import dev.siconnect.app.automation.Automation
import dev.siconnect.app.capabilities.Reminder
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ControlCenterSnapshot(
    val revision:Int=0,val agents:List<AgentRecord> = emptyList(),
    val recent:List<RequestSummary> = emptyList(),val pending:List<RequestRecord> = emptyList(),
    val reminders:List<Reminder> = emptyList(),val automations:List<Automation> = emptyList(),
    val capabilities:List<CapabilityStatus> = emptyList(),val error:String?=null,
    val semanticEnabledInSettings:Boolean=false,
    val proposals:List<ProposalSummary> = emptyList(),val pendingProposalCount:Int=0,
    val activeRequests:List<RequestSummary> = emptyList(),
)

/** One retained metadata projection. No shell output is loaded to render overview screens. */
@OptIn(FlowPreview::class)
class ControlCenterState(private val g:RuntimeGraph):ViewModel() {
    private val refreshes=MutableSharedFlow<Unit>(extraBufferCapacity=1)
    private val mutable=MutableStateFlow(ControlCenterSnapshot())
    val state:StateFlow<ControlCenterSnapshot> = mutable.asStateFlow()
    init {viewModelScope.launch(Dispatchers.IO) {
        g.ready.await()
        mutable.subscriptionCount.map {it>0}.distinctUntilChanged().collectLatest {visible ->
        if(!visible) return@collectLatest
        merge(refreshes,g.engine.recordUpdates.map {Unit},g.runner.runUpdates.map {Unit},g.proposals.updates,
            discoveryUpdates(g.gateway.state),discoveryUpdates(g.privileged.directStatus),discoveryUpdates(g.shizuku.status),
            discoveryUpdates(dev.siconnect.app.accessibility.SemanticAccessibilityService.connectionState),
            discoveryUpdates(dev.siconnect.app.notifications.NotificationCaptureService.connectionState))
            .onStart {g.registry.invalidateDiscovery();emit(Unit)}.debounce(100).collect {
                try {mutable.value=ControlCenterSnapshot(mutable.value.revision+1,g.agents.all(),g.requests.recentSummaries(50),
                    g.requests.listByStates(setOf(RequestState.AWAITING_APPROVAL)),
                    g.reminders.all().filter {it.state in setOf("SCHEDULED","BLOCKED","FIRING","AWAITING_APPROVAL","CANCELLING")}.sortedBy {it.fireAtMs},
                    g.scheduler.all(),g.registry.statuses(maxAgeMs=5_000),semanticEnabledInSettings=android.provider.Settings.Secure.getString(g.context.contentResolver,android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                        .orEmpty().split(':').any {android.content.ComponentName.unflattenFromString(it)==android.content.ComponentName(g.context,dev.siconnect.app.accessibility.SemanticAccessibilityService::class.java)},
                    proposals=g.proposals.list(limit=3,states=setOf(ProposalState.PROPOSED,ProposalState.VIEWED)),pendingProposalCount=g.proposals.pendingCount(),
                    activeRequests=g.requests.historyPage(limit=50,states=setOf(RequestState.EXECUTING,RequestState.VERIFYING))) }
                catch(e:CancellationException) {throw e}
                catch(e:Exception) {mutable.value=mutable.value.copy(error="Could not refresh: ${e.javaClass.simpleName}")}
            }
        }
    }}
    private fun discoveryUpdates(flow: Flow<*>) = flow.map {g.registry.invalidateDiscovery();Unit}
    fun refresh() {g.registry.invalidateDiscovery();refreshes.tryEmit(Unit)}
}

internal fun botDisplayName(bot:AgentRecord,bots:List<AgentRecord>):String {
    val label=if(bot.grant.label=="ADB development client") "Personal bot" else bot.grant.label
    return if(bots.count {it.grant.label==bot.grant.label}>1) "$label · ${bot.grant.agentId.takeLast(6)}" else label
}
