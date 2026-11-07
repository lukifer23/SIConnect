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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.semantics.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.siconnect.app.*
import dev.siconnect.app.R
import dev.siconnect.app.data.*
import dev.siconnect.app.automation.Automation
import dev.siconnect.app.transport.ConnectionService
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.serialization.json.*

private data class SettingsWorkflowRow(val definition:SkillDefinition,val enabled:Boolean)

/** Draft definitions and credentials survive rotation in memory, never in an Android state Bundle. */
private class ManagementDrafts(initialUrl:String):ViewModel() {
    val workflow=mutableStateOf("")
    val credential=mutableStateOf("")
    val url=mutableStateOf(initialUrl)
    val message=mutableStateOf("")
}

@Composable private fun SettingsAccessItem(title:String,status:String,action:String,onAction:()->Unit,explanation:String?=null,actionEnabled:Boolean=true) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title,style=MaterialTheme.typography.titleMedium)
                    Text(status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick=onAction,enabled=actionEnabled,modifier=Modifier.semantics {contentDescription="$action $title"}) {Text(action)}
            }
            explanation?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        }
    }
}

/** The service-details action is optional and permission protected on some Android releases. */
internal fun MainActivity.openScreenControlSettings():Boolean {
    val details=Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(Intent.EXTRA_COMPONENT_NAME,ComponentName(this,dev.siconnect.app.accessibility.SemanticAccessibilityService::class.java).flattenToString())
    val handler=packageManager.resolveActivity(details,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY or android.content.pm.PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo
    if(handler!=null && handler.exported && (handler.permission==null || checkSelfPermission(handler.permission)==android.content.pm.PackageManager.PERMISSION_GRANTED)) {
        try {startActivity(details.setComponent(ComponentName(handler.packageName,handler.name)));return true}
        catch(_:ActivityNotFoundException) { /* The supported accessibility list remains available below. */ }
        catch(_:SecurityException) { /* OEM service details may require additional system privileges. */ }
    }
    return try {startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS));true}
    catch(_:ActivityNotFoundException) {false}
    catch(_:SecurityException) {false}
}

@Composable internal fun MainActivity.Management(initialTab:String="Settings",onSetup:()->Unit,onGateway:()->Unit,onAdvancedControl:()->Unit) {
        val g=graph; val scope=rememberCoroutineScope()
        val drafts=remember {
            ViewModelProvider(this,object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=ManagementDrafts(g.settings.gateway.value.baseUrl) as T
            })[ManagementDrafts::class.java]
        }
        val expanded=LocalExpandedLayout.current
        val health by g.health.collectAsState(); val shizuku by g.shizuku.status.collectAsState(); val direct by g.privileged.directStatus.collectAsState(); val connection by g.gateway.state.collectAsState()
        val controls by g.settings.controls.collectAsState()
        val connectionDetail by g.gateway.detail.collectAsState()
        val semanticConnected by dev.siconnect.app.accessibility.SemanticAccessibilityService.connectionState.collectAsState()
        val listenerConnected by dev.siconnect.app.notifications.NotificationCaptureService.connectionState.collectAsState()
        val accessRevision=permissionRevision
        val notificationsEnabled=remember(accessRevision) {getSystemService(NotificationManager::class.java).areNotificationsEnabled()}
        val calendarGranted=remember(accessRevision) {checkSelfPermission(Manifest.permission.READ_CALENDAR)==android.content.pm.PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.WRITE_CALENDAR)==android.content.pm.PackageManager.PERMISSION_GRANTED}
        val listenerComponent=remember {ComponentName(this,dev.siconnect.app.notifications.NotificationCaptureService::class.java)}
        val listenerGranted=remember(accessRevision) {getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listenerComponent)}
        val semanticGranted=remember(accessRevision) {AndroidSettings.Secure.getString(contentResolver,AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any {ComponentName.unflattenFromString(it)==ComponentName(this,dev.siconnect.app.accessibility.SemanticAccessibilityService::class.java)}}
        var showConnectionDetails by rememberSaveable {mutableStateOf(false)}
        var tab by rememberSaveable { mutableStateOf(initialTab) }
        var settingsPage by rememberSaveable {mutableStateOf<String?>(null)}
        var settingsSearch by rememberSaveable {mutableStateOf("")}
        val pageState=rememberSaveableStateHolder()
        LaunchedEffect(expanded) {if(expanded && settingsPage==null && tab=="Settings") settingsPage="Connections"}
        BackHandler(settingsPage!=null || tab!="Settings") {settingsPage=null;tab="Settings"}
        var refresh by remember { mutableIntStateOf(0) }; var message by drafts.message
        var restoringScreenControl by rememberSaveable {mutableStateOf(false)}
        LaunchedEffect(semanticConnected,restoringScreenControl) {
            if(restoringScreenControl && semanticConnected) {
                message="Screen control restored. Android has connected the SIConnect accessibility service."
                restoringScreenControl=false
            }
        }
        var events by remember { mutableStateOf<List<Event>?>(null) }
        var skills by remember { mutableStateOf<List<SettingsWorkflowRow>?>(null) }
        var roots by remember { mutableStateOf<List<DocumentRoot>?>(null) }
        var capabilities by remember { mutableStateOf<List<CapabilityStatus>?>(null) }
        var toolSearch by rememberSaveable {mutableStateOf("")}
        var readyToolsOnly by rememberSaveable {mutableStateOf(false)}
        var toolsNeedingSetup by rememberSaveable {mutableStateOf(false)}
        var skillJson by drafts.workflow
        var url by drafts.url
        var gatewaySecret by drafts.credential
        var approvalId by rememberSaveable {mutableStateOf<String?>(null)}
        var approve by remember { mutableStateOf<RequestRecord?>(null) }
        var approvalError by remember {mutableStateOf<String?>(null)}
        var busy by remember {mutableStateOf(false)}
        var removeSkill by remember {mutableStateOf<SkillDefinition?>(null)}
        fun action(block: suspend ()->Unit) {
            if(busy) return
            busy=true;message=""
            scope.launch {
                try {withContext(Dispatchers.IO) {g.ready.await();block()};refresh++}
                catch(e:CancellationException) {message="The action left this screen before its outcome was confirmed. Review current settings and Activity before repeating it.";throw e}
                catch(e:Exception) {message=e.message ?: e.javaClass.simpleName}
                finally {busy=false}
            }
        }
        val exportDiagnostics=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) {uri->
            if(uri==null) message="Export cancelled. No diagnostic file was written."
            else action {
                val records=g.events.query(limit=5000)
                val output=requireNotNull(contentResolver.openOutputStream(uri,"wt")) {"The selected destination could not be opened"}
                output.bufferedWriter(Charsets.UTF_8).use {writer->
                    records.forEach {event->
                        writer.appendLine(AppJson.encodeToString(Event.serializer(),event.copy(agentId=null,deviceId=null,detail=null)))
                    }
                }
                message="Saved ${records.size} redacted diagnostic events to your selected file."
            }
        }
        val loadTarget=when {tab=="Settings" && settingsPage=="Connected folders"->"Folders";tab in setOf("Capabilities","Skills","Diagnostics")->tab;else->null}
        var pageLoading by remember {mutableStateOf(false)}
        var loadError by remember {mutableStateOf<Pair<String,String>?>(null)}
        val loadGeneration=remember {intArrayOf(0)}
        val hasPageData=when(loadTarget) {"Folders"->roots!=null;"Skills"->skills!=null;"Capabilities"->capabilities!=null;"Diagnostics"->events!=null;else->true}
        val pageReady=hasPageData && !pageLoading && loadError?.first!=loadTarget
        LaunchedEffect(refresh,loadTarget,permissionRevision,if(loadTarget=="Capabilities") health else "",if(loadTarget=="Capabilities") direct else "",if(loadTarget=="Capabilities") shizuku else "",if(loadTarget=="Capabilities") controls else null) {
            val generation=++loadGeneration[0]
            pageLoading=loadTarget!=null;loadError=null
            if(loadTarget==null) return@LaunchedEffect
            try {
                withContext(Dispatchers.IO) {g.ready.await()}
                when(loadTarget) {
                    "Folders"->roots=withContext(Dispatchers.IO) {DocumentRoots(g.context,g.db).all()}
                    "Capabilities"->capabilities=withContext(Dispatchers.IO) {g.registry.statuses()}
                    "Skills"->skills=withContext(Dispatchers.IO) {g.skills.rowsIncludingDisabled().map {(definition,enabled)->SettingsWorkflowRow(definition,enabled)}}
                    "Diagnostics"->events=withContext(Dispatchers.IO) {g.events.query(limit=50)}
                }
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {loadError=loadTarget to "Could not load this page. Your saved settings have not been changed."}
            finally {if(loadGeneration[0]==generation) pageLoading=false}
        }
        LaunchedEffect(tab) {if(tab=="Diagnostics") g.engine.recordUpdates.conflate().collect {refresh++}}
        LaunchedEffect(approvalId) {
            approve=null;approvalError=null
            val id=approvalId ?: return@LaunchedEffect
            g.engine.recordUpdates.refreshesFor(id).collect {
                try {
                    val current=withContext(Dispatchers.IO) {g.ready.await();g.requests.get(id)}
                    if(approvalId==id) {
                        approve=current
                        approvalError=if(current==null) "This request is no longer available. Check its retained outcome in Activity before requesting another action." else null
                    }
                } catch(e:CancellationException) {throw e}
                catch(_:Exception) {if(approvalId==id) {approve=null;approvalError="This review could not be loaded. Your existing request has not been resubmitted."}}
            }
        }
        PageLayout {
            PageHeader(if(expanded) "Settings" else if(tab=="Settings") settingsPage ?: "Settings" else tab,
                if(settingsPage==null && tab=="Settings") "Make this phone work the way you want." else "Your phone’s access and preferences.") {
                if(!expanded && (settingsPage!=null || tab!="Settings")) TextButton(onClick={settingsPage=null;tab="Settings"}) {Text("Back to settings")}
            }
            if(tab!="Settings") FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("Settings","Capabilities","Skills","Diagnostics").forEach {t->FilterChip(selected=tab==t,onClick={tab=t},label={Text(t)})}
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(loadTarget!=null && (pageLoading || (!hasPageData && loadError==null))) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if(hasPageData) "Refreshing saved information…" else "Loading saved information…",style=MaterialTheme.typography.bodySmall)
            }
            loadError?.takeIf {it.first==loadTarget}?.let {(_,error)->
                Notice(if(hasPageData) "$error Showing the last loaded information." else error,true)
                TextButton(onClick={refresh++},enabled=!pageLoading) {Text("Retry loading")}
            }
            if(message.isNotBlank()) Row(verticalAlignment=Alignment.CenterVertically) {
                Text(message,Modifier.weight(1f).semantics {liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick={message=""}) {Text("Dismiss")}
            }
            Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                if(expanded) Column(Modifier.width(190.dp).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("PREFERENCES",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(12.dp))
                    listOf("Connections","Recovery","Termux","Android access","Connected folders","Background & timing","Appearance","Commute & weather","Storage & history","Private workspaces","Advanced connection","Developer tools").forEach {name->
                        Surface(onClick={if(name=="Developer tools") {tab="Capabilities";settingsPage=null} else {tab="Settings";settingsPage=name}},
                            shape=MaterialTheme.shapes.medium,color=if(settingsPage==name || (name=="Developer tools" && tab!="Settings")) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                            Text(name,Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=14.dp),style=MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            pageState.SaveableStateProvider("$tab:${settingsPage ?: "overview"}") {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(expanded) SectionHeading(if(tab=="Settings") settingsPage ?: "All settings" else tab)
                when(tab) {
                    "Capabilities" -> {
                        Text("Tool availability",style=MaterialTheme.typography.titleMedium)
                        Text("Browse by purpose. Availability reflects this phone; each agent’s access is managed separately in Agents.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(toolSearch,{toolSearch=it},label={Text("Search tools")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            FilterChip(!readyToolsOnly && !toolsNeedingSetup,{readyToolsOnly=false;toolsNeedingSetup=false},label={Text("All tools")})
                            FilterChip(readyToolsOnly,{readyToolsOnly=true;toolsNeedingSetup=false},label={Text("Ready to use")})
                            FilterChip(toolsNeedingSetup,{toolsNeedingSetup=true;readyToolsOnly=false},label={Text("Needs setup")})
                        }
                        val shown=capabilities.orEmpty().filter {(!readyToolsOnly || it.availability.isExecutable) && (!toolsNeedingSetup || !it.availability.isExecutable) &&
                            (it.descriptor.id+" "+it.descriptor.title+" "+it.descriptor.description).contains(toolSearch,true)}
                        if(capabilities!=null) Text("${shown.size} matching tools · ${capabilities.orEmpty().count {it.availability.isExecutable}} ready on this phone",style=MaterialTheme.typography.labelMedium)
                        shown.groupBy {it.descriptor.id.substringBefore('.')}.toSortedMap().forEach {(family,tools)->
                            val title=mapOf("ui" to "Shared-screen interaction","headless" to "Private app workspaces","shell" to "Privileged shell","device" to "Device diagnostics","documents" to "Selected documents","files" to "Managed files","termux" to "Termux background commands","system" to "System operations","requests" to "Request results")[family]
                                ?: family.replace('_',' ').replaceFirstChar {it.uppercase()}
                            key(family,toolSearch.isNotBlank()) {
                                ExpandablePanel(title,"${tools.count {it.availability.isExecutable}} of ${tools.size} ready",initiallyExpanded=toolSearch.isNotBlank()) {
                                    tools.sortedWith(compareBy<CapabilityStatus> {it.availability.isExecutable}.thenBy {it.descriptor.title}).forEachIndexed {index,cap->
                                        if(index>0) HorizontalDivider()
                                        Text(cap.descriptor.title,style=MaterialTheme.typography.titleSmall)
                                        StatusPill(if(cap.availability.isExecutable) "Ready" else cap.availability.name.lowercase().replace('_',' '),if(cap.availability.isExecutable) StatusTone.GOOD else StatusTone.ATTENTION)
                                        Text(cap.detail ?: cap.descriptor.description,style=MaterialTheme.typography.bodySmall)
                                        if(cap.descriptor.ownerOnly) Text("Local owner only",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(cap.descriptor.id,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        if(pageReady && shown.isEmpty()) EmptyState("No matching tools","Try another search or select All tools.")
                    }
                    "Skills" -> {
                        ExpandablePanel("Install a workflow","Paste a definition, then validate it before installation") {
                            OutlinedTextField(skillJson,{skillJson=it},label={Text("Workflow JSON")},modifier=Modifier.fillMaxWidth(),maxLines=10,enabled=!busy)
                            Button(onClick={
                                val definition=skillJson
                                ownerAuthentication.authenticate("Install workflow",{action {g.skills.install(SkillJson.parse(definition));message="Workflow validated and installed"}},{message=it})
                            },enabled=!busy && skillJson.isNotBlank()) {Text("Validate and install")}
                        }
                        SectionHeading("Installed workflows")
                        if(pageReady && skills.orEmpty().isEmpty()) EmptyState("No workflows installed","Install a validated definition to make it available for local automation.")
                        skills.orEmpty().forEach {(def,enabled)->
                            Panel {
                                Text(def.id.replace('.',' ').replace('_',' ').replaceFirstChar {it.uppercase()},style=MaterialTheme.typography.titleMedium)
                                StatusPill(if(enabled) "Enabled" else "Disabled",if(enabled) StatusTone.GOOD else StatusTone.NEUTRAL)
                                Text(def.description,style=MaterialTheme.typography.bodyMedium)
                                Text("Version ${def.version} · ${def.steps.size} steps",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Button(onClick={ownerAuthentication.authenticate("Run owner workflow",{action {
                                        val id="skill:${java.util.UUID.randomUUID()}"
                                        val r=g.runner.start(def,JsonObject(emptyMap()),ExecutionContext(id,id,"local-owner",g.settings.deviceId),Origin.LOCAL_OWNER)
                                        message="${r.runId}: ${r.state}"
                                    }},{message=it})},enabled=!busy && pageReady && enabled) {Text("Run once")}
                                    OutlinedButton(onClick={if(enabled) action {g.skills.enable(def.id,false)} else ownerAuthentication.authenticate("Enable workflow",{action {g.skills.enable(def.id,true)}},{message=it})},enabled=!busy && pageReady) {Text(if(enabled) "Disable" else "Enable")}
                                    TextButton(onClick={removeSkill=def},enabled=!busy && pageReady) {Text("Remove",color=MaterialTheme.colorScheme.error)}
                                }
                            }
                        }
                    }
                    "Diagnostics" -> {
                        Panel {
                            Text("Runtime & storage",style=MaterialTheme.typography.titleMedium)
                            Text(connectionDetail,style=MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick={action {
                                val r=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="device.runtime",arguments=buildJsonObject {put("checkDatabase",true)}),Origin.LOCAL_OWNER)
                                require(r.state==RequestState.SUCCEEDED) {r.errorMessage ?: "Database check could not complete"}
                                message="Live database integrity: "+r.result!!.output.jsonObject.getValue("databaseIntegrity").jsonPrimitive.content
                            }},enabled=!busy) {Text("Check database health")}
                        }
                        Panel {
                            Text("Export diagnostics",style=MaterialTheme.typography.titleMedium)
                            Text("Choose where to save up to 5,000 events. Agent identities, device identity, and event details are removed from this export.",style=MaterialTheme.typography.bodyMedium)
                            Button(onClick={ownerAuthentication.authenticate("Save redacted diagnostics",{exportDiagnostics.launch("siconnect-diagnostics.jsonl")},{message=it})},enabled=!busy) {Text("Save redacted diagnostics")}
                        }
                        SectionHeading("Recent diagnostic events")
                        if(pageReady && events.orEmpty().isEmpty()) EmptyState("No retained events","New runtime and connection events will appear here.")
                        events.orEmpty().forEach {event->Panel {
                            Text(event.eventType.replace('.',' '),style=MaterialTheme.typography.titleSmall)
                            Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.MEDIUM).format(java.util.Date(event.timestampMs)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            event.capabilityId?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                            event.errorCode?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                            event.executionStatus?.let {Text(it.toString(),style=MaterialTheme.typography.bodySmall)}
                        }}
                    }
                    "Settings" -> {
                        if(settingsPage==null) {
                            OutlinedTextField(settingsSearch,{settingsSearch=it},label={Text("Find a setting")},singleLine=true,modifier=Modifier.fillMaxWidth())
                            val categories=listOf(
                                Triple("Connections","Gateway, agents, and advanced device control",R.drawable.ic_nav_agents),
                                Triple("Recovery","Live connection, Android access, and startup blockers",R.drawable.ic_nav_settings),
                                Triple("Termux","Native background commands and CLI sign-in checks",R.drawable.ic_nav_settings),
                                Triple("Android access","Screen, notifications, and calendar",R.drawable.ic_nav_settings),
                                Triple("Connected folders","Choose documents your agents can use",R.drawable.ic_nav_home),
                                Triple("Background & timing","Battery access and precise reminders",R.drawable.ic_nav_automations),
                                Triple("Appearance","System, light, or dark theme",R.drawable.ic_nav_settings),
                                Triple("Commute & weather","Destinations, travel timing, and forecasts",R.drawable.ic_nav_automations),
                                Triple("Storage & history","Execution history and payload retention",R.drawable.ic_nav_activity),
                                Triple("Private workspaces","Manage apps running on private displays",R.drawable.ic_nav_home),
                                Triple("Advanced connection","Backend selection and manual configuration",R.drawable.ic_nav_settings),
                                Triple("Developer tools","Tool catalog, workflows, and diagnostics",R.drawable.ic_nav_activity),
                            ).filter {(title,description,_)->settingsSearch.isBlank() || "$title $description".contains(settingsSearch,true)}
                            if(categories.isEmpty()) EmptyState("No matching settings","Try a different word, such as notifications, theme, or gateway.")
                            BoxWithConstraints {
                                val twoColumns=maxWidth>=560.dp
                                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                    categories.chunked(if(twoColumns) 2 else 1).forEach {row->
                                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                            row.forEach {(title,description,icon)->NavigationCard(title,description,{
                                                if(title=="Developer tools") {tab="Capabilities"} else settingsPage=title
                                            },Modifier.weight(1f),icon)}
                                            if(twoColumns && row.size==1) Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                            OutlinedButton(onClick=onSetup) {Text("Guided setup & verification")}
                        }
                        if(settingsPage=="Connections") {
                            Text("Connections carry requests to this phone. Each agent keeps its saved access mode separately; Full control remains saved until you disable it or revoke the agent.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            StatusPill(if(controls.remoteExecutionEnabled) "Remote actions enabled" else "Remote actions paused",if(controls.remoteExecutionEnabled) StatusTone.GOOD else StatusTone.ATTENTION)
                            SettingsAccessItem("Gateway",if(connection==dev.siconnect.app.transport.ConnectionState.ONLINE) "Connected" else connectionDetail,"Manage",onGateway)
                        SettingsAccessItem("Advanced device control",if(g.privileged.available()) "Available · ${if(g.settings.privilegeBackend=="direct") "bundled service" else "Shizuku"}" else "Optional · setup or recovery needed","Open",onAdvancedControl)
                        NavigationCard("Termux background commands","Set up the native Termux command bridge and verify its own app identity.",{settingsPage="Termux"},icon=R.drawable.ic_nav_settings)
                        OutlinedButton(onClick=onSetup) {Text("Guided setup and verification")}

                        }
                        if(settingsPage=="Termux") {TermuxSettings(onReview={approvalId=it.requestId})}
                        if(settingsPage=="Android access") {
                            Text("Access belongs to this phone. Agent permissions are managed separately in Agents.",style=MaterialTheme.typography.bodySmall)
                        if(permissionStatus.isNotBlank()) Text(permissionStatus)
                        SettingsAccessItem("Approval and reminder alerts",if(notificationsEnabled) "Allowed" else "Off",if(notificationsEnabled) "Manage" else "Enable",{
                            if(!notificationsEnabled && android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                                requestNativeAccess(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                            else startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE,packageName))
                        })
                        SettingsAccessItem("Screen control",if(semanticConnected) "Connected" else if(semanticGranted) "Enabled · service disconnected" else "Not enabled",if(semanticGranted && !semanticConnected) "Restore connection" else if(semanticGranted) "Manage" else "Enable",{
                            val restore=semanticGranted && !semanticConnected
                            if(openScreenControlSettings()) {
                                restoringScreenControl=restore
                                if(restore) message="In Android Accessibility settings, open SIConnect, turn its service off, then on. Return here to verify the connection."
                            } else message="Android could not open Accessibility settings. Open your phone’s Settings, then Accessibility, and select SIConnect."
                        },if(semanticGranted && !semanticConnected) "Android has retained your permission, but the service is disconnected. Restore connection opens Android settings: select SIConnect, turn its service off, then on. This status changes to Connected only when Android reconnects it." else null)
                        SettingsAccessItem("Notification actions",if(listenerConnected) "Connected" else if(listenerGranted) "Allowed · service disconnected" else "Not enabled",if(listenerGranted && !listenerConnected) "Reconnect" else if(listenerGranted) "Manage" else "Enable",{
                            if(listenerGranted && !listenerConnected) {
                                android.service.notification.NotificationListenerService.requestRebind(listenerComponent)
                                message="Reconnection requested. Status changes only when Android connects the notification listener."
                            } else startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        })
                        if(listenerGranted && !listenerConnected) TextButton(onClick={startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}) {Text("Review notification access in Android")}
                        SettingsAccessItem("Calendar",if(calendarGranted) "Read and write allowed" else "Access needed",if(calendarGranted) "Manage" else "Enable",{
                            if(calendarGranted) startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
                            else requestNativeAccess(arrayOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR))
                        })

                        }
                        if(settingsPage=="Connected folders") {
                            Text("Connected folders",style=MaterialTheme.typography.titleMedium)
                        if(roots!=null) Text(if(roots.orEmpty().isEmpty()) "No folders selected. Android lets you choose shared folders; protected app storage is excluded." else "${roots.orEmpty().size} selected. Android access and each agent’s document grant are required.",style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick={ownerAuthentication.authenticate("Connect document folder",{documentPicker.launch(null)},{documentStatus=it})}) {Text("Choose folder")}
                        if(documentStatus.isNotBlank()) Text(documentStatus)
                        roots.orEmpty().forEach {root->SettingsAccessItem(root.label,"Selected folder","Disconnect",{action {DocumentRoots(this@Management,g.db).remove(root.id);g.gateway.refreshCatalog();documentStatus="";message="Folder access removed"}},actionEnabled=!busy && pageReady)}

                        }
                        if(settingsPage=="Background & timing") {
                            val exact=g.reminders.canScheduleExact()
                        SettingsAccessItem("Precise reminders",if(exact) "Allowed" else "Flexible timing available","Manage",{startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName")))})
                        val batteryExempt=getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                        SettingsAccessItem("Background operation",if(batteryExempt) "Battery exemption allowed" else "Android battery optimization applies","Manage",{startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))})

                        }
                        if(settingsPage=="Appearance") {
                            Text("Choose your appearance",style=MaterialTheme.typography.titleMedium)
                        Text("Changes apply immediately across the app and are saved on this phone.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        val appearance by g.settings.appearance.collectAsState()
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("SYSTEM" to "System","LIGHT" to "Light","DARK" to "Dark").forEach {(value,label)->FilterChip(selected=appearance==value,onClick={g.settings.setAppearance(value)},label={Text(label)})}
                        }

                        }
                        if(settingsPage=="Appearance") Panel {
                            StatusPill("Live appearance",StatusTone.GOOD)
                            Text("Your control center",style=MaterialTheme.typography.titleLarge)
                            Text("Cards, status labels, and controls use your selected theme. System follows Android’s appearance.",style=MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick={g.settings.setAppearance("SYSTEM")}) {Text("Follow Android appearance")}
                        }
                        if(settingsPage=="Commute & weather") {
                            CommuteSettings()

                        }
                        if(settingsPage=="Storage & history") {
                            StorageSettings()

                        }
                        if(settingsPage=="Private workspaces") {
                            PrivateWorkspaceSettings()

                        }
                        if(settingsPage=="Recovery") RecoverySettings(onGateway,onAdvancedControl,{settingsPage="Termux"})
                        if(settingsPage=="Advanced connection") {
                            TextButton(onClick={showConnectionDetails=!showConnectionDetails}) {Text(if(showConnectionDetails) "Hide advanced connection settings" else "Advanced connection settings")}
                        if(showConnectionDetails) {
                            Row(verticalAlignment=Alignment.CenterVertically) {Text("Route privileged requests",Modifier.weight(1f));Switch(controls.privilegedEnabled,{enabled->
                                if(enabled) ownerAuthentication.authenticate("Enable privileged execution",{g.settings.setControls(g.settings.controls.value.copy(privilegedEnabled=true))},{message=it})
                                else g.settings.setControls(g.settings.controls.value.copy(privilegedEnabled=false))
                            })}
                            Text("Selected backend: ${if(g.settings.privilegeBackend=="direct") "Bundled service" else "Shizuku"}. Selection never changes automatically.")
                            if(g.settings.privilegeBackend!="direct") OutlinedButton(onClick={ownerAuthentication.authenticate("Select bundled authority",{action {g.settings.privilegeBackend="direct";g.privileged.refresh()}},{message=it})}) {Text("Use bundled service")}
                            if(g.settings.privilegeBackend!="shizuku") OutlinedButton(onClick={ownerAuthentication.authenticate("Select Shizuku authority",{action {g.settings.privilegeBackend="shizuku";g.privileged.refresh()}},{message=it})}) {Text("Use optional Shizuku")}
                            if(g.settings.privilegeBackend=="shizuku") OutlinedButton(onClick={g.shizuku.requestPermission()}) {Text("Authorize Shizuku")}
                            Text(if(g.settings.privilegeBackend=="direct") direct else shizuku,style=MaterialTheme.typography.bodySmall)
                            OutlinedTextField(url,{url=it},label={Text("Gateway HTTPS URL")},modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(gatewaySecret,{gatewaySecret=it},label={Text("Existing device credential · optional")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                            OutlinedButton(onClick={
                                val selectedUrl=url;val selectedSecret=gatewaySecret
                                ownerAuthentication.authenticate("Change gateway connection",{action {
                                    require(selectedUrl.startsWith("https://") || (BuildConfig.DEBUG && selectedUrl.startsWith("http://127.0.0.1:"))) {"HTTPS required"}
                                    g.gateway.stop()
                                    if(selectedSecret.isNotBlank()) g.settings.setDeviceSecret(selectedSecret)
                                    g.settings.setGateway(GatewayConfig(selectedUrl,true));gatewaySecret=""
                                    withContext(Dispatchers.Main) {startForegroundService(Intent(this@Management,ConnectionService::class.java))}
                                    message="Connecting with saved configuration"
                                }},{message=it})
                            },enabled=url.isNotBlank()) {Text("Save connection settings")}
                            TextButton(onClick={g.settings.setGateway(g.settings.gateway.value.copy(enabled=false));stopService(Intent(this@Management,ConnectionService::class.java))}) {Text("Disconnect gateway")}
                            if(BuildConfig.DEBUG) TextButton(onClick={ownerAuthentication.authenticate("Rotate development credential",{action {g.createDevelopmentAgent();message="Development credential rotated"}},{message=it})}) {Text("Rotate development credential")}
                        }

                        }
                        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",style=MaterialTheme.typography.bodySmall)

                    }
                }
            }
            }
            }
            if(tab!="Settings") TextButton(onClick={refresh++}) { Text("Refresh") }
        }
        removeSkill?.let {definition->
            AlertDialog(onDismissRequest={removeSkill=null},title={Text("Remove this workflow?")},
                text={Text("${definition.id} will no longer be available for new runs. Saved schedules and already running executions are managed separately in Automations.")},
                confirmButton={TextButton(onClick={removeSkill=null;action {
                    require(g.skills.all(includeDisabled=true).firstOrNull {it.id==definition.id}==definition) {"Workflow changed. Review it again before removing."}
                    g.skills.remove(definition.id);message="Workflow removed"
                }},enabled=!busy) {Text("Remove workflow")}},
                dismissButton={TextButton(onClick={removeSkill=null}) {Text("Keep workflow")}})
        }
        approve?.let { r -> OwnerApprovalDialog(r,error=message,onClose={approvalId=null;approve=null},onDeny={action {g.engine.resolveApproval(r.requestId,false,"local-owner",r.argsHash)}},onApprove={always,broad ->
            ownerAuthentication.authenticate("Approve ${r.capabilityId}",{
                if(approvalId==r.requestId) action {g.approveFromOwner(r.requestId,r.argsHash,always,broad)}
            },{message=it})
        }) }
        if(approvalId!=null && approve==null) AlertDialog(onDismissRequest={approvalId=null},title={Text("Saved owner review")},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(approvalError==null) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(approvalError ?: "Loading the original request and its current outcome…")
            }},confirmButton={TextButton(onClick={approvalId=null}) {Text("Close")}})
    }
