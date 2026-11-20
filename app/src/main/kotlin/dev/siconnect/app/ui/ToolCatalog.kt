package dev.siconnect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siconnect.core.BackendKind
import dev.siconnect.core.CapabilityStatus
import dev.siconnect.core.InteractionMode

private enum class CatalogReadiness(val label: String) {
    ALL("Any availability"), READY("Ready to use"), UNAVAILABLE("Unavailable");

    fun matches(ready: Boolean) = this == ALL || (this == READY) == ready
}

private enum class DisplayFilter(val label: String) {
    ALL("Any display use"), BACKGROUND("Background"), PRIVATE("Private display"), SHARED("Shared screen");

    fun matches(mode: InteractionMode) = when (this) {
        ALL -> true
        BACKGROUND -> mode == InteractionMode.BACKGROUND
        PRIVATE -> mode == InteractionMode.ISOLATED_SCREEN
        SHARED -> mode == InteractionMode.SCREEN || mode == InteractionMode.MAY_OPEN_SCREEN
    }
}

private fun InteractionMode.label() = when (this) {
    InteractionMode.BACKGROUND -> "Background"
    InteractionMode.SCREEN -> "Uses shared screen"
    InteractionMode.MAY_OPEN_SCREEN -> "May open shared screen"
    InteractionMode.ISOLATED_SCREEN -> "Private display"
    InteractionMode.UNSPECIFIED -> "Display use unspecified"
}

private val familyTitles = mapOf(
    "ui" to "Shared-screen interaction", "headless" to "Private app workspaces", "shell" to "Privileged shell",
    "device" to "Device diagnostics", "documents" to "Selected documents", "files" to "Managed files",
    "termux" to "Termux background commands", "system" to "System operations", "requests" to "Request results"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> CatalogMenu(options: List<T>, selection: T, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label(selection)); ExposedDropdownMenuDefaults.TrailingIcon(open) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = { onSelect(option); open = false })
            }
        }
    }
}

@Composable
internal fun ToolCatalog(capabilities: List<CapabilityStatus>?, pageReady: Boolean) {
    var search by rememberSaveable { mutableStateOf("") }
    var nativeOnly by rememberSaveable { mutableStateOf(false) }
    var display by rememberSaveable { mutableStateOf(DisplayFilter.ALL) }
    var readiness by rememberSaveable { mutableStateOf(CatalogReadiness.ALL) }

    Text("Tool availability", style = MaterialTheme.typography.titleMedium)
    Text("Availability reflects this phone. Manage each agent’s access in Agents.", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(search, { search = it }, label = { Text("Search tools") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = nativeOnly, onClick = { nativeOnly = !nativeOnly }, label = { Text("Native APIs") })
        CatalogMenu(DisplayFilter.entries, display, { it.label }) { display = it }
        CatalogMenu(CatalogReadiness.entries, readiness, { it.label }) { readiness = it }
    }

    val shown = remember(capabilities, search, nativeOnly, display, readiness) {
        val term = search.trim()
        capabilities.orEmpty().filter { status ->
            val tool = status.descriptor
            (!nativeOnly || tool.backend == BackendKind.NATIVE) && display.matches(tool.interaction) &&
                readiness.matches(status.availability.isExecutable) &&
                (tool.id.contains(term, true) || tool.title.contains(term, true) || tool.description.contains(term, true))
        }
    }
    if (capabilities != null) {
        Text("${shown.size} matching tools · ${shown.count { it.availability.isExecutable }} ready", style = MaterialTheme.typography.labelMedium)
    }
    shown.groupBy { it.descriptor.id.substringBefore('.') }.toSortedMap().forEach { (family, tools) ->
        val title = familyTitles[family] ?: family.replace('_', ' ').replaceFirstChar { it.uppercase() }
        key(family, search.isNotBlank()) {
            ExpandablePanel(title, "${tools.count { it.availability.isExecutable }} of ${tools.size} ready", initiallyExpanded = search.isNotBlank()) {
                tools.sortedWith(compareByDescending<CapabilityStatus> { it.availability.isExecutable }.thenBy { it.descriptor.title }).forEachIndexed { index, status ->
                    val tool = status.descriptor
                    if (index > 0) HorizontalDivider()
                    Text(tool.title, style = MaterialTheme.typography.titleSmall)
                    StatusPill(if (status.availability.isExecutable) "Ready" else status.availability.name.lowercase().replace('_', ' '),
                        if (status.availability.isExecutable) StatusTone.GOOD else StatusTone.ATTENTION)
                    val backend = if (tool.backend == BackendKind.NATIVE) "Native API" else tool.backend.name.replace('_', ' ')
                    Text("$backend · ${tool.interaction.label()}", style = MaterialTheme.typography.labelMedium)
                    Text(status.detail ?: tool.description, style = MaterialTheme.typography.bodySmall)
                    if (tool.ownerOnly) Text("Local owner only", style = MaterialTheme.typography.labelSmall)
                    Text(tool.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (pageReady && shown.isEmpty()) {
        EmptyState("No matching tools", "Try another search or clear the filters.")
        TextButton(onClick = { search = ""; nativeOnly = false; display = DisplayFilter.ALL; readiness = CatalogReadiness.ALL }) {
            Text("Clear filters")
        }
    }
}
