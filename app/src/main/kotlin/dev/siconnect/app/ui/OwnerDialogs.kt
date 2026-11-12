package dev.siconnect.app.ui

import android.Manifest
import android.app.*
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.siconnect.app.*
import dev.siconnect.app.data.*
import dev.siconnect.app.automation.Automation
import dev.siconnect.app.transport.ConnectionService
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Shared owner review: one path for Home and detailed management. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun OwnerApprovalDialog(r: RequestRecord,busy:Boolean=false,error:String="",agentLabel:String=r.agentId,actionTitle:String=r.capabilityId,
    onClose:()->Unit,onDeny:()->Unit,onApprove:(Boolean,Boolean)->Unit) {
    val critical=r.assessment?.risk==RiskClass.CRITICAL
    val pending=r.state==RequestState.AWAITING_APPROVAL
    val deadline=r.approvalExpiresAtMs ?: r.expiresAtMs
    var expired by remember(r.requestId,deadline) {mutableStateOf(System.currentTimeMillis()>=deadline)}
    LaunchedEffect(r.requestId,deadline,pending) {
        expired=System.currentTimeMillis()>=deadline
        if(pending && !expired) {delay((deadline-System.currentTimeMillis()).coerceAtLeast(1));expired=true}
    }
    val canDecide=pending && !busy && !expired
    BasicAlertDialog(onDismissRequest={},properties=androidx.compose.ui.window.DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false)) {
        Surface(shape=RoundedCornerShape(28.dp),tonalElevation=6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max=with(androidx.compose.ui.platform.LocalDensity.current) {androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()}*0.85f).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(!pending) "Action ${r.state.name.lowercase().replace('_',' ')}" else "Allow this action?",style=MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(agentLabel,style=MaterialTheme.typography.titleSmall)
                    Text("Review stays open until you close it.",style=MaterialTheme.typography.bodySmall)
                    if(pending) Text("Expires at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(r.approvalExpiresAtMs ?: r.expiresAtMs))}")
                    Text(actionTitle,style=MaterialTheme.typography.titleMedium)
                    if(actionTitle!=r.capabilityId) Text(r.capabilityId,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(r.payloadExpired) Text("Payload expired under your retention setting. The original outcome and retry identity are retained.")
                    else {
                        Text(r.assessment?.shellCommand ?: r.arguments.toString())
                        Text("Risk: ${r.assessment?.risk}\n${r.assessment?.sideEffects?.joinToString() ?: ""}")
                    }
                    Text("Backend: ${r.selectedBackend ?: "selected at execution"}",style=MaterialTheme.typography.bodySmall)
                    if(pending) Text("Always allow saves this exact action and backend. Broader shell access is managed separately under Agents. Completion is verified by the capability where supported; external effects may not be reversible.",style=MaterialTheme.typography.bodySmall)
                    if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Saving your decision and checking current permissions…")}
                    if(expired && pending) Text("This request expired. Ask the bot to submit a new request.",color=MaterialTheme.colorScheme.error)
                    r.errorMessage?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
                }
                Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(canDecide) {
                        Button(onClick={onApprove(false,false)},modifier=Modifier.fillMaxWidth()) {Text("Allow once")}
                        if(!critical) OutlinedButton(onClick={onApprove(true,false)},modifier=Modifier.fillMaxWidth()) {Text("Always allow this exact action")}
                        TextButton(onClick=onDeny) {Text("Deny",color=MaterialTheme.colorScheme.error)}
                    }
                    TextButton(onClick=onClose,enabled=!busy) {Text("Close")}
                }
            }
        }
    }
}

@Composable internal fun ShellPermissionDialog(label: String,busy:Boolean=false,allowed:Boolean=false,error:String="",onClose:()->Unit,onReduce:()->Unit,onAllow:()->Unit) {
    AlertDialog(onDismissRequest={},properties=androidx.compose.ui.window.DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false),title={Text(if(allowed) "Arbitrary shell is always allowed" else "Always allow arbitrary shell?")},
        text={Column(Modifier.heightIn(max=with(androidx.compose.ui.platform.LocalDensity.current) {androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()}*0.5f).verticalScroll(rememberScrollState())) {Text("$label will be allowed to run arbitrary shell commands and read its request results without asking each time. This can change apps, settings and data. Critical operations still require approval.\n\nThis choice is saved on your phone across app restarts. Emergency stop and agent revocation still apply. You can switch back to asking each time under Agents.")
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={if(allowed) Button(onClick=onReduce,enabled=!busy) {Text("Ask before arbitrary shell")} else Button(onClick=onAllow,enabled=!busy) {Text(if(busy) "Saving…" else "Always allow")}},dismissButton={TextButton(onClick=onClose,enabled=!busy) {Text("Close")}})
}
