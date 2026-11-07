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
import androidx.compose.ui.platform.LocalLocale

@Composable internal fun MainActivity.StorageSettings() {
    val locale=LocalLocale.current.platformLocale
    val scope = rememberCoroutineScope()
    var days by remember { mutableStateOf<Int?>(null) }
    var policyLoaded by remember {mutableStateOf(false)}
    var proposedDays by rememberSaveable { mutableStateOf<Int?>(null) }
    var summary by remember { mutableStateOf<StorageSummary?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var loadError by remember {mutableStateOf<String?>(null)}
    var loading by remember {mutableStateOf(true)}
    var revision by remember { mutableIntStateOf(0) }
    val accessRevision=permissionRevision
    LaunchedEffect(revision,accessRevision) {
        loading=true;loadError=null
        try {
            val loaded=withContext(Dispatchers.IO) {graph.ready.await();graph.settings.executionRetention to graph.retention.summary()}
            days=loaded.first.days;summary=loaded.second;policyLoaded=true
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {loadError="Storage information could not be loaded. Saved retention settings have not been changed."}
        finally {loading=false}
    }
    fun applyPolicy(policy: RetentionPolicy) {
        if(busy) return
        busy = true;message=""
        scope.launch {
            try {
                withContext(Dispatchers.IO) { graph.ready.await(); graph.retention.configure(policy) }
                days = policy.days;policyLoaded=true; proposedDays = null
                message = if (days == null) "Automatic expiry stopped. Already expired payloads cannot be restored." else "Execution payloads will expire after $days days."
                revision++
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Could not save retention. Try again." }
            finally { busy = false }
        }
    }
    Panel {
            Text("Execution history", style = MaterialTheme.typography.titleMedium)
            if(policyLoaded) {
                StatusPill(if(days==null) "Automatic expiry off" else "Expire payloads after $days days",if(days==null) StatusTone.NEUTRAL else StatusTone.GOOD)
                Text(if (days == null) "Keep execution payloads until you choose an expiry period." else "Expire completed execution payloads after $days days.",style=MaterialTheme.typography.bodyMedium)
            }
            if(loading) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(if(policyLoaded) "Refreshing storage information…" else "Loading saved retention settings…",style=MaterialTheme.typography.bodySmall)}
            loadError?.let {Notice(if(policyLoaded) "$it Showing the last loaded information." else it,true)}
            Text("Active work, uncertain outcomes, reminder and automation settings stay available. Compact outcomes and retry identities are kept to prevent repeated actions.", style = MaterialTheme.typography.bodySmall)
            Text("Completed Termux command output follows the same expiry period. Late results for uncertain commands stay available.",style=MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = policyLoaded && days == null, onClick = { applyPolicy(RetentionPolicy()) }, enabled = policyLoaded && !loading && !busy && days != null, label = { Text("Keep payloads") })
                RetentionPolicy.OPTIONS.forEach { option ->
                    FilterChip(selected = policyLoaded && days == option, onClick = { proposedDays = option }, enabled = policyLoaded && !loading && !busy && days != option, label = { Text("$option days") })
                }
            }
            summary?.let { s ->
                fun mb(bytes: Long) = String.format(locale, "%.1f", bytes / 1_048_576.0)
                HorizontalDivider()
                Text("Database storage",style=MaterialTheme.typography.titleSmall)
                Text("${mb(s.allocatedBytes)} MB allocated",style=MaterialTheme.typography.headlineSmall)
                if(s.allocatedBytes>0) LinearProgressIndicator(progress={((s.allocatedBytes-s.reusableBytes).toDouble()/s.allocatedBytes).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                Text("${mb(s.reusableBytes)} MB can be reused within the database. Expiring records does not immediately shrink the database file.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${s.fullRequests} full requests · ${s.compactRequests} compact outcomes",style=MaterialTheme.typography.bodySmall)
                Text("${s.fullWorkflows} full workflows · ${s.compactWorkflows} compact workflows",style=MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick={revision++},enabled=!busy && !loading) {Text(if(loadError!=null) "Retry storage details" else "Refresh storage details")}
            if (policyLoaded && days != null) OutlinedButton(enabled = !busy && !loading, onClick = {
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
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Notice(message)
    }
    proposedDays?.let { selected ->
        AlertDialog(onDismissRequest = { if (!busy) proposedDays = null }, title = { Text("Expire payloads after $selected days?") },
            text = { Text("Old completed requests and workflows lose their arguments, saved definitions and result bodies. Their outcomes and retry identities remain. Expired payloads cannot be restored. Cleanup runs in small batches while SIConnect is running.") },
            confirmButton = { TextButton(enabled = policyLoaded && !busy && !loading, onClick = {
                ownerAuthentication.authenticate("Change execution history retention", { applyPolicy(RetentionPolicy(selected)) }, { message = it })
            }) { Text("Enable expiry") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { proposedDays = null }) { Text("Cancel") } })
    }
}
