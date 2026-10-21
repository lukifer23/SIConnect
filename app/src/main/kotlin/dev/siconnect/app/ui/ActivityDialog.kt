package dev.siconnect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siconnect.app.data.RequestSummary
import dev.siconnect.core.RequestState

/** Bounded metadata only. Full arguments/results are read only after an explicit selection. */
@Composable internal fun ActivityDialog(records:List<RequestSummary>,labels:Map<String,String>,title:(String)->String,onClose:()->Unit,onOpen:(String)->Unit) {
    var filter by rememberSaveable { mutableStateOf("All") }
    var selectedAgent by rememberSaveable { mutableStateOf<String?>(null) }
    var newest by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    val attention=setOf(RequestState.DENIED,RequestState.AWAITING_APPROVAL,RequestState.FAILED,RequestState.EXPIRED,RequestState.UNKNOWN_OUTCOME)
    val visible=records.filter { (selectedAgent==null || it.agentId==selectedAgent) && when(filter) { "Needs attention" -> it.state in attention; "Completed" -> it.state==RequestState.SUCCEEDED; else -> true } }
        .let { if(newest) it.sortedWith(compareByDescending<RequestSummary> {r -> r.updatedAtMs}.thenByDescending {r -> r.requestId}) else it.sortedWith(compareBy<RequestSummary> {r -> r.updatedAtMs}.thenBy {r -> r.requestId}) }
    AlertDialog(onDismissRequest=onClose,title={Text("Activity")},text={Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Latest ${records.size} requests. Tap an action for its result and audit trail.",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("All","Needs attention","Completed").forEach { name -> FilterChip(selected=filter==name,onClick={filter=name},label={Text(name)}) }
        }
        Row {
            Box(Modifier.weight(1f)) {
                TextButton(onClick={menu=true}) {Text(selectedAgent?.let {labels[it] ?: "Unknown bot"} ?: "Every bot")}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    DropdownMenuItem(text={Text("Every bot")},onClick={selectedAgent=null;menu=false})
                    records.map {it.agentId}.distinct().sortedBy {labels[it] ?: it}.forEach { id ->
                        DropdownMenuItem(text={Text(labels[id] ?: "Unknown bot")},onClick={selectedAgent=id;menu=false})
                    }
                }
            }
            TextButton(onClick={newest=!newest}) {Text(if(newest) "Newest first" else "Oldest first")}
        }
        if(visible.isEmpty()) Text("No requests match this view.")
        LazyColumn(Modifier.heightIn(max=440.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            items(visible,key={it.requestId}) { r ->
                Column(Modifier.fillMaxWidth().clickable {onOpen(r.requestId)}.padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(title(r.capabilityId),style=MaterialTheme.typography.titleSmall)
                    Text("${labels[r.agentId] ?: "Unknown bot"} · ${actionStateLabel(r.state)}",style=MaterialTheme.typography.bodySmall)
                    Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.MEDIUM).format(java.util.Date(r.updatedAtMs)),style=MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
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
