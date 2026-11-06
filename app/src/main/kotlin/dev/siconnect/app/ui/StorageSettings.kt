package dev.siconnect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siconnect.app.data.*
import dev.siconnect.app.graph
import kotlinx.coroutines.*
import java.util.Locale

@Composable internal fun MainActivity.StorageSettings() {
    val scope = rememberCoroutineScope()
    var days by remember { mutableStateOf(graph.settings.executionRetention.days) }
    var proposedDays by rememberSaveable { mutableStateOf<Int?>(null) }
    var summary by remember { mutableStateOf<StorageSummary?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision) {
        try { summary = withContext(Dispatchers.IO) { graph.ready.await(); graph.retention.summary() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Storage information is unavailable. Try again." }
    }
    fun applyPolicy(policy: RetentionPolicy) {
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { graph.ready.await(); graph.retention.configure(policy) }
                days = policy.days; proposedDays = null
                message = if (days == null) "Automatic expiry stopped. Already expired payloads cannot be restored." else "Execution payloads will expire after $days days."
                revision++
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Could not save retention. Try again." }
            finally { busy = false }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Execution history", style = MaterialTheme.typography.titleMedium)
            Text(if (days == null) "Keep execution payloads until you choose an expiry period." else "Expire completed execution payloads after $days days.")
            Text("Active work, uncertain outcomes, reminder and automation settings stay available. Compact outcomes and retry identities are kept to prevent repeated actions.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = days == null, onClick = { applyPolicy(RetentionPolicy()) }, enabled = !busy && days != null, label = { Text("Keep payloads") })
                RetentionPolicy.OPTIONS.forEach { option ->
                    FilterChip(selected = days == option, onClick = { proposedDays = option }, enabled = !busy && days != option, label = { Text("$option days") })
                }
            }
            summary?.let { s ->
                fun mb(bytes: Long) = String.format(Locale.getDefault(), "%.1f", bytes / 1_048_576.0)
                Text("${mb(s.allocatedBytes)} MB database · ${mb(s.reusableBytes)} MB reusable", style = MaterialTheme.typography.bodySmall)
                Text("${s.fullRequests} full requests · ${s.compactRequests} compact outcomes · ${s.fullWorkflows} full workflows", style = MaterialTheme.typography.bodySmall)
            }
            if (days != null) OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { graph.ready.await(); graph.retention.maintain() }
                        message = "Expired ${result.total} payload records. More eligible history is processed automatically."
                        revision++
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "Cleanup could not finish. Existing outcomes remain available; try again." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Working…" else "Clean up one batch") }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
    proposedDays?.let { selected ->
        AlertDialog(onDismissRequest = { if (!busy) proposedDays = null }, title = { Text("Expire payloads after $selected days?") },
            text = { Text("Old completed requests and workflows lose their arguments, saved definitions and result bodies. Their outcomes and retry identities remain. Expired payloads cannot be restored. Cleanup runs in small batches while SIConnect is running.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                ownerAuthentication.authenticate("Change execution history retention", { applyPolicy(RetentionPolicy(selected)) }, { message = it })
            }) { Text("Enable expiry") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { proposedDays = null }) { Text("Cancel") } })
    }
}
