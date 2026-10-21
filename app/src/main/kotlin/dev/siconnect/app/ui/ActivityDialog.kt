package dev.siconnect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import dev.siconnect.core.RequestState

/** Bounded metadata only. Full arguments/results are read only after an explicit selection. */
@Composable internal fun ActivityDialog(store:SqlRequestStore,revision:Int,labels:Map<String,String>,title:(String)->String,onClose:()->Unit,onOpen:(String)->Unit) {
    var filter by rememberSaveable { mutableStateOf("All") }
    var selectedAgent by rememberSaveable { mutableStateOf<String?>(null) }
    var newest by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    val attention=setOf(RequestState.DENIED,RequestState.AWAITING_APPROVAL,RequestState.FAILED,RequestState.EXPIRED,RequestState.UNKNOWN_OUTCOME)
    var page by remember {mutableStateOf(emptyList<RequestSummary>())}
    var cursors by remember {mutableStateOf(listOf<RequestSummary?>(null))}
    var more by remember {mutableStateOf(false)}
    var loading by remember {mutableStateOf(true)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(filter,selectedAgent,newest) {cursors=listOf(null)}
    LaunchedEffect(filter,selectedAgent,newest,cursors,revision) {
        loading=true;error=null
        try {
            val states=when(filter) {"Needs attention"->attention;"Completed"->setOf(RequestState.SUCCEEDED);else->null}
            val loaded=withContext(Dispatchers.IO) {store.historyPage(101,cursors.last(),selectedAgent,states,newest)}
            more=loaded.size>100;page=loaded.take(100)
        } catch(e:kotlinx.coroutines.CancellationException) {throw e} catch(e:Exception) {error="Could not load history: ${e.javaClass.simpleName}"} finally {loading=false}
    }
    val bodyHeight=(LocalConfiguration.current.screenHeightDp * 0.62f).dp
    AlertDialog(onDismissRequest=onClose,title={Text("Activity")},text={Column(Modifier.fillMaxWidth().heightIn(max=bodyHeight),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Page ${cursors.size} · ${page.size} requests. Tap an action for its result and audit trail.",style=MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("All","Needs attention","Completed").forEach { name -> FilterChip(selected=filter==name,onClick={filter=name},label={Text(name)}) }
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
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(!loading && page.isEmpty()) Text("No requests match this view.")
        LazyColumn(Modifier.weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            items(page,key={it.requestId}) { r ->
                Column(Modifier.fillMaxWidth().clickable {onOpen(r.requestId)}.padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(title(r.capabilityId),style=MaterialTheme.typography.titleSmall)
                    Text("${labels[r.agentId] ?: "Unknown bot"} · ${actionStateLabel(r.state)}",style=MaterialTheme.typography.bodySmall)
                    Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.MEDIUM).format(java.util.Date(r.updatedAtMs)),style=MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={cursors=cursors.dropLast(1)},enabled=!loading && cursors.size>1) {Text("Previous page")}
            TextButton(onClick={page.lastOrNull()?.let {cursors=cursors+it}},enabled=!loading && more) {Text("Next page")}
        }
    }},confirmButton={TextButton(onClick=onClose) {Text("Close")}})
}

internal fun actionStateLabel(state:RequestState):String = when(state) {
    RequestState.SUCCEEDED -> "Completed"
    RequestState.DENIED -> "Blocked"
    RequestState.AWAITING_APPROVAL -> "Needs approval"
    RequestState.UNKNOWN_OUTCOME -> "Check outcome"
    RequestState.EXECUTING,RequestState.VERIFYING -> "Running"
    else -> state.name.lowercase().replace('_',' ').replaceFirstChar {it.uppercase()}
}
