package dev.siconnect.app.ui.agents

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.siconnect.app.accessibility.SemanticUiRules
import kotlinx.coroutines.*

internal data class InstalledAppChoice(val packageName:String,val label:String,val version:String)

/** Current Android user only. Selecting a row does not grant anything; the existing confirmation follows. */
@Composable internal fun InstalledAppPicker(allowed:Set<String>,onSelect:(InstalledAppChoice)->Unit,onClose:()->Unit) {
    val context=LocalContext.current
    var query by rememberSaveable {mutableStateOf("")}
    var apps by remember {mutableStateOf<List<InstalledAppChoice>?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(Unit) {
        try {apps=withContext(Dispatchers.IO) {
            val pm=context.packageManager
            val installed=if(android.os.Build.VERSION.SDK_INT>=33) pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)) else pm.getInstalledApplications(0)
            installed.asSequence()
                .filter {it.packageName!=context.packageName && !SemanticUiRules.protectedPackage(it.packageName)}
                .mapNotNull {app->runCatching {
                    val info=if(android.os.Build.VERSION.SDK_INT>=33) pm.getPackageInfo(app.packageName,PackageManager.PackageInfoFlags.of(0)) else pm.getPackageInfo(app.packageName,0)
                    InstalledAppChoice(app.packageName,app.loadLabel(pm).toString(),info.versionName ?: info.longVersionCode.toString())
                }.getOrNull()}.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) {it.label}).toList()
        }} catch(e:CancellationException) {throw e} catch(e:Exception) {error="Could not load installed apps: ${e.javaClass.simpleName}"}
    }
    val filtered=apps.orEmpty().filter {it.label.contains(query,true) || it.packageName.contains(query,true)}
    AlertDialog(onDismissRequest=onClose,title={Text("Choose an installed app")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Current Android user. Protected owner, permission and lock-screen surfaces are excluded. Selecting an app does not change permission.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(query,{query=it.take(200)},label={Text("Search app name or package")},singleLine=true,modifier=Modifier.fillMaxWidth())
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(apps==null && error==null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(apps!=null && filtered.isEmpty()) Text("No matching installed apps.")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=360.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(filtered.take(100),key={it.packageName}) {app->
                val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null,app.packageName) {
                    value=withContext(Dispatchers.IO) {runCatching {context.packageManager.getApplicationIcon(app.packageName).toBitmap(64,64).asImageBitmap()}.getOrNull()}
                }
                OutlinedCard(onClick={onSelect(app)},modifier=Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        icon?.let {Image(it,contentDescription=null,modifier=Modifier.size(40.dp))}
                        Column(Modifier.weight(1f)) {
                            Text(app.label,style=MaterialTheme.typography.titleSmall)
                            Text(app.packageName,style=MaterialTheme.typography.bodySmall)
                            Text("Version ${app.version} · ${if(app.packageName in allowed) "Standing app allowance saved" else "No standing app allowance"}",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if(filtered.size>100) Text("Showing 100 matches. Refine the search.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick=onClose) {Text("Close")}})
}
