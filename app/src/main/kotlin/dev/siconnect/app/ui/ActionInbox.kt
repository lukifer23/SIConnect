package dev.siconnect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.core.*
import kotlinx.coroutines.*

/** Remote text remains display data. Only fresh owner authentication can accept an action. */
@Composable fun ActionInbox(g:RuntimeGraph,revision:Int,authenticate:(String,()->Unit,(String)->Unit)->Unit,
    onClose:()->Unit,onRequest:(String)->Unit) {
    val scope=rememberCoroutineScope()
    var selected by rememberSaveable {mutableStateOf<String?>(null)}
    var p by remember {mutableStateOf<ActionProposal?>(null)}
    var readiness by remember {mutableStateOf<ProposalReadiness?>(null)}
    var rows by remember {mutableStateOf(emptyList<ProposalSummary>())}
    var labels by remember {mutableStateOf(emptyMap<String,String>())}
    var error by remember {mutableStateOf("")};var busy by remember {mutableStateOf(false)}
    var loading by remember {mutableStateOf(true)}
    var page by remember {mutableIntStateOf(0)}
    var cursorMs by rememberSaveable {mutableStateOf<Long?>(null)}
    var cursorId by rememberSaveable {mutableStateOf<String?>(null)}
    var oldest by rememberSaveable {mutableStateOf(false)}
    var filter by rememberSaveable {mutableStateOf("All")}
    var agent by rememberSaveable {mutableStateOf<String?>(null)}
    var advanced by rememberSaveable(selected) {mutableStateOf(false)}
    fun reset() {cursorMs=null;cursorId=null}
    fun work(block:suspend ()->Unit) {
        if(busy) return
        busy=true;error=""
        scope.launch {try {withContext(Dispatchers.IO) {g.ready.await();block()};page++}
            catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "Could not update proposal"} finally {busy=false}}
    }
    LaunchedEffect(selected,revision,page,cursorMs,cursorId,oldest,filter,agent) {
        loading=true;p=null;readiness=null
        if(selected==null) rows=emptyList()
        try {
            val loaded=withContext(Dispatchers.IO) {
                g.ready.await()
                val bots=g.agents.all();val names=bots.associate {it.grant.agentId to botDisplayName(it,bots)}
                if(selected!=null) {
                    val proposal=g.proposals.reviewFromOwner(requireNotNull(selected))
                    Triple(names,proposal,g.proposals.readinessFromOwner(proposal.proposalId))
                } else {
                    val states=when(filter) {
                        "Pending"->setOf(ProposalState.PROPOSED,ProposalState.VIEWED)
                        "In progress"->setOf(ProposalState.ACCEPTED,ProposalState.EXECUTING)
                        "Completed"->setOf(ProposalState.COMPLETED)
                        "Problems"->setOf(ProposalState.FAILED,ProposalState.UNKNOWN_OUTCOME)
                        "Closed"->setOf(ProposalState.DISMISSED,ProposalState.EXPIRED)
                        else->null
                    }
                    rows=g.proposals.list(agent,20,cursorMs,cursorId,oldest,states)
                    Triple(names,null,null)
                }
            }
            labels=loaded.first;p=loaded.second;readiness=loaded.third
        } catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "Could not load proposals"} finally {loading=false}
    }
    // A single deadline refresh while this review is open, not a polling loop.
    LaunchedEffect(p?.proposalId,p?.expiresAtMs,p?.state) {
        p?.takeIf {it.state in setOf(ProposalState.PROPOSED,ProposalState.VIEWED)}?.let {
            delay((it.expiresAtMs-System.currentTimeMillis()).coerceAtLeast(1));page++
        }
    }
    fun name(id:String)="${labels[id] ?: "Agent"} · …${id.takeLast(8)}"
    AlertDialog(onDismissRequest=onClose,title={Text(if(selected==null) "Action Inbox" else "Review proposed action")},
        text={Column(Modifier.fillMaxWidth().heightIn(max=540.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Proposals are suggestions. Acceptance submits the exact action under the original agent's current permissions and does not grant more access.",style=MaterialTheme.typography.bodySmall)
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            if(busy || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(selected==null) {
                var filtersOpen by remember {mutableStateOf(false)}
                Box {OutlinedButton(onClick={filtersOpen=true}) {Text("Status: $filter")};DropdownMenu(expanded=filtersOpen,onDismissRequest={filtersOpen=false}) {
                    listOf("All","Pending","In progress","Completed","Problems","Closed").forEach {value->DropdownMenuItem(text={Text(value)},onClick={filter=value;reset();filtersOpen=false})}
                }}
                var agentsOpen by remember {mutableStateOf(false)}
                Box {OutlinedButton(onClick={agentsOpen=true}) {Text(agent?.let(::name) ?: "All agents")};DropdownMenu(expanded=agentsOpen,onDismissRequest={agentsOpen=false}) {
                    DropdownMenuItem(text={Text("All agents")},onClick={agent=null;reset();agentsOpen=false})
                    labels.forEach {(id,_)->DropdownMenuItem(text={Text(name(id))},onClick={agent=id;reset();agentsOpen=false})}
                }}
                TextButton(onClick={oldest=!oldest;reset()}) {Text(if(oldest) "Oldest first" else "Newest first")}
                if(!loading && rows.isEmpty()) Text("No proposals match this page.")
                rows.forEach {row->OutlinedCard(onClick={selected=row.proposalId;error=""},modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(row.title,style=MaterialTheme.typography.titleMedium)
                        Text("${name(row.agentId)} · ${proposalStateLabel(row.state,row.requestState)}")
                        Text("${row.risk.name.replace('_',' ')} · ${java.util.Date(row.createdAtMs)}",style=MaterialTheme.typography.bodySmall)
                        Text(row.summary,maxLines=3)
                    }
                }}
                if(rows.size==20) TextButton(onClick={rows.lastOrNull()?.let {cursorMs=it.createdAtMs;cursorId=it.proposalId}}) {Text("Next page")}
                if(cursorId!=null) TextButton(onClick={reset()}) {Text("First page")}
            } else p?.let {proposal->
                Text(proposal.title,style=MaterialTheme.typography.titleLarge)
                Text("${name(proposal.agentId)} · ${proposalStateLabel(proposal.state,proposal.requestState)}")
                Text("Suggested action: ${proposal.summary}")
                Text("Reason supplied by agent: ${proposal.reason}")
                Text("Source (${proposal.sourceType}): ${proposal.sourceReference ?: "No reference supplied"}",style=MaterialTheme.typography.bodySmall)
                Text("Risk: ${proposal.assessment.risk} · Target: ${proposal.assessment.target ?: proposal.capabilityId}")
                Text("Created ${java.util.Date(proposal.createdAtMs)}\nExpires ${java.util.Date(proposal.expiresAtMs)}",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={advanced=!advanced}) {Text(if(advanced) "Hide exact arguments" else "Show exact action and arguments")}
                if(advanced) {Text("${proposal.capabilityId} · version ${proposal.capabilityVersion}");Text(proposal.arguments.toString(),style=MaterialTheme.typography.bodySmall)}
                proposal.errorCode?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                proposal.verification?.let {Text("Execution verification: ${it.name.replace('_',' ')}")}
                if(proposal.state==ProposalState.UNKNOWN_OUTCOME) Text("The action may have happened. Inspect the original execution before taking further action.")
                if(proposal.state in setOf(ProposalState.PROPOSED,ProposalState.VIEWED)) {
                    readiness?.let {r->Text(when(r.outcome) {
                        PolicyOutcome.ALLOW->"Currently allowed. Acceptance will submit this action."
                        PolicyOutcome.REQUIRE_APPROVAL->"A separate execution approval will be required after acceptance."
                        else->"Cannot submit now: ${r.reason}"
                    })}
                    Button(enabled=!busy && readiness?.outcome in setOf(PolicyOutcome.ALLOW,PolicyOutcome.REQUIRE_APPROVAL),onClick={
                        authenticate("Accept proposed ${proposal.capabilityId}",{
                            if(selected==proposal.proposalId) work {
                                val accepted=g.proposals.acceptFromOwner(proposal.proposalId,proposal.revision)
                                g.scope.launch {
                                    try {g.proposals.dispatchAccepted(accepted.proposalId)}
                                    catch(e:CancellationException) {throw e}
                                    catch(e:Exception) {android.util.Log.e("SIConnect","Accepted proposal dispatch deferred: ${e.javaClass.simpleName}")}
                                }
                            }
                        },{error=it})
                    }) {Text("Accept proposal")}
                    OutlinedButton(enabled=!busy,onClick={work {g.proposals.dismissFromOwner(proposal.proposalId)}}) {Text("Dismiss")}
                }
                if(proposal.errorCode=="DISPATCH_NOT_RECORDED") Text("No execution record was created. Recovery will retry the same request identity; no new action is allocated.")
                if(proposal.state==ProposalState.ACCEPTED && proposal.requestState==null) Text("Accepted; waiting for an execution record. Recovery keeps the same request identity and stops at the execution deadline.")
                proposal.linkedRequestId?.let {id->Button(enabled=proposal.requestState!=null,onClick={onRequest(id)}) {Text(if(proposal.requestState==RequestState.AWAITING_APPROVAL) "Review execution approval" else "Open linked execution")}}
                TextButton(onClick={selected=null;p=null;error=""}) {Text("Back to inbox")}
            }
        }},confirmButton={TextButton(onClick=onClose) {Text("Close")}})
}

internal fun proposalStateLabel(state:ProposalState,request:RequestState?)=if(request==RequestState.AWAITING_APPROVAL) "Waiting for execution approval" else state.name.lowercase().replace('_',' ').replaceFirstChar {it.uppercase()}
