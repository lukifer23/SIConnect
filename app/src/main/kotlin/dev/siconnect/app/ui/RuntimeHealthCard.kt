package dev.siconnect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import java.util.Locale

/** One explicit native measurement. No polling, device wake or persistent profiler is started here. */
@Composable fun RuntimeHealthCard(snapshot:JsonObject?,busy:Boolean,onMeasure:()->Unit) {
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    fun value(key:String)=snapshot?.get(key)?.jsonPrimitive?.longOrNull
    fun mib(bytes:Long?)=bytes?.let {String.format(locale,"%.1f MiB",it/1048576.0)} ?: "Unavailable"
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Bridge health",style=MaterialTheme.typography.titleMedium)
        if(snapshot==null) Text("Check SIConnect’s real memory, CPU time and storage use.",style=MaterialTheme.typography.bodySmall)
        else {
            Text("RAM: ${mib(value("appPssKiB")?.times(1024))}")
            Text("CPU time used: ${String.format(locale,"%.2f s",(value("processCpuMs") ?: 0)/1000.0)} since this process started")
            Text("App storage: ${mib(value("appCodeBytes")?.let {it+(value("appDataBytes") ?: 0)})}")
            Text("Cache: ${mib(value("appCacheBytes"))} · Database: ${mib(value("databaseBytes")?.let {it+(value("databaseWalBytes") ?: 0)})}")
            Text("Connection: ${snapshot["gatewayState"]?.jsonPrimitive?.content ?: "Unavailable"}")
            Text("One sample; CPU time is cumulative, not CPU percentage or battery drain.",style=MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick=onMeasure,enabled=!busy) {Text(if(busy) "Checking…" else if(snapshot==null) "Check now" else "Refresh measurement")}
    }}
}
