package dev.siconnect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dev.siconnect.app.data.RequestSummary
import dev.siconnect.app.data.SqlRequestStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.conflate
import dev.siconnect.core.RequestState
import dev.siconnect.core.RequestRecord
import dev.siconnect.core.Event
import kotlinx.serialization.json.*

/** Shared bounded history browser for full-screen and modal entry points. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ActivityContent(store:SqlRequestStore,revision:Int,labels:Map<String,String>,title:(String)->String,modifier:Modifier,onOpen:(String)->Unit) {
    var filter by rememberSaveable { mutableStateOf("All") }
    var selectedAgent by rememberSaveable { mutableStateOf<String?>(null) }
    var newest by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var search by rememberSaveable {mutableStateOf("")}
    val searchTerm=search.trim()
    var query by remember {mutableStateOf(searchTerm)}
    // Only typing is debounced. Live request updates must not keep restarting a
    // search delay and starve the actual read while an agent is busy.
    LaunchedEffect(searchTerm) {if(searchTerm.isNotEmpty()) kotlinx.coroutines.delay(250);query=searchTerm}
    val searching=query!=searchTerm
    val attention=setOf(RequestState.DENIED,RequestState.AWAITING_APPROVAL,RequestState.FAILED,RequestState.EXPIRED,RequestState.UNKNOWN_OUTCOME)
    var page by remember(filter,selectedAgent,newest,query) {mutableStateOf(emptyList<RequestSummary>())}
    var cursors by remember(filter,selectedAgent,newest,query) {mutableStateOf(listOf<RequestSummary?>(null))}
    var more by remember {mutableStateOf(false)}
    var loading by remember {mutableStateOf(true)}
    var error by remember {mutableStateOf<String?>(null)}
    var retry by remember {mutableIntStateOf(0)}
    val currentRevision by rememberUpdatedState(revision)
    LaunchedEffect(filter,selectedAgent,newest,query,cursors,retry,labels) {
      snapshotFlow {currentRevision}.conflate().collect {
        loading=true;error=null
        try {
            val states=when(filter) {"Needs attention"->attention;"Completed"->setOf(RequestState.SUCCEEDED);"Running"->setOf(RequestState.EXECUTING,RequestState.VERIFYING);else->null}
            val matchingAgents=labels.filterValues {it.contains(query,true)}.keys
            // Titles and friendly outcome labels are display metadata; resolve them to
            // exact IDs/states while SQLite applies the search before its page limit.
            val matchingCapabilities=if(query.isEmpty()) emptySet() else withContext(Dispatchers.IO) {store.historyCapabilityIds()}.filter {title(it).contains(query,true)}.toSet()
            val matchingStates=RequestState.entries.filter {actionStateLabel(it).contains(query,true)}.toSet()
            val loaded=withContext(Dispatchers.IO) {store.historyPage(101,cursors.last(),selectedAgent,states,newest,query,matchingCapabilities,matchingAgents,
                matchingStates)}
            more=loaded.size>100;page=loaded.take(100)
        } catch(e:kotlinx.coroutines.CancellationException) {throw e} catch(e:Exception) {page=emptyList();more=false;error="Could not load activity. Your saved records are unchanged."} finally {loading=false}
      }
    }
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Every request, its outcome, and what happened next.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(search,{search=it.take(256)},label={Text("Search all activity")},supportingText={Text("Action, agent, outcome, or request ID")},singleLine=true,modifier=Modifier.fillMaxWidth(),trailingIcon={if(search.isNotEmpty()) TextButton(onClick={search=""}) {Text("Clear")}})
        FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("All","Running","Needs attention","Completed").forEach { name -> FilterChip(selected=filter==name,onClick={filter=name},label={Text(name)}) }
        }
        Row {
            Box(Modifier.weight(1f)) {
                TextButton(onClick={menu=true}) {Text(selectedAgent?.let {labels[it] ?: "Unknown bot"} ?: "Every bot")}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    DropdownMenuItem(text={Text("Every bot")},onClick={selectedAgent=null;menu=false})
                    labels.keys.sortedBy {labels[it] ?: it}.forEach { id ->
                        DropdownMenuItem(text={Text(labels[id] ?: "Unknown bot")},onClick={selectedAgent=id;menu=false})
                    }
                }
            }
            TextButton(onClick={newest=!newest}) {Text(if(newest) "Newest first" else "Oldest first")}
        }
        if(loading || searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {Notice(it,true);TextButton(onClick={retry++},enabled=!loading) {Text("Retry loading activity")}}
        val visible=if(searching) emptyList() else page
        if(!loading && !searching && error==null && visible.isEmpty()) EmptyState("No matching activity","Try a different search or filter.")
        LazyColumn(Modifier.weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            items(visible,key={it.requestId}) { r ->
                Card(onClick={onOpen(r.requestId)},modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(title(r.capabilityId),style=MaterialTheme.typography.titleMedium)
                        Text(labels[r.agentId] ?: "Unknown agent",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        StatusPill(actionStateLabel(r.state),requestTone(r.state))
                        if(r.payloadExpired) Text("Payload expired · outcome retained",style=MaterialTheme.typography.labelSmall)
                        Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.MEDIUM).format(java.util.Date(r.updatedAtMs)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text(if(searching) "Searching all activity…" else "Page ${cursors.size} · ${visible.size} of ${page.size} requests",style=MaterialTheme.typography.labelSmall)
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={cursors=cursors.dropLast(1)},enabled=!loading && !searching && error==null && cursors.size>1) {Text("Previous page")}
            TextButton(onClick={page.lastOrNull()?.let {cursors=cursors+it}},enabled=!loading && !searching && error==null && more) {Text("Next page")}
        }
    }
}

@Composable internal fun ActivityScreen(store:SqlRequestStore,revision:Int,labels:Map<String,String>,title:(String)->String,
    detail:@Composable ()->Unit={},onOpen:(String)->Unit) {
    PageLayout {
        PageHeader("Activity","A clear record of your agents’ work.")
        if(LocalExpandedLayout.current) Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
            ActivityContent(store,revision,labels,title,Modifier.weight(.46f).fillMaxHeight(),onOpen)
            Column(Modifier.weight(.54f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(12.dp)) {detail()}
        } else ActivityContent(store,revision,labels,title,Modifier.weight(1f),onOpen)
    }
}

@Composable internal fun ActivityDialog(store:SqlRequestStore,revision:Int,labels:Map<String,String>,title:(String)->String,onClose:()->Unit,onOpen:(String)->Unit) {
    val bodyHeight=with(androidx.compose.ui.platform.LocalDensity.current) {androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()}*0.62f
    AlertDialog(onDismissRequest=onClose,title={Text("Activity")},text={
        ActivityContent(store,revision,labels,title,Modifier.heightIn(max=bodyHeight),onOpen)
    },confirmButton={TextButton(onClick=onClose) {Text("Close")}})
}

internal fun actionStateLabel(state:RequestState):String = when(state) {
    RequestState.SUCCEEDED -> "Completed"
    RequestState.DENIED -> "Blocked"
    RequestState.AWAITING_APPROVAL -> "Needs approval"
    RequestState.UNKNOWN_OUTCOME -> "Check outcome"
    RequestState.EXECUTING,RequestState.VERIFYING -> "Running"
    else -> state.name.lowercase().replace('_',' ').replaceFirstChar {it.uppercase()}
}


@Composable internal fun RequestDetails(record:RequestRecord,agentLabel:String,events:List<Event>,canReviewPermissions:Boolean,
    busy:Boolean,onPermissions:()->Unit,onCancel:()->Unit,privateApp:String?=null,onAllowPrivateApp:()->Unit={}) {
    val r=record
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        StatusPill(actionStateLabel(r.state),requestTone(r.state))
        Text(agentLabel,style=MaterialTheme.typography.titleMedium)
        r.policy?.let {Text(it.reason,style=MaterialTheme.typography.bodyMedium)}
        if(privateApp!=null) Panel {
            Text("Private app access needed",style=MaterialTheme.typography.titleMedium)
            Text("Allow $agentLabel to open and interact with $privateApp on a private display. This includes clicks and text entry, which can send or delete within that app. Other apps’ allowances stay as configured.",style=MaterialTheme.typography.bodyMedium)
            Button(onClick=onAllowPrivateApp,enabled=!busy) {Text("Set up private app access")}
        }
        if(canReviewPermissions) OutlinedButton(onClick=onPermissions) {Text("Review agent permissions")}
        r.errorMessage?.let {Notice(it,true)}
        if(events.any {it.durationMs!=null} || r.state.isTerminal) Panel {
            Text("Timing",style=MaterialTheme.typography.titleMedium)
            if(r.state.isTerminal) Text("Saved request lifecycle: ${(r.updatedAtMs-r.createdAtMs).coerceAtLeast(0)} ms",style=MaterialTheme.typography.bodyMedium)
            events.firstOrNull {it.eventType in setOf("execution.finished","execution.interrupted","request.failed") && it.durationMs!=null}?.durationMs?.let {
                Text("Execution: $it ms",style=MaterialTheme.typography.bodyMedium)
            }
            events.firstOrNull {it.eventType in setOf("transport.result_queued","transport.result_queue_rejected")}?.let {event->event.durationMs?.let {
                Text(if(event.eventType=="transport.result_queued") "Phone receipt to response queued: $it ms" else "Phone receipt to response queue rejection: $it ms",style=MaterialTheme.typography.bodyMedium)
            }
            }
            Text("Phone timings exclude time before arrival and after the response enters the socket queue. Remote receipt and agent processing are not measured here.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        r.assessment?.shellCommand?.let {Panel {Text("Command",style=MaterialTheme.typography.titleSmall);Text(it,style=MaterialTheme.typography.bodyMedium)}}
        r.result?.let {result->
            Panel {
                Text("Result",style=MaterialTheme.typography.titleMedium)
                val fields=(result.output as? JsonObject)?.entries?.take(12)
                if(fields!=null) fields.forEach {(key,value)->
                    Text(key.replace(Regex("([a-z])([A-Z])"),"$1 $2").replace('_',' ').replaceFirstChar {it.uppercase()},style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(when(value) {is JsonPrimitive->value.content.take(300);is JsonArray->"${value.size} items";is JsonObject->"${value.size} fields";else->value.toString().take(300)},style=MaterialTheme.typography.bodyMedium)
                } else Text(result.output.toString().take(1000),style=MaterialTheme.typography.bodyMedium)
            }
            ExpandablePanel("Raw result","Inspect the original structured response") {
                val raw=result.output.toString()
                androidx.compose.foundation.text.selection.SelectionContainer {Text(raw.take(12000),style=MaterialTheme.typography.bodySmall)}
                if(raw.length>12000) Text("Preview limited to 12,000 characters. The full result remains in the request record.",style=MaterialTheme.typography.bodySmall)
            }
        }
        if(!r.state.isTerminal) {
            Notice("Cancellation asks running work to stop. A side effect may already have happened; review the outcome before retrying.")
            OutlinedButton(onClick=onCancel,enabled=!busy) {Text("Cancel this request")}
        }
        ExpandablePanel("Request details","Identity and execution backend") {
            Text("Request",style=MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.text.selection.SelectionContainer {Text(r.requestId,style=MaterialTheme.typography.bodySmall)}
            Text("Backend: ${r.result?.backend ?: r.selectedBackend ?: "Not executed"}",style=MaterialTheme.typography.bodySmall)
        }
        ExpandablePanel("Audit trail","${events.size} retained events") {
            events.reversed().forEach {Text("${it.eventType} · ${it.policyDecision ?: it.executionStatus ?: it.errorCode ?: ""}",style=MaterialTheme.typography.bodySmall)}
        }
        Spacer(Modifier.height(12.dp))
    }
}
