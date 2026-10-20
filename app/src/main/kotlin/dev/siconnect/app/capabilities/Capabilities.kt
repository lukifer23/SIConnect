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

private class Operation(override val descriptor: CapabilityDescriptor,
    val available: () -> Availability = { Availability.IMPLEMENTED }, val detail: () -> String? = { null },
    val assessment: ((JsonObject) -> RiskAssessment)? = null,
    val body: suspend (JsonObject, ExecutionContext) -> CapabilityResult) : Capability {
    override fun availability() = available()
    override fun availabilityDetail() = detail()
    override fun assess(args: JsonObject) = assessment?.invoke(args) ?: super.assess(args)
    override suspend fun execute(args: JsonObject, ctx: ExecutionContext) = body(args, ctx)
}

fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
fun JsonObject.long(key: String, default: Long): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: default
private fun native(out: JsonElement) = CapabilityResult(out, BackendKind.NATIVE, "uid=${Process.myUid()}")

fun registerCapabilities(g: RuntimeGraph) {
    val c = g.context
    fun op(id: String, title: String, schema: JsonObject = objectSchema(), risk: RiskClass = RiskClass.READ_ONLY,
           available: () -> Availability = { Availability.IMPLEMENTED }, detail: () -> String? = { null },
           body: suspend (JsonObject, ExecutionContext) -> JsonElement) {
        g.registry.register(Operation(CapabilityDescriptor(id, title = title, description = title, inputSchema = schema,
            backend = BackendKind.NATIVE, risk = risk, sideEffects = if (risk > RiskClass.READ_ONLY) listOf(title) else emptyList()), available, detail,
            body = { a, ctx -> native(body(a, ctx)) }))
    }
    op("device.status", "Device status") { _, _ -> buildJsonObject {
        put("model", Build.MODEL); put("manufacturer", Build.MANUFACTURER); put("android", Build.VERSION.RELEASE)
        put("api", Build.VERSION.SDK_INT); put("deviceId", g.settings.deviceId); put("uptimeMs", SystemClock.elapsedRealtime())
    } }
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
    op("apps.launch", "Launch package", objectSchema { string("package", "Package name", true, maxLength=200) }, RiskClass.LOW_IMPACT) { a, _ ->
        val i = c.packageManager.getLaunchIntentForPackage(a.str("package")) ?: throw CapabilityException("NO_LAUNCH_INTENT", "Package has no launch activity")
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); buildJsonObject { put("dispatched", true) }
    }
    op("alarms.next", "Next owner alarm") { _, _ -> val alarm = c.getSystemService(AlarmManager::class.java).nextAlarmClock
        buildJsonObject { put("triggerAtMs", alarm?.triggerTime?.let(::JsonPrimitive) ?: JsonNull); put("visible", alarm != null) } }
    val notifyAvailable = { if (c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) Availability.IMPLEMENTED else Availability.PERMISSION_REQUIRED }
    op("notifications.post", "Post SIConnect notification", objectSchema { string("title", "Title", true, maxLength=150); string("text", "Body", true, maxLength=1000) }, RiskClass.LOW_IMPACT,
        notifyAvailable, { "Allow SIConnect notifications in Android settings" }) { a, ctx ->
        val n = Notification.Builder(c, "messages").setSmallIcon(R.drawable.ic_stat).setContentTitle(a.str("title")).setContentText(a.str("text")).setAutoCancel(true).build()
        c.getSystemService(NotificationManager::class.java).notify(ctx.requestId.hashCode(), n)
        buildJsonObject { put("notificationId", ctx.requestId.hashCode()) }
    }
    val listenerAvailable = { if (NotificationCaptureService.instance != null) Availability.IMPLEMENTED else Availability.PERMISSION_REQUIRED }
    op("notifications.list", "Active notification metadata", available = listenerAvailable, detail = { "Enable notification listener access; notification bodies are excluded" }) { _, _ ->
        JsonArray((NotificationCaptureService.instance ?: throw CapabilityException("PERMISSION_REQUIRED", "Notification listener disconnected")).metadata().take(200).map { n -> buildJsonObject { put("key", n.key); put("package", n.packageName); put("postTime", n.postTime); put("ongoing", n.isOngoing) } })
    }
    op("notifications.dismiss", "Dismiss notification", objectSchema { string("key", "Exact notification key", true, maxLength=1000) }, RiskClass.MODERATE_IMPACT, listenerAvailable) { a, _ ->
        (NotificationCaptureService.instance ?: throw CapabilityException("PERMISSION_REQUIRED", "Listener disconnected")).cancelNotification(a.str("key")); buildJsonObject { put("dispatched", true) }
    }
    // All privileged tools share one UserService backend. No app-shell fallback exists.
    fun shell(id: String, title: String, schema: JsonObject, risk: RiskClass = RiskClass.HIGH_IMPACT,
              command: (JsonObject) -> ShellRequest) {
        g.registry.register(Operation(CapabilityDescriptor(id, title=title, description=title, inputSchema=schema,
            backend=g.privileged.kind, risk=risk, timeoutMs=125_000,
            sideEffects=if (risk > RiskClass.READ_ONLY) listOf("May change device state under the Shizuku identity") else emptyList()),
            available={ if (g.privileged.available()) Availability.IMPLEMENTED else Availability.PRIVILEGE_REQUIRED }, detail={ g.privileged.detail() },
            assessment={ a -> RiskAssessment(risk, title, target=(a["package"] ?: a["key"])?.jsonPrimitive?.content,
                shellCommand=if (id == "shell.execute") a.str("command") else null,
                sideEffects=if (risk > RiskClass.READ_ONLY) listOf(title) else emptyList()) },
            body={ a, ctx ->
                val result = g.privileged.execute(command(a).copy(execId=ctx.requestId))
                if (result.cancelled || result.timedOut || result.error != null)
                    throw CapabilityException(if (result.timedOut) "TIMEOUT" else "SHELL_INTERRUPTED", result.error ?: "Shell interrupted; external effects may have occurred")
                if (result.exitCode != 0) throw CapabilityException("SHELL_EXIT", "Command exited ${result.exitCode}; external effects may have occurred")
                CapabilityResult(AppJson.encodeToJsonElement(ShellResult.serializer(), result), g.privileged.kind, result.identity)
            }))
    }
    shell("shell.execute", "Execute arbitrary privileged shell", objectSchema {
        string("command", "Exact command; arbitrary commands require approval by default", true, maxLength=16000)
        string("cwd", "Working directory", maxLength=1000); obj("env", "Environment variables")
        string("stdin", "UTF-8 input", maxLength=65536); integer("timeoutMs", "Timeout", min=1, max=120000)
        integer("maxOutputBytes", "Per-stream output limit; base64 preserves binary", min=1, max=131072)
    }) { a -> ShellRequest("", command=a.str("command"), cwd=(a["cwd"] as? JsonPrimitive)?.content,
        env=(a["env"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
        stdin=(a["stdin"] as? JsonPrimitive)?.content, timeoutMs=a.long("timeoutMs", 30000), maxOutputBytes=a.long("maxOutputBytes", 65536).toInt()) }
    shell("shell.identity", "Actual privileged identity", objectSchema(), RiskClass.READ_ONLY) { ShellRequest("", argv=listOf("/system/bin/id")) }
    shell("shell.health", "Privileged process health", objectSchema(), RiskClass.READ_ONLY) { ShellRequest("", argv=listOf("/system/bin/id")) }
    shell("apps.list_privileged", "Packages in Android user 0", objectSchema(), RiskClass.READ_ONLY) { ShellRequest("", argv=listOf("/system/bin/pm", "list", "packages", "--user", "0")) }
    val pkgSchema = objectSchema { string("package", "Exact package name in user 0", true, maxLength=200) }
    fun pkg(a: JsonObject): String = a.str("package").also { require(it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) }
    shell("apps.inspect_privileged", "Inspect package", pkgSchema, RiskClass.READ_ONLY) { ShellRequest("", argv=listOf("/system/bin/dumpsys", "package", pkg(it))) }
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
