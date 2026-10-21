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

/** Owner resolution is intentionally absent from the remote registry. No agent text is rendered as a control. */
@Composable fun ActionInbox(g:RuntimeGraph,revision:Int,authenticate:(String,()->Unit,(String)->Unit)->Unit,
    onClose:()->Unit,onRequest:(String)->Unit) {
    val scope=rememberCoroutineScope()
    var selected by rememberSaveable {mutableStateOf<String?>(null)}
    var p by remember {mutableStateOf<ActionProposal?>(null)}
    var rows by remember {mutableStateOf(emptyList<ActionProposal>())}
    var error by remember {mutableStateOf("")};var busy by remember {mutableStateOf(false)}
    var page by remember {mutableIntStateOf(0)}
    var cursor by remember {mutableStateOf<Pair<Long,String>?>(null)}
    fun work(block:suspend ()->Unit) {scope.launch {busy=true;try {withContext(Dispatchers.IO) {g.ready.await();block()};page++} catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "Could not update proposal"} finally {busy=false}}}
    LaunchedEffect(selected,revision,page,cursor) {
        try {withContext(Dispatchers.IO) {
            if(selected!=null) p=g.proposals.reviewFromOwner(requireNotNull(selected))
            else rows=g.proposals.list(limit=20,beforeMs=cursor?.first,beforeId=cursor?.second)
        }} catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "Could not load proposals"}
    }
    AlertDialog(onDismissRequest=onClose,title={Text(if(selected==null) "Action Inbox" else "Review proposed action")},
        text={Column(Modifier.fillMaxWidth().heightIn(max=540.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Agent proposals are suggestions. Accepting submits the exact action under that agent's current permissions. A separate execution approval may still be required.",style=MaterialTheme.typography.bodySmall)
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(selected==null) {
                if(rows.isEmpty()) Text("No proposals on this page.")
                rows.forEach {row->OutlinedCard(onClick={selected=row.proposalId;error=""},modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(row.title,style=MaterialTheme.typography.titleMedium)
                        Text("Agent …${row.agentId.takeLast(8)} · ${proposalStateLabel(row)}")
                        Text(row.summary,maxLines=3)
                    }
                }}
                if(rows.size==20) TextButton(onClick={rows.lastOrNull()?.let {cursor=it.createdAtMs to it.proposalId}}) {Text("Older proposals")}
                if(cursor!=null) TextButton(onClick={cursor=null}) {Text("Newest proposals")}
            } else p?.let {proposal->
                Text(proposal.title,style=MaterialTheme.typography.titleLarge)
                Text("Agent …${proposal.agentId.takeLast(8)} · ${proposalStateLabel(proposal)}")
                Text("Agent summary: ${proposal.summary}")
                Text("Agent reason: ${proposal.reason}")
                Text("Source (${proposal.sourceType}): ${proposal.sourceReference ?: "No reference supplied"}",style=MaterialTheme.typography.bodySmall)
                Text("Risk: ${proposal.assessment.risk} · Target: ${proposal.assessment.target ?: proposal.capabilityId}")
                Text("Created ${java.util.Date(proposal.createdAtMs)}\nExpires ${java.util.Date(proposal.expiresAtMs)}",style=MaterialTheme.typography.bodySmall)
                Text("Exact proposed action",style=MaterialTheme.typography.titleMedium)
                Text("${proposal.capabilityId} · version ${proposal.capabilityVersion}")
                Text(proposal.arguments.toString(),style=MaterialTheme.typography.bodySmall)
                proposal.errorCode?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                proposal.verification?.let {Text("Execution verification: ${it.name.replace('_',' ')}",style=MaterialTheme.typography.bodySmall)}
                if(proposal.state in setOf(ProposalState.PROPOSED,ProposalState.VIEWED)) {
                    Button(enabled=!busy,onClick={
                        authenticate("Accept proposed ${proposal.capabilityId}",{
                            if(selected==proposal.proposalId) work {
                                val accepted=g.proposals.acceptFromOwner(proposal.proposalId,proposal.revision)
                                // Runtime scope survives this screen closing; persistent link recovers ordinary process death.
                                g.scope.launch {g.proposals.dispatchAccepted(accepted.proposalId)}
                            }
                        },{error=it})
                    }) {Text("Accept proposal")}
                    OutlinedButton(enabled=!busy,onClick={work {g.proposals.dismissFromOwner(proposal.proposalId)}}) {Text("Dismiss")}
                }
                proposal.linkedRequestId?.let {id->Button(onClick={onRequest(id)}) {Text(if(proposal.requestState==RequestState.AWAITING_APPROVAL) "Review execution approval" else "Open linked execution")}}
                TextButton(onClick={selected=null;p=null;error=""}) {Text("Back to inbox")}
            }
        }},confirmButton={TextButton(onClick=onClose) {Text("Close")}})
}

internal fun proposalStateLabel(p:ActionProposal)=if(p.requestState==RequestState.AWAITING_APPROVAL) "Waiting for execution approval" else p.state.name.lowercase().replace('_',' ').replaceFirstChar {it.uppercase()}
