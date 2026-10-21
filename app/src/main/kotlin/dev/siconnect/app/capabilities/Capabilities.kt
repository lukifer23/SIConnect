package dev.siconnect.app.capabilities

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.*
import android.os.*
import dev.siconnect.app.R
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.backend.*
import dev.siconnect.app.data.AppJson
import dev.siconnect.app.notifications.NotificationCaptureService
import dev.siconnect.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private class Operation(private val baseDescriptor: CapabilityDescriptor,
    val available: () -> Availability = { Availability.IMPLEMENTED }, val detail: () -> String? = { null },
    val assessment: ((JsonObject) -> RiskAssessment)? = null,
    val selectedBackend: (() -> BackendKind)? = null,
    val body: suspend (JsonObject, ExecutionContext) -> CapabilityResult) : Capability {
    override val descriptor: CapabilityDescriptor get() = selectedBackend?.let { baseDescriptor.copy(backend=it()) } ?: baseDescriptor
    override fun availability() = available()
    override fun availabilityDetail() = detail()
    override fun assess(args: JsonObject) = assessment?.invoke(args) ?: super.assess(args)
    override suspend fun execute(args: JsonObject, ctx: ExecutionContext) = body(args, ctx)
}

fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
fun JsonObject.long(key: String, default: Long): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: default
private fun native(out: JsonElement) = CapabilityResult(out, BackendKind.NATIVE, "uid=${Process.myUid()}")

fun registerCapabilities(g: RuntimeGraph) {
    registerFileCapabilities(g)
    registerDocumentCapabilities(g)
    registerCalendarCapabilities(g)
    registerSemanticUiCapabilities(g)
    registerNotificationActionCapabilities(g)
    registerUiCapabilities(g.registry,g.ui)
    g.registry.register(RequestOutputCapability(g))
    g.registry.register(PackageInspectionCapability(g.context))
    val c = g.context
    fun op(id: String, title: String, schema: JsonObject = objectSchema(), risk: RiskClass = RiskClass.READ_ONLY,
           available: () -> Availability = { Availability.IMPLEMENTED }, detail: () -> String? = { null },
           body: suspend (JsonObject, ExecutionContext) -> JsonElement) {
        g.registry.register(Operation(CapabilityDescriptor(id, title = title, description = title + if(id=="apps.launch") ". Opens the target app on the shared device screen." else ". Runs without opening an Activity or navigating the shared screen.", inputSchema = schema,
            interaction=if(id=="apps.launch") InteractionMode.SCREEN else InteractionMode.BACKGROUND,backend = BackendKind.NATIVE, risk = risk, sideEffects = if (risk > RiskClass.READ_ONLY) listOf(title) else emptyList()), available, detail,
            body = { a, ctx -> native(body(a, ctx)) }))
    }
    op("requests.get", "Inspect your own request", objectSchema { string("requestId", "Request ID", true, maxLength=200) }) { a, ctx ->
        val record=g.requests.get(a.str("requestId")) ?: throw CapabilityException("NOT_FOUND", "Unknown request")
        if(record.agentId!=ctx.agentId) throw CapabilityException("POLICY_DENIED", "Request belongs to another agent")
        AppJson.encodeToJsonElement(RequestRecord.serializer(),record)
    }
    op("requests.recent", "Find your recent original request IDs after a lost response", objectSchema {
        integer("limit","Maximum recent metadata rows",min=1,max=50)
    }) {a,ctx -> JsonArray(g.requests.historyPage(limit=a.long("limit",20).toInt(),agentId=ctx.agentId).map {r->buildJsonObject {
        put("requestId",r.requestId);put("capabilityId",r.capabilityId);put("state",r.state.name);put("createdAtMs",r.createdAtMs);put("updatedAtMs",r.updatedAtMs)
    }}) }
    op("requests.cancel", "Cancel your own request", objectSchema { string("requestId", "Request ID", true, maxLength=200) }, RiskClass.LOW_IMPACT) { a,ctx ->
        val record=g.requests.get(a.str("requestId")) ?: throw CapabilityException("NOT_FOUND", "Unknown request")
        if(record.agentId!=ctx.agentId) throw CapabilityException("POLICY_DENIED", "Request belongs to another agent")
        AppJson.encodeToJsonElement(RequestRecord.serializer(),requireNotNull(g.engine.cancel(record.requestId,ctx.agentId)))
    }
    op("device.status", "Device status") { _, _ -> buildJsonObject {
        put("model", Build.MODEL); put("manufacturer", Build.MANUFACTURER); put("android", Build.VERSION.RELEASE)
        put("api", Build.VERSION.SDK_INT); put("deviceId", g.settings.deviceId); put("uptimeMs", SystemClock.elapsedRealtime())
    } }
    op("device.capabilities", "Live capabilities and this agent’s scope") { _,ctx ->
        val grant=g.agents.grant(ctx.agentId)
        JsonArray(g.registry.statuses().map {status -> buildJsonObject {
            val d=status.descriptor
            put("id",d.id);put("title",d.title);put("backend",d.backend.name);put("availability",status.availability.name)
            put("interaction",d.interaction.name);put("risk",d.risk.name)
            put("scopeGranted",grant!=null && !grant.revoked && (grant.expiresAtMs?.let {it>System.currentTimeMillis()} ?: true) && grant.scopes.any {PolicyEngine.globMatches(it,d.id)} && grant.denied.none {PolicyEngine.globMatches(it,d.id)})
            status.detail?.let {put("detail",it)}
        }})
    }
    op("device.runtime", "SIConnect process and connection resource measurements") { _, _ ->
        val memory=Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val dbFile=c.getDatabasePath("siconnect.db")
        buildJsonObject {
            put("appVersion",dev.siconnect.app.BuildConfig.VERSION_NAME);put("appVersionCode",dev.siconnect.app.BuildConfig.VERSION_CODE)
            put("observedAtMs",System.currentTimeMillis());put("elapsedRealtimeMs",SystemClock.elapsedRealtime())
            put("processStartedAtElapsedMs",Process.getStartElapsedRealtime());put("processCpuMs",Process.getElapsedCpuTime())
            put("appPssKiB",memory.totalPss);put("appPrivateDirtyKiB",memory.totalPrivateDirty)
            put("appUidRxBytes",android.net.TrafficStats.getUidRxBytes(Process.myUid()))
            put("appUidTxBytes",android.net.TrafficStats.getUidTxBytes(Process.myUid()))
            put("databaseBytes",dbFile.length());put("databaseWalBytes",java.io.File(dbFile.path+"-wal").length())
            try {
                val storage=c.getSystemService(android.app.usage.StorageStatsManager::class.java)
                    .queryStatsForUid(c.applicationInfo.storageUuid,Process.myUid())
                put("appCodeBytes",storage.appBytes);put("appDataBytes",storage.dataBytes);put("appCacheBytes",storage.cacheBytes)
            } catch(e:Exception) {put("storageStatsError",e.javaClass.simpleName)}
            put("screenInteractive",c.getSystemService(PowerManager::class.java).isInteractive)
            put("keyguardLocked",c.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            put("deviceLocked",c.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            put("charging",c.getSystemService(BatteryManager::class.java).isCharging)
            put("gatewayState",g.gateway.state.value.name);put("privilegedBackend",g.privileged.kind.name)
            put("privilegedAvailable",g.privileged.available())
            put("gatewayConnectEvents",g.db.readableDatabase.rawQuery("SELECT count(*) FROM events WHERE event_type='gateway.connected'",null).use {it.moveToFirst();it.getLong(0)})
        }
    }
    op("device.battery", "Battery") { _, _ ->
        val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val manager = c.getSystemService(BatteryManager::class.java)
        buildJsonObject { put("percent", manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)); put("charging", manager.isCharging)
            b?.let { put("temperatureC", it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0); put("status", it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) } }
    }
    op("device.storage", "App volume storage") { _, _ -> val s = StatFs(c.filesDir.path); buildJsonObject { put("totalBytes", s.totalBytes); put("availableBytes", s.availableBytes) } }
    op("device.memory", "Memory") { _, _ -> val m = ActivityManager.MemoryInfo(); c.getSystemService(ActivityManager::class.java).getMemoryInfo(m)
        buildJsonObject { put("totalBytes", m.totalMem); put("availableBytes", m.availMem); put("lowMemory", m.lowMemory) } }
    op("device.network", "Network state") { _, _ -> val cm = c.getSystemService(ConnectivityManager::class.java); val n = cm.getNetworkCapabilities(cm.activeNetwork)
        buildJsonObject { put("connected", n != null); put("validated", n?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            put("wifi", n?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true); put("cellular", n?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) } }
    op("device.thermal", "Thermal status") { _, _ -> buildJsonObject { put("status", c.getSystemService(PowerManager::class.java).currentThermalStatus) } }
    op("apps.list", "Visible installed packages", objectSchema { integer("limit", "Maximum packages", min=1, max=1000) }) { a, _ ->
        val list = (if (Build.VERSION.SDK_INT >= 33) c.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)) else c.packageManager.getInstalledPackages(0)).sortedBy { it.packageName }.take(a.long("limit", 100).toInt())
        buildJsonObject { put("user", "current Android user; no Secure Folder traversal"); put("packages", JsonArray(list.map { p -> buildJsonObject { put("packageName", p.packageName); put("versionName", p.versionName); put("versionCode", p.longVersionCode) } })) }
    }
    op("apps.inspect", "Inspect visible package", objectSchema { string("package", "Package name", true, maxLength=200) }) { a, _ ->
        val p = (if (Build.VERSION.SDK_INT >= 33) c.packageManager.getPackageInfo(a.str("package"), PackageManager.PackageInfoFlags.of(0)) else c.packageManager.getPackageInfo(a.str("package"), 0))
        buildJsonObject { put("packageName", p.packageName); put("versionName", p.versionName); put("versionCode", p.longVersionCode); put("enabled", p.applicationInfo?.enabled) }
    }
    op("apps.launch", "Launch package", objectSchema { string("package", "Package name", true, maxLength=200); string("leaseId", "Optional owned UI lease", maxLength=100) }, RiskClass.LOW_IMPACT) { a, ctx ->
        val i = c.packageManager.getLaunchIntentForPackage(a.str("package")) ?: throw CapabilityException("NO_LAUNCH_INTENT", "Package has no launch activity")
        g.ui.withScreen(ctx,a.str("package"),(a["leaseId"] as? JsonPrimitive)?.content) {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); buildJsonObject { put("dispatched", true);put("verifiedForeground",false) }
        }
    }
    op("alarms.next", "Next owner alarm") { _, _ -> val alarm = c.getSystemService(AlarmManager::class.java).nextAlarmClock
        buildJsonObject { put("triggerAtMs", alarm?.triggerTime?.let(::JsonPrimitive) ?: JsonNull); put("visible", alarm != null) } }
    val notifyAvailable = { if (c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) Availability.IMPLEMENTED else Availability.PERMISSION_REQUIRED }
    op("notifications.post", "Post SIConnect notification", objectSchema { string("title", "Title", true, maxLength=150); string("text", "Body", true, maxLength=1000); string("deduplicationKey","Optional per-agent stable alert key; never repeats a previously claimed post",maxLength=200) }, RiskClass.LOW_IMPACT,
        notifyAvailable, { "Allow SIConnect notifications in Android settings" }) { a, ctx ->
        postNotification(g,a,ctx)
    }
    val listenerAvailable = { if (NotificationCaptureService.instance != null) Availability.IMPLEMENTED else Availability.PERMISSION_REQUIRED }
    op("notifications.list", "Active notification metadata", available = listenerAvailable, detail = { "Enable notification listener access; notification bodies are excluded" }) { _, _ ->
        JsonArray((NotificationCaptureService.instance ?: throw CapabilityException("PERMISSION_REQUIRED", "Notification listener disconnected")).metadata().take(200).map { n -> buildJsonObject { put("key", n.key); put("package", n.packageName); put("postTime", n.postTime); put("ongoing", n.isOngoing) } })
    }
    op("notifications.dismiss", "Dismiss notification", objectSchema { string("key", "Exact notification key", true, maxLength=1000) }, RiskClass.MODERATE_IMPACT, listenerAvailable) { a, _ ->
        (NotificationCaptureService.instance ?: throw CapabilityException("PERMISSION_REQUIRED", "Listener disconnected")).cancelNotification(a.str("key")); buildJsonObject { put("dispatched", true) }
    }
    // All privileged tools use the same explicitly selected backend.
    fun shell(id: String, title: String, schema: JsonObject, risk: RiskClass = RiskClass.HIGH_IMPACT,
              description:String=title,
              command: (JsonObject) -> ShellRequest) {
        g.registry.register(Operation(CapabilityDescriptor(id, title=title, description=description, inputSchema=schema,
            backend=g.privileged.kind, risk=risk, timeoutMs=125_000,interaction=if(risk==RiskClass.READ_ONLY) InteractionMode.BACKGROUND else InteractionMode.MAY_OPEN_SCREEN,
            sideEffects=if (risk > RiskClass.READ_ONLY) listOf("May change device state under the owner-selected privileged identity") else emptyList()),
            available={ if (g.privileged.available()) Availability.IMPLEMENTED else Availability.PRIVILEGE_REQUIRED }, detail={ g.privileged.detail() },
            assessment={ a -> RiskAssessment(risk, title, target=(a["package"] ?: a["key"])?.jsonPrimitive?.content,
                shellCommand=if (id == "shell.execute") a.str("command") else null,
                sideEffects=if (risk > RiskClass.READ_ONLY) listOf(title) else emptyList()) },
            selectedBackend={ g.privileged.kind },
            body={ a, ctx ->
                val result = g.privileged.execute(command(a).copy(execId=ctx.requestId))
                val code=when {result.timedOut->"TIMEOUT";result.cancelled->"CANCELLED";result.error!=null->"SHELL_ERROR";result.exitCode!=0->"SHELL_EXIT";else->null}
                CapabilityResult(AppJson.encodeToJsonElement(ShellResult.serializer(), result), g.privileged.kind, result.identity,
                    verification=if(code==null) VerificationStatus.NOT_APPLICABLE else VerificationStatus.FAILED,
                    verificationDetail=code?.let { "Shell did not finish successfully; effects may have occurred" },errorCode=code)
            }))
    }
    shell("shell.execute", "Execute arbitrary privileged shell", objectSchema {
        string("command", "Exact command; arbitrary commands require approval by default", true, maxLength=16000)
        string("cwd", "Working directory", maxLength=1000); obj("env", "Environment variables")
        string("stdin", "UTF-8 input", maxLength=65536); integer("timeoutMs", "Timeout", min=1, max=120000)
        integer("maxOutputBytes", "Per-stream captured-byte limit; output is drained and previews may be bounded further for transport", min=1, max=131072)
        string("outputEncoding", "legacy preserves small text and base64 fields; text avoids binary duplication; base64 preserves exact captured bytes", enum=listOf("legacy","text","base64"))
    }) { a -> ShellRequest("", command=a.str("command"), cwd=(a["cwd"] as? JsonPrimitive)?.content,
        env=(a["env"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
        stdin=(a["stdin"] as? JsonPrimitive)?.content, timeoutMs=a.long("timeoutMs", 30000), maxOutputBytes=a.long("maxOutputBytes", 65536).toInt(), outputEncoding=(a["outputEncoding"] as? JsonPrimitive)?.content ?: "legacy") }
    shell("shell.identity", "Actual privileged identity", objectSchema(), RiskClass.READ_ONLY) { ShellRequest("", argv=listOf("/system/bin/id")) }
    shell("shell.health", "Privileged process health", objectSchema(), RiskClass.READ_ONLY) { ShellRequest("", argv=listOf("/system/bin/id")) }
    shell("apps.list_privileged", "Packages in Android user 0", objectSchema(), RiskClass.READ_ONLY) { ShellRequest("", argv=listOf("/system/bin/pm", "list", "packages", "--user", "0")) }
    val pkgSchema = objectSchema { string("package", "Exact package name in user 0", true, maxLength=200) }
    fun pkg(a: JsonObject): String = a.str("package").also { require(it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) }
    shell("apps.inspect_privileged", "Raw package diagnostic dump", pkgSchema, RiskClass.READ_ONLY,
        description="Raw dumpsys package diagnostics for explicit troubleshooting. Output can be large or truncated and may include multiple Android user states. For installation, version, enabled state and installer verification, use apps_inspect_detailed with packageName and user 0 instead. A successful dump exit is not verification of complete metadata.") { ShellRequest("", argv=listOf("/system/bin/dumpsys", "package", pkg(it))) }
    shell("apps.uninstall", "Uninstall package from user 0", pkgSchema) { ShellRequest("", argv=listOf("/system/bin/pm", "uninstall", "--user", "0", pkg(it))) }
    shell("apps.install", "Install APK into user 0", objectSchema { string("path", "APK path readable by shell", true, maxLength=1000) }) {
        val path=it.str("path"); require(path.startsWith("/") && !path.contains('\u0000'))
        ShellRequest("", argv=listOf("/system/bin/pm", "install", "--user", "0", "-r", path), timeoutMs=120000)
    }
    val settingsSchema = objectSchema { string("namespace", "Settings namespace", true, enum=listOf("system","secure","global")); string("key", "Key", true, maxLength=200) }
    shell("settings.get", "Read setting in user 0", settingsSchema, RiskClass.READ_ONLY) { ShellRequest("", argv=listOf("/system/bin/settings","--user","0","get",it.str("namespace"),it.str("key"))) }
    shell("settings.put", "Write setting in user 0", objectSchema { string("namespace", "Namespace", true, enum=listOf("system","secure","global")); string("key", "Key", true, maxLength=200); string("value", "Value", true, maxLength=4000) }) { ShellRequest("", argv=listOf("/system/bin/settings","--user","0","put",it.str("namespace"),it.str("key"),it.str("value"))) }
    shell("system.dumpsys", "Read system service dump", objectSchema { string("service", "Service name", true, maxLength=100) }, RiskClass.READ_ONLY) {
        val service=it.str("service"); require(service.matches(Regex("[a-zA-Z0-9_.]+")))
        ShellRequest("", argv=listOf("/system/bin/dumpsys", service))
    }
    shell("system.command", "Privileged cmd service operation", objectSchema { stringArray("argv", "Arguments to Android cmd", true) }) {
        ShellRequest("", argv=listOf("/system/bin/cmd") + it.getValue("argv").jsonArray.map { v -> v.jsonPrimitive.content })
    }
}
