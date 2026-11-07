package dev.siconnect.app.ui.automations

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.R
import dev.siconnect.app.ui.*
import dev.siconnect.app.automation.*
import dev.siconnect.app.capabilities.WeatherCommute
import dev.siconnect.app.data.*
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

private data class AutomationResources(val skills:List<SkillDefinition>,val capabilities:List<CapabilityStatus>,
    val grant:AgentGrant?,val exactAllowed:Boolean,val notificationsAllowed:Boolean)

/** Form-driven owner management over the existing durable scheduler and skill runtime. */
@Composable
fun AutomationsScreen(g:RuntimeGraph,authenticate:(String,()->Unit,(String)->Unit)->Unit,
                      modifier:Modifier=Modifier,onChanged:()->Unit = {}) {
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val locale=LocalLocale.current.platformLocale
    val lifecycle=LocalLifecycleOwner.current
    var refresh by remember {mutableIntStateOf(0)}
    var resourcesRefresh by remember {mutableIntStateOf(0)}
    val projections=remember(g) {ScheduleProjectionStore(g.db)}
    var automations by remember {mutableStateOf(emptyList<AutomationSummary>())}
    var automationFilter by rememberSaveable {mutableStateOf(AutomationFilter.ALL)}
    var search by rememberSaveable {mutableStateOf("")}
    var query by remember {mutableStateOf(search)}
    var automationCursors by remember {mutableStateOf(listOf<AutomationSummary?>(null))}
    var moreAutomations by remember {mutableStateOf(false)}
    var listLoading by remember {mutableStateOf(true)}
    var listError by remember {mutableStateOf<String?>(null)}
    var skills by remember {mutableStateOf(emptyList<SkillDefinition>())}
    var selectedId by rememberSaveable {mutableStateOf<String?>(null)}
    var selectedAutomation by remember {mutableStateOf<Automation?>(null)}
    var selectionLoadedId by remember {mutableStateOf<String?>(null)}
    var draftJson by rememberSaveable {mutableStateOf<String?>(null)}
    var discardDraft by rememberSaveable {mutableStateOf(false)}
    var runs by remember {mutableStateOf(emptyList<AutomationRun>())}
    var runFilter by rememberSaveable {mutableStateOf(AutomationRunFilter.ALL)}
    var runCursors by remember {mutableStateOf(listOf<AutomationRun?>(null))}
    var moreRuns by remember {mutableStateOf(false)}
    var runsLoading by remember {mutableStateOf(false)}
    var runsError by remember {mutableStateOf<String?>(null)}
    var inspectedRunId by rememberSaveable {mutableStateOf<String?>(null)}
    var inspectedRun by remember {mutableStateOf<SkillExecution?>(null)}
    var inspectedLoadedId by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    var message by rememberSaveable {mutableStateOf("")}
    var capabilities by remember {mutableStateOf(emptyList<CapabilityStatus>())}
    var automationGrant by remember {mutableStateOf<AgentGrant?>(null)}
    var exactAllowed by remember {mutableStateOf(false)}
    var notificationsAllowed by remember {mutableStateOf(false)}
    var ready by remember {mutableStateOf(false)}
    fun work(block:suspend ()->Unit) {
        if(busy) return
        busy=true
        scope.launch {
            try {withContext(Dispatchers.IO) {g.ready.await();block()};refresh++;resourcesRefresh++;onChanged()}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {message=e.message ?: "Action could not complete";refresh++}
            finally {busy=false}
        }
    }
    DisposableEffect(lifecycle) {
        val observer=object:DefaultLifecycleObserver {override fun onStart(owner:LifecycleOwner) {refresh++;resourcesRefresh++}}
        lifecycle.lifecycle.addObserver(observer)
        onDispose {lifecycle.lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(g,lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            g.ready.await();g.runner.runUpdates.collect {refresh++}
        }
    }
    LaunchedEffect(search) {delay(250);query=search;automationCursors=listOf(null)}
    LaunchedEffect(g,resourcesRefresh) {
        try {
            val loaded=withContext(Dispatchers.IO) {
                g.ready.await()
                val installed=g.skills.all()
                AutomationResources(if(g.skills.exists(AutomationTemplates.NOTICE_SKILL)) installed else listOf(AutomationTemplates.notice())+installed,
                    g.registry.statuses(maxAgeMs=5_000),g.agents.grant("local-automation"),
                    g.context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
                    g.context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
            }
            skills=loaded.skills;capabilities=loaded.capabilities;automationGrant=loaded.grant
            exactAllowed=loaded.exactAllowed;notificationsAllowed=loaded.notificationsAllowed;ready=true
        } catch(e:CancellationException) {throw e}
          catch(e:Exception) {message="Could not load automations: ${e.javaClass.simpleName}"}
    }
    LaunchedEffect(g,refresh,automationFilter,query,automationCursors) {
        listLoading=true;listError=null
        try {
            val loaded=withContext(Dispatchers.IO) {g.ready.await();projections.automationPage(51,automationCursors.last(),automationFilter,query)}
            automations=loaded.take(50);moreAutomations=loaded.size>50
        } catch(e:CancellationException) {throw e}
          catch(e:Exception) {listError="Could not load saved automations: ${e.javaClass.simpleName}"}
        finally {listLoading=false}
    }
    LaunchedEffect(g,selectedId,refresh) {
        val id=selectedId
        if(selectedAutomation?.id!=id) selectedAutomation=null
        selectionLoadedId=null
        try {selectedAutomation=withContext(Dispatchers.IO) {g.ready.await();id?.let(g.scheduler::get)};selectionLoadedId=id}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {message="Could not load this automation: ${e.javaClass.simpleName}";selectionLoadedId=id}
    }
    LaunchedEffect(g,selectedId,refresh,runFilter,runCursors) {
        val id=selectedId
        runsLoading=true;runsError=null
        try {
            val loaded=withContext(Dispatchers.IO) {g.ready.await();if(id==null) emptyList() else projections.runPage(id,31,runCursors.last(),runFilter)}
            runs=loaded.take(30);moreRuns=loaded.size>30
        } catch(e:CancellationException) {throw e}
          catch(e:Exception) {runsError="Could not load run history: ${e.javaClass.simpleName}"}
        finally {runsLoading=false}
    }
    LaunchedEffect(g,inspectedRunId,lifecycle) {
        val id=inspectedRunId
        inspectedRun=null;inspectedLoadedId=null
        if(id!=null) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            coroutineScope {
                val wakes=Channel<Unit>(Channel.CONFLATED)
                launch(start=CoroutineStart.UNDISPATCHED) {g.runner.runUpdates.collect {if(it.runId==id) wakes.trySend(Unit)}}
                wakes.trySend(Unit)
                try {for(wake in wakes) {
                    try {inspectedRun=withContext(Dispatchers.IO) {g.ready.await();g.skillRuns.get(id)};inspectedLoadedId=id}
                    catch(e:CancellationException) {throw e}
                    catch(e:Exception) {message="Could not load this run: ${e.javaClass.simpleName}"}
                }} finally {wakes.close()}
            }
        }
    }
    val selected=selectedAutomation?.takeIf {it.id==selectedId}
    val draft=draftJson?.let {AppJson.decodeFromString(AutomationDraft.serializer(),it)}
    fun closeSelection() {draftJson=null;selectedId=null;inspectedRunId=null;runCursors=listOf(null)}
    fun leave() {if(draftJson!=null) discardDraft=true else closeSelection()}
    BackHandler(enabled=draftJson!=null || selectedId!=null) {leave()}
    if(discardDraft) AlertDialog(onDismissRequest={discardDraft=false},
        title={Text("Discard unsaved changes?")},text={Text("Your saved automations will stay unchanged.")},
        confirmButton={TextButton(onClick={discardDraft=false;closeSelection()}) {Text("Discard changes")}},
        dismissButton={TextButton(onClick={discardDraft=false}) {Text("Keep editing")}})
    fun edit(value:AutomationDraft) {draftJson=AppJson.encodeToString(AutomationDraft.serializer(),value)}
    fun startTemplate(skill:SkillDefinition) {
        selectedId=null
        val now=ZonedDateTime.now().plusMinutes(5)
        edit(AutomationDraft("task-"+UUID.randomUUID().toString().take(16),AutomationTemplates.title(skill),skill.id,
            AutomationTemplates.initialInputs(skill,now.zone.id),localTime=now.toLocalTime().withSecond(0).withNano(0).toString(),startDate=now.toLocalDate().toString(),zoneId=now.zone.id))
    }
    fun startNew() { (skills.firstOrNull {it.id==AutomationTemplates.NOTICE_SKILL} ?: skills.firstOrNull())?.let(::startTemplate) }
    fun settings(action:String) {runCatching {g.context.startActivity(Intent(action).setData(Uri.parse("package:${g.context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.onFailure {message="Open Android Settings → SIConnect to change this access"}}
    PageLayout(modifier) {
        PageHeader(if(draft!=null) if(selectedId!=null) "Edit automation" else "New automation" else if(selected!=null) selected.label.ifBlank {"Automation"} else "Automations",
            if(draft!=null) "Choose the action, timing, and access. Review before saving." else "Build a routine. Let your phone handle the repeat.") {
            if(draft!=null || selectedId!=null) TextButton(onClick=::leave,enabled=!busy) {Text("Back")}
            else Button(onClick=::startNew,enabled=ready && !busy && skills.isNotEmpty()) {Text("New")}
        }
        if(message.isNotBlank()) Card {Column(Modifier.padding(12.dp)) {
            Text(message);TextButton(onClick={message=""}) {Text("Dismiss")}
        }}
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(!ready) {
            Text("Loading saved workflows and schedules…")
            if(message.isNotBlank()) TextButton(onClick={resourcesRefresh++}) {Text("Retry loading")}
            return@PageLayout
        }
        if(selectedId!=null && selected==null) {
            if(selectionLoadedId!=selectedId) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Loading this automation…")}
            else EmptyState("Automation unavailable","Return to saved automations and choose another schedule.")
            return@PageLayout
        }
        if(draft!=null) {
            val definition=skills.firstOrNull {it.id==draft.skillId}
            if(definition==null) {Text("This workflow is unavailable. Enable it in workflow management before editing.");return@PageLayout}
            val preview=runCatching {draft.preview(System.currentTimeMillis(),selected)}
            val inputErrors=Schema.validate(definition.inputs,draft.effectiveInputs())
            val requestedCapabilities=AutomationTemplates.requestedCapabilities(definition,draft.effectiveInputs())
            val missing=requestedCapabilities.filter {capability->
                val grant=automationGrant
                grant==null || grant.revoked || (grant.expiresAtMs?.let {it<=System.currentTimeMillis()} ?: false) ||
                    grant.scopes.none {PolicyEngine.globMatches(it,capability)} || grant.denied.any {PolicyEngine.globMatches(it,capability)}
            }
            val unavailable=requestedCapabilities.mapNotNull {id->capabilities.firstOrNull {it.descriptor.id==id}?.takeIf {!it.availability.isExecutable}?.let {id+": "+it.availability.name}}
            @Composable fun actionInputs() {
                SectionHeading("1. What happens")
                ChoiceField("What should happen?",skills.map {it.id},draft.skillId,{id->
                    val skill=skills.first {it.id==id}
                    edit(draft.copy(skillId=id,label=AutomationTemplates.title(skill),inputs=AutomationTemplates.initialInputs(skill,draft.zoneId),
                        recurrenceKind=if(id=="weather.commute_check") RecurrenceKind.DAILY else draft.recurrenceKind,
                        localTime=if(id=="weather.commute_check") "20:00" else draft.localTime))
                },skills.associate {it.id to AutomationTemplates.title(it)})
                OutlinedTextField(draft.label,{edit(draft.copy(label=it.take(120)))},label={Text(if(definition.id==AutomationTemplates.NOTICE_SKILL) "Reminder title" else "Name")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                Text(definition.description,style=MaterialTheme.typography.bodyMedium)
                Text(BackgroundExecution.description(definition,capabilities),style=MaterialTheme.typography.titleSmall)
                val mainWeather=setOf("wakeTime","departureTime","latitude","longitude")
                if(definition.id=="weather.commute_check") {
                    Text("Choose a wake-time fallback and departure time. Your visible next alarm is used when suitable; your Clock alarms are never changed.")
                    SchemaInputFields(definition.inputs,draft.inputs,{edit(draft.copy(inputs=it))},keys=mainWeather)
                    Text("Blank coordinates use your weather location saved in Settings. Coordinates remain on this device and are sent to the forecast provider when the workflow runs.",style=MaterialTheme.typography.bodySmall)
                    var advancedWeather by rememberSaveable(draft.id) {mutableStateOf(false)}
                    TextButton(onClick={advancedWeather=!advancedWeather}) {Text(if(advancedWeather) "Hide weather thresholds" else "Weather thresholds and notifications")}
                    if(advancedWeather) SchemaInputFields(definition.inputs,draft.inputs,{edit(draft.copy(inputs=it))},keys=(definition.inputs["properties"] as? JsonObject).orEmpty().keys-mainWeather-setOf("timezone"))
                } else {
                    SchemaInputFields(definition.inputs,draft.inputs,{edit(draft.copy(inputs=it))},
                        keys=when {definition.id==AutomationTemplates.NOTICE_SKILL->setOf("text")
                            definition.id==AutomationTemplates.HEALTH_SKILL && draft.inputs["notifyProblems"]?.jsonPrimitive?.booleanOrNull!=true->setOf("notifyProblems")
                            else->null})
                    if(definition.id==AutomationTemplates.HEALTH_SKILL && definition.version>=3 && draft.inputs["notifyProblems"]?.jsonPrimitive?.booleanOrNull==true)
                        Text("Unchanged warnings stay quiet unless reminders are enabled. Critical conditions can remind at least hourly. Threshold hysteresis reduces repeated alerts near a boundary.",style=MaterialTheme.typography.bodySmall)
                }
            }
            @Composable fun timingInputs() {
                SectionHeading("2. When it runs")
                ChoiceField("Repeat",RecurrenceKind.entries.map {it.name},draft.recurrenceKind.name,{value->
                    val kind=RecurrenceKind.valueOf(value)
                    edit(draft.copy(recurrenceKind=kind,weekdays=if(kind==RecurrenceKind.WEEKLY) draft.weekdays.take(1) else draft.weekdays))
                },mapOf("ONCE" to "Once","FIXED_INTERVAL" to "Every few minutes or hours","DAILY" to "Every day","WEEKDAYS" to "Selected weekdays","WEEKLY" to "Weekly","MONTHLY" to "Monthly"))
                if(draft.recurrenceKind in setOf(RecurrenceKind.ONCE,RecurrenceKind.FIXED_INTERVAL)) OutlinedButton(onClick={
                    val current=runCatching {LocalDate.parse(draft.startDate)}.getOrElse {LocalDate.now()}
                    DatePickerDialog(context,{_,year,month,day->edit(draft.copy(startDate=LocalDate.of(year,month+1,day).toString()))},current.year,current.monthValue-1,current.dayOfMonth).show()
                },modifier=Modifier.fillMaxWidth()) {Text("Start date · "+runCatching {LocalDate.parse(draft.startDate).format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy",locale))}.getOrDefault(draft.startDate))}
                OutlinedButton(onClick={
                    val current=runCatching {LocalTime.parse(draft.localTime)}.getOrElse {LocalTime.now()}
                    TimePickerDialog(context,{_,hour,minute->edit(draft.copy(localTime=LocalTime.of(hour,minute).toString()))},current.hour,current.minute,android.text.format.DateFormat.is24HourFormat(context)).show()
                },modifier=Modifier.fillMaxWidth()) {Text((if(definition.id=="weather.commute_check") "Evening check · " else "Time · ")+runCatching {LocalTime.parse(draft.localTime).format(DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale))}.getOrDefault(draft.localTime))}
                if(draft.recurrenceKind==RecurrenceKind.FIXED_INTERVAL)
                    OutlinedTextField(draft.intervalMinutes,{edit(draft.copy(intervalMinutes=it.take(8)))},label={Text("Repeat every · minutes (minimum 15)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                if(draft.recurrenceKind in setOf(RecurrenceKind.WEEKDAYS,RecurrenceKind.WEEKLY)) {
                    for(days in (1..7).toList().chunked(4)) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        for(day in days) FilterChip(selected=day in draft.weekdays,onClick={
                            val next=if(draft.recurrenceKind==RecurrenceKind.WEEKLY) listOf(day) else if(day in draft.weekdays) draft.weekdays-day else draft.weekdays+day
                            edit(draft.copy(weekdays=next))
                        },label={Text(DayOfWeek.of(day).getDisplayName(java.time.format.TextStyle.SHORT,locale))},modifier=Modifier.weight(1f))
                    }
                }
                if(draft.recurrenceKind==RecurrenceKind.MONTHLY) {
                    OutlinedTextField(draft.dayOfMonth,{edit(draft.copy(dayOfMonth=it.take(2)))},label={Text("Day of month · 1–31")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                    Text("Months without this date are skipped.",style=MaterialTheme.typography.bodySmall)
                }
                var changeZone by rememberSaveable(draft.id) {mutableStateOf(false)}
                Row {Text("Timezone: ${draft.zoneId}",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);TextButton(onClick={changeZone=!changeZone}) {Text(if(changeZone) "Done" else "Change")}}
                if(changeZone) OutlinedTextField(draft.zoneId,{edit(draft.copy(zoneId=it.take(100)))},label={Text("Timezone · e.g. Europe/London")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                Text("This timezone stays with the schedule when you travel. Clock changes and daylight saving use the saved recurrence.",style=MaterialTheme.typography.bodySmall)
                Row {Text("Precise timing",Modifier.weight(1f));Switch(draft.exact,{edit(draft.copy(exact=it))})}
                Text(if(draft.exact) "Uses Android exact alarms. The native workflow may still wait for execution or owner approval." else "Deferrable work saves wakeups; Android may delay this run.",style=MaterialTheme.typography.bodySmall)
                if(draft.exact && !exactAllowed) TextButton(onClick={settings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)}) {Text("Allow precise timing in Android")}
                var advancedSchedule by rememberSaveable(draft.id) {mutableStateOf(false)}
                TextButton(onClick={advancedSchedule=!advancedSchedule}) {Text(if(advancedSchedule) "Hide delayed-run options" else "If the phone misses a run")}
                if(advancedSchedule) {
                    ChoiceField("Missed run",MissedRunPolicy.entries.map {it.name},draft.missedPolicy.name,{edit(draft.copy(missedPolicy=MissedRunPolicy.valueOf(it)))},
                        mapOf("SKIP" to "Skip if more than one minute late","MARK_MISSED" to "Record as missed after one minute","RUN_ONCE_IF_STILL_RELEVANT" to "Run once within the maximum delay","COALESCE" to "Combine missed times into one relevant run"))
                    OutlinedTextField(draft.maxLatenessMinutes,{edit(draft.copy(maxLatenessMinutes=it.take(4)))},label={Text("Maximum delay · minutes (1–1440)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                }
                Row {Text("Enable schedule",Modifier.weight(1f));Switch(draft.enabled,{edit(draft.copy(enabled=it))})}
            }
            @Composable fun reviewSchedule() {
                HorizontalDivider()
                SectionHeading("3. Review & save")
                Text("Next runs",style=MaterialTheme.typography.titleSmall)
                preview.onSuccess {times->times.forEach {Text(formatTime(it,draft.zoneId,locale))}}
                    .onFailure {Text(it.message ?: "Check the schedule fields",color=MaterialTheme.colorScheme.error)}
                if(inputErrors.isNotEmpty()) Text(inputErrors.take(3).joinToString("\n"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                if(missing.isNotEmpty()) {
                    Text("Local automations need access to: ${missing.joinToString()}. Saving does not grant permission.",color=MaterialTheme.colorScheme.error)
                    if(definition.id=="weather.commute_check") Button(onClick={authenticate("Allow commute automation",{work {g.enableWeatherAutomationFromOwner();message="Local commute calculations and reminder creation allowed"}},{message=it})},enabled=!busy) {Text("Allow local weather automation")}
                }
                if(unavailable.isNotEmpty()) Text("Android access: ${unavailable.joinToString()}",style=MaterialTheme.typography.bodySmall)
                if(!notificationsAllowed && "notifications.post" in requestedCapabilities) TextButton(onClick={
                    runCatching {g.context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,g.context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.onFailure {message="Open Android notification settings for SIConnect"}
                }) {Text("Enable SIConnect notifications")}
                Text("Runs as Local automations. Every step checks current permissions, availability, applicable device controls and approval policy. Remote emergency stop pauses bots; local schedules are managed here.",style=MaterialTheme.typography.bodySmall)
                Button(onClick={
                    val saved=runCatching {draft.build(System.currentTimeMillis(),selected)}.getOrElse {message=it.message ?: "Invalid schedule";return@Button}
                    val chosen=definition
                    authenticate("Save ${saved.label}",{work {
                        val installed=g.skills.get(chosen.id)
                        if(installed==null && !g.skills.exists(chosen.id) && chosen==AutomationTemplates.notice()) g.skills.install(chosen)
                        else require(installed==chosen) {"Workflow changed; reopen this form"}
                        require(Schema.validate(chosen.inputs,saved.inputs).isEmpty()) {"Check workflow inputs"}
                        if(chosen.id=="weather.commute_check") WeatherCommute.plan(saved.inputs,System.currentTimeMillis(),null,g.settings.weather.latitude,g.settings.weather.longitude)
                        val result=g.scheduler.put(saved)
                        message="${result.label}: ${result.scheduleState.lowercase().replace('_',' ')}"
                        selectedId=result.id;draftJson=null
                    }},{message=it})
                },enabled=!busy && preview.isSuccess && inputErrors.isEmpty(),modifier=Modifier.fillMaxWidth()) {Text("Save automation")}
                Spacer(Modifier.height(20.dp))
            }
            if(LocalExpandedLayout.current) Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(.48f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {actionInputs();Spacer(Modifier.height(20.dp))}
                Column(Modifier.weight(.52f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {timingInputs();reviewSchedule()}
            } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                actionInputs();timingInputs();reviewSchedule()
            }
        } else if(selected!=null) {
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {
                    Text("${skills.firstOrNull {it.id==selected.skillId}?.let(AutomationTemplates::title) ?: selected.skillId}\n${selected.scheduleState.lowercase().replace('_',' ')}")
                    skills.firstOrNull {it.id==selected.skillId}?.let {Text(BackgroundExecution.description(it,capabilities),style=MaterialTheme.typography.bodySmall)}
                    if(selected.enabled) Text("Next: ${formatTime(selected.nextAtMs,selected.zoneId,locale)}")
                    else Text("Future runs are disabled. Existing active runs remain separate.")
                    selected.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={edit(AutomationDraft.from(selected))},enabled=!busy) {Text("Edit")}
                        Button(onClick={
                            val id=selected.id;val revision=selected.revision;val slot=System.currentTimeMillis()
                            authenticate("Run ${selected.label.ifBlank {"automation"}} now",{work {
                                val idRun=g.scheduler.runNow(id,slot,revision)
                                runCursors=listOf(null);runFilter=AutomationRunFilter.ALL
                                inspectedRunId=idRun;message="Run queued. The saved schedule is unchanged."
                            }},{message=it})
                        },enabled=!busy) {Text("Run now")}
                    }
                    Row {Text("Schedule enabled",Modifier.weight(1f));Switch(selected.enabled,{enabled->
                        val candidate=selected.copy(enabled=enabled)
                        val save={work {g.scheduler.put(candidate)}}
                        if(enabled) authenticate("Enable ${selected.label.ifBlank {"automation"}}",save,{message=it}) else save()
                    },enabled=!busy)}
                    HorizontalDivider()
                    Text("Run history",style=MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        listOf(AutomationRunFilter.ALL to "All runs",AutomationRunFilter.ACTIVE to "Active",
                            AutomationRunFilter.ATTENTION to "Needs attention",AutomationRunFilter.COMPLETED to "Completed").forEach {(value,label)->
                            FilterChip(selected=runFilter==value,onClick={runFilter=value;runCursors=listOf(null);inspectedRunId=null},label={Text(label)})
                        }
                    }
                    if(runsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    runsError?.let {Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick={refresh++}) {Text("Retry history")}}
                    if(!runsLoading && runs.isEmpty() && runsError==null) Text(if(runFilter==AutomationRunFilter.ALL) "No runs yet" else "No runs match this filter")
                }
                items(runs,key={it.runId}) {run->
                    OutlinedCard(onClick={inspectedRunId=if(inspectedRunId==run.runId) null else run.runId}) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(run.state.lowercase().replace('_',' '),style=MaterialTheme.typography.titleSmall)
                        Text(formatTime(run.slotAt,selected.zoneId,locale),style=MaterialTheme.typography.bodySmall)
                        run.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                        if(inspectedRunId==run.runId) {
                            val execution=inspectedRun?.takeIf {it.runId==run.runId}
                            if(execution!=null) {
                                Text(if(execution.payloadExpired) "Result payload expired · original outcome retained" else "${execution.cursor} of ${execution.definition.steps.size} steps recorded",style=MaterialTheme.typography.bodySmall)
                                Text("Started: ${formatTime(execution.startedAtMs,selected.zoneId,locale)}",style=MaterialTheme.typography.bodySmall)
                                Text("Dispatch delay: ${((execution.startedAtMs-run.slotAt).coerceAtLeast(0))/1000} seconds",style=MaterialTheme.typography.bodySmall)
                                if(execution.state==SkillState.AWAITING_APPROVAL) Text("Waiting for owner review. Open pending approvals; this workflow is not complete.")
                                if(execution.definition.id==AutomationTemplates.HEALTH_SKILL)
                                    HealthScanSummary.lines(execution.output,execution.inputs).forEach {Text(it)}
                                if(execution.state in setOf(SkillState.READY,SkillState.RUNNING,SkillState.AWAITING_APPROVAL)) TextButton(onClick={val id=run.runId;work {g.scheduler.cancelRun(id);message="Run cancellation requested"}},enabled=!busy) {Text("Cancel this run")}
                                var showSteps by rememberSaveable(run.runId) {mutableStateOf(false)}
                                TextButton(onClick={showSteps=!showSteps}) {Text(if(showSteps) "Hide step details" else "Step details")}
                                if(showSteps) execution.steps.forEach {step->Text("${step.id}: ${step.state}${step.error?.let {" · $it"} ?: ""}")}
                                var output by rememberSaveable(run.runId) {mutableStateOf(false)}
                                TextButton(onClick={output=!output}) {Text(if(output) "Hide raw result" else "Advanced: raw result")}
                                if(output) {
                                    val text=execution.output.toString()
                                    Text(text.take(6000),style=MaterialTheme.typography.bodySmall)
                                    if(text.length>6000) Text("Preview limited to 6,000 characters; full result remains in the stored workflow.",style=MaterialTheme.typography.bodySmall)
                                }
                            } else if(inspectedLoadedId!=run.runId) {
                                LinearProgressIndicator(Modifier.fillMaxWidth());Text("Loading this run…",style=MaterialTheme.typography.bodySmall)
                            } else {
                                Text(if(run.state=="READY") "Queued durably; waiting for its worker" else "No skill details retained for this run",style=MaterialTheme.typography.bodySmall)
                                if(run.state in setOf("READY","RUNNING")) TextButton(onClick={val id=run.runId;work {g.scheduler.cancelRun(id);message="Queued run cancelled"}},enabled=!busy) {Text("Cancel this run")}
                            }
                        }
                    }}
                }
                item {
                    Text("History page ${runCursors.size} · ${runs.size} runs",style=MaterialTheme.typography.labelSmall)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        TextButton(onClick={runCursors=runCursors.dropLast(1);inspectedRunId=null},enabled=!runsLoading && runCursors.size>1) {Text("Newer runs")}
                        TextButton(onClick={runs.lastOrNull()?.let {runCursors=runCursors+it;inspectedRunId=null}},enabled=!runsLoading && moreRuns) {Text("Older runs")}
                    }
                }
            }
        } else {
            @Composable fun templates() {
                    SectionHeading("Start with a routine")
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        skills.take(4).forEach {skill->NavigationCard(AutomationTemplates.title(skill),
                            when(skill.id) {AutomationTemplates.NOTICE_SKILL->"A reminder on your schedule";AutomationTemplates.HEALTH_SKILL->"Check battery, storage, and device health";"weather.commute_check"->"Prepare for tomorrow’s weather";else->skill.description},
                            {startTemplate(skill)},icon=R.drawable.ic_nav_automations)}
                    }
            }
            Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                if(LocalExpandedLayout.current) Column(Modifier.weight(.44f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {templates()}
                LazyColumn(Modifier.weight(if(LocalExpandedLayout.current) .56f else 1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item {
                        if(!LocalExpandedLayout.current) {templates();Spacer(Modifier.height(20.dp))}
                        SectionHeading("Saved automations")
                        Text("Android can delay scheduled work. Screen actions and approvals need your interaction.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(search,{search=it.take(200)},label={Text("Find saved automations")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            listOf(AutomationFilter.ALL to "All",AutomationFilter.ENABLED to "Enabled",AutomationFilter.PAUSED to "Paused",
                                AutomationFilter.ATTENTION to "Needs attention").forEach {(value,label)->
                                FilterChip(selected=automationFilter==value,onClick={automationFilter=value;automationCursors=listOf(null)},label={Text(label)})
                            }
                        }
                        if(listLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        listError?.let {Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick={refresh++}) {Text("Retry saved automations")}}
                    }
                val healthUpgrade=skills.firstOrNull {AutomationTemplates.upgradeableHealth(it)}
                if(healthUpgrade!=null) item {
                    OutlinedCard {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Upgrade background health scans",style=MaterialTheme.typography.titleMedium)
                        Text("Add persisted warning cooldowns and clearer health results. Existing schedules, notification choices and permissions stay as configured; future runs use the upgraded workflow.")
                        Button(onClick={authenticate("Upgrade device health workflow",{work {
                            g.skills.replaceIfUnchanged(healthUpgrade,AutomationTemplates.health())
                            message="Health scans upgraded. Edit an automation to configure warning notifications."
                        }},{message=it})},enabled=!busy) {Text("Upgrade health snapshot")}
                    }}
                }
                if(!listLoading && automations.isEmpty() && listError==null) item {
                    if(query.isBlank() && automationFilter==AutomationFilter.ALL) EmptyState("Your first routine starts here","Choose a template above or create an automation.",R.drawable.ic_nav_automations)
                    else EmptyState("No matching automations","Try another name or schedule filter.")
                }
                items(automations,key={it.id}) {a->
                    OutlinedCard(onClick={selectedId=a.id;inspectedRunId=null;runCursors=listOf(null);runFilter=AutomationRunFilter.ALL},modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(a.label.ifBlank {a.id},style=MaterialTheme.typography.titleMedium)
                        skills.firstOrNull {it.id==a.skillId}?.let {Text(BackgroundExecution.description(it,capabilities),style=MaterialTheme.typography.bodySmall)}
                        StatusPill(if(a.enabled) a.scheduleState.lowercase().replace('_',' ') else "Paused",if(a.enabled) StatusTone.GOOD else StatusTone.NEUTRAL)
                        if(a.enabled) Text(a.nextAtMs?.let {"Next: ${formatTime(it,a.zoneId,locale)}"} ?: "Next run time unavailable",style=MaterialTheme.typography.bodyMedium)
                        a.lastState?.let {Text("Last run: ${it.lowercase().replace('_',' ')}",style=MaterialTheme.typography.bodySmall)}
                        a.error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                    }}
                }
                item {
                    Text("Page ${automationCursors.size} · ${automations.size} automations",style=MaterialTheme.typography.labelSmall)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        TextButton(onClick={automationCursors=automationCursors.dropLast(1)},enabled=!listLoading && automationCursors.size>1) {Text("Previous automations")}
                        TextButton(onClick={automations.lastOrNull()?.let {automationCursors=automationCursors+it}},enabled=!listLoading && moreAutomations) {Text("More automations")}
                    }
                }
                }
            }
        }
    }
}

private fun formatTime(epoch:Long,zone:String,locale:java.util.Locale):String=runCatching {
    Instant.ofEpochMilli(epoch).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a z",locale))
}.getOrElse {Instant.ofEpochMilli(epoch).toString()}
