package dev.siconnect.app.capabilities

import android.app.KeyguardManager
import android.os.Process
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.accessibility.SemanticAccessibilityService
import dev.siconnect.core.*
import kotlinx.serialization.json.*

fun registerSemanticUiCapabilities(g: RuntimeGraph) {
    fun configured()=android.provider.Settings.Secure.getString(g.context.contentResolver,android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        .orEmpty().split(':').any {android.content.ComponentName.unflattenFromString(it)==android.content.ComponentName(g.context,SemanticAccessibilityService::class.java)}
    fun service() = SemanticAccessibilityService.instance ?: if(configured()) throw CapabilityException("ACCESSIBILITY_DISCONNECTED", "Android access is enabled but the service is disconnected; restore SIConnect in Accessibility settings",noSideEffects=true)
        else throw CapabilityException("ACCESSIBILITY_REQUIRED", "Enable SIConnect accessibility access in Android settings; sideload restrictions may require owner action", noSideEffects = true)
    fun ObjectSchemaBuilder.target() { string("packageName", "Exact foreground Android package expected", true, maxLength = 200) }
    fun ObjectSchemaBuilder.selector() {
        string("text", "Visible text; exact unless contains=true", maxLength = 500); string("description", "Content description", maxLength = 500)
        string("resourceId", "Full resource ID such as package:id/control", maxLength = 300); string("className", "Exact Android accessibility class", maxLength = 200)
        boolean("contains", "Substring match for text/description; default false")
        for (field in listOf("clickable", "editable", "scrollable", "enabled", "checkable", "checked", "selected", "focused")) boolean(field, "Require $field state")
        integer("maxScanNodes", "Maximum visited nodes; a smaller budget may return an incomplete search", min = 1, max = 1000)
    }
    fun selection(a: JsonObject) = JsonObject(a.filterKeys { it in setOf("text", "description", "resourceId", "className", "contains", "clickable", "editable", "scrollable", "enabled", "checkable", "checked", "selected", "focused", "maxScanNodes") })
    fun ObjectSchemaBuilder.mutation(node: Boolean = true) {
        target(); string("leaseId", "Existing owned ui.lease_acquire lease for this package", true, maxLength = 100)
        integer("windowId", "Exact window ID from a fresh semantic snapshot", true, min = 0)
        if (node) { string("snapshotId", "Fresh snapshot bound to this agent/window", true, maxLength = 100); string("nodeRef", "Node reference returned by that snapshot", true, maxLength = 30) }
    }
    val output = objectSchema {
        string("serviceState","Configured, connected, locked and foreground readiness distinguished; not a grant decision")
        boolean("foregroundWindowAvailable","An active accessible root exists while unlocked")
        boolean("androidServiceCrashStateKnown","False: Android's private service crash list requires external diagnostics")
        integer("eventCount","Content-free invalidating event count since service binding");integer("lastEventType","Last Android accessibility event type")
        integer("lastEventAtElapsedMs","Last invalidating event monotonic time");integer("staleReferenceCount","Explicit stale-reference refusals since binding");integer("retainedSnapshots","Bounded snapshot count")
        boolean("connected", "Service connection state"); boolean("enabledInSettings", "Android reports service enabled"); boolean("deviceLocked", "Android keyguard state")
        string("serviceGeneration", "Runtime service generation"); integer("snapshotTtlMs", "Reference lifetime"); integer("maxScannedNodes", "Traversal bound"); integer("maxReturnedNodes", "Result bound")
        boolean("protectedControlsAutomatable", "Always false for this semantic backend"); boolean("eventDrivenWaits", "Waits use Android events")
        string("snapshotId", "Agent-bound snapshot identity"); integer("windowId", "Active window"); string("packageName", "Expected target"); integer("epoch", "Accessibility event generation")
        prop("nodes", false, buildJsonObject { put("type", "array"); put("maxItems", 200); putJsonObject("items") { put("type", "object") } })
        integer("scannedNodes", "Visited nodes"); boolean("limitReached", "Returned-node limit reached"); boolean("scanIncomplete", "Traversal budget omitted nodes"); boolean("passwordContentIncluded", "Always false")
        boolean("hasMoreMatches", "At least one further matching node was observed"); integer("scanNodeLimit", "Actual traversal bound")
        integer("observedAtMs", "Wall-clock observation timestamp"); integer("observedAtElapsedMs", "Monotonic observation timestamp"); integer("scanDurationMs", "Actual bounded traversal duration")
        boolean("actionAccepted", "Android accepted this specific interaction; not a business outcome guarantee"); string("action", "Performed interaction"); integer("previousWindowId", "Window checked before action")
        boolean("textReadbackVerified", "Editable text matched after action"); boolean("businessOutcomeVerified", "Generic interactions cannot confirm sends/installations/deletions")
        string("nextStep", "Safe next verification action"); boolean("matched", "Wait selector found a node"); boolean("timedOut", "Wait deadline elapsed"); obj("snapshot", "Matching bounded snapshot")
    }
    fun register(id: String, title: String, input: JsonObject, risk: RiskClass = RiskClass.READ_ONLY, body: suspend (JsonObject, ExecutionContext) -> JsonObject) {
        val status = id == "ui.status"
        g.registry.register(object : Capability {
            override val descriptor = CapabilityDescriptor(id, title = title,
                description = if (status) "Reports actual SIConnect accessibility special-access and service state without opening a screen, including when access is disabled." else "$title using Android AccessibilityService semantic nodes. Requires owner-enabled accessibility access and an unlocked foreground target. Protected owner/permission/biometric controls and password input are excluded. Mutation requires an existing owned UI lease. Action acceptance does not verify a message send or other app-specific outcome. Bounded requested UI metadata is persisted with the request result; accessibility events contain no captured UI content.",
                inputSchema = input, outputSchema = output, backend = BackendKind.ACCESSIBILITY, risk = risk,
                permissions = listOf("android.settings.ACCESSIBILITY_SETTINGS"), interaction = if (status) InteractionMode.BACKGROUND else InteractionMode.SCREEN,
                timeoutMs = if (id == "ui.wait_for") 65_000 else 30_000,
                sideEffects = if (risk > RiskClass.READ_ONLY) listOf("Changes the shared screen or app controls; generic clicks can communicate externally or delete content") else emptyList())
            override fun availability() = if (status || SemanticAccessibilityService.instance != null) Availability.IMPLEMENTED else if(configured()) Availability.TEMPORARILY_UNAVAILABLE else Availability.PERMISSION_REQUIRED
            override fun availabilityDetail() = when(availability()) {
                Availability.TEMPORARILY_UNAVAILABLE->"Android access is enabled but the service is disconnected. Open Accessibility settings to restore it; app diagnostics cannot read Android's private service crash list."
                Availability.PERMISSION_REQUIRED->"Owner must enable SIConnect accessibility access in Android's special settings"
                else->null
            }
            override fun assess(args: JsonObject) = RiskAssessment(risk, title, args["packageName"]?.jsonPrimitive?.content, descriptor.sideEffects, externalCommunication = id == "ui.click")
            override suspend fun execute(args: JsonObject, ctx: ExecutionContext): CapabilityResult {
                val action = risk > RiskClass.READ_ONLY
                val out = if (action) g.ui.withScreen(ctx, args.str("packageName"), args.str("leaseId")) { body(args, ctx) } else body(args, ctx)
                val verified = id == "ui.set_text" && out["textReadbackVerified"]?.jsonPrimitive?.booleanOrNull == true
                val failedReadback = id == "ui.set_text" && !verified
                return CapabilityResult(out, BackendKind.ACCESSIBILITY, "uid=${Process.myUid()}", if (failedReadback) VerificationStatus.FAILED else if (verified) VerificationStatus.VERIFIED else if (action) VerificationStatus.NOT_VERIFIED else VerificationStatus.NOT_APPLICABLE,
                    if (failedReadback) "The edit was accepted but readback did not match; inspect the existing request and actual field before retrying" else if (verified) "Editable text matched local readback; no send or server acknowledgement inferred" else if (action) "Android accepted the interaction; inspect/wait for the expected application postcondition" else null,
                    if (failedReadback) "TEXT_READBACK_UNVERIFIED" else null)
            }
        })
    }
    register("ui.status", "Inspect semantic accessibility availability", objectSchema()) { _, _ ->
        val enabled = configured()
        val base = SemanticAccessibilityService.instance?.status() ?: buildJsonObject { put("connected", false); put("deviceLocked", g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked); put("protectedControlsAutomatable", false); put("eventDrivenWaits", true) }
        JsonObject(base + mapOf("enabledInSettings" to JsonPrimitive(enabled),"androidServiceCrashStateKnown" to JsonPrimitive(false),
            "serviceState" to JsonPrimitive(dev.siconnect.app.accessibility.SemanticServiceState.classify(enabled,
                base["connected"]?.jsonPrimitive?.booleanOrNull==true,base["deviceLocked"]?.jsonPrimitive?.booleanOrNull==true,
                base["foregroundWindowAvailable"]?.jsonPrimitive?.booleanOrNull==true))))
    }
    register("ui.inspect", "Inspect a bounded semantic foreground snapshot", objectSchema { target(); integer("limit", "Returned node limit", min = 1, max = 200); integer("maxScanNodes", "Maximum visited nodes", min = 1, max = 1000) }) { a, ctx -> service().inspect(ctx.agentId, a.str("packageName"), selection(a), a.long("limit", 100).toInt()) }
    register("ui.find", "Find semantic controls using bounded explicit selectors", objectSchema { target(); selector(); integer("limit", "Returned match limit", min = 1, max = 100) }) { a, ctx ->
        val query = selection(a)
        if (query.keys.none { it !in setOf("contains", "maxScanNodes") }) throw CapabilityException("SELECTOR_REQUIRED", "Supply at least one explicit semantic selector", noSideEffects = true)
        service().inspect(ctx.agentId, a.str("packageName"), query, a.long("limit", 20).toInt())
    }
    register("ui.wait_for", "Wait for semantic state using accessibility events", objectSchema { target(); selector(); integer("timeoutMs", "Bounded event-driven wait", min = 100, max = 60000) }) { a, ctx ->
        val query = selection(a)
        if (query.keys.none { it !in setOf("contains", "maxScanNodes") }) throw CapabilityException("SELECTOR_REQUIRED", "Supply an explicit state selector", noSideEffects = true)
        service().waitFor(ctx.agentId, a.str("packageName"), query, a.long("timeoutMs", 10000))
    }
    register("ui.click", "Activate an exact semantic node", objectSchema { mutation() }, RiskClass.HIGH_IMPACT) { a, ctx -> service().action(ctx.agentId, a.str("packageName"), a.str("snapshotId"), a.str("nodeRef"), a.long("windowId", -1).toInt(), "click", null) }
    register("ui.set_text", "Set an editable field without pressing send", objectSchema { mutation(); string("text", "Replacement text; password fields are rejected", true, maxLength = 4000) }, RiskClass.MODERATE_IMPACT) { a, ctx -> service().action(ctx.agentId, a.str("packageName"), a.str("snapshotId"), a.str("nodeRef"), a.long("windowId", -1).toInt(), "set_text", a.str("text")) }
    register("ui.scroll", "Scroll an exact semantic container", objectSchema { mutation(); string("direction", "Semantic scroll direction", true, enum = listOf("forward", "backward")) }, RiskClass.LOW_IMPACT) { a, ctx -> service().action(ctx.agentId, a.str("packageName"), a.str("snapshotId"), a.str("nodeRef"), a.long("windowId", -1).toInt(), "scroll_${a.str("direction")}", null) }
    register("ui.back", "Navigate back from the exact leased window", objectSchema { mutation(false) }, RiskClass.LOW_IMPACT) { a, _ -> service().global(a.str("packageName"), a.long("windowId", -1).toInt(), false) }
    register("ui.home", "Navigate home from the exact leased window", objectSchema { mutation(false) }, RiskClass.LOW_IMPACT) { a, _ -> service().global(a.str("packageName"), a.long("windowId", -1).toInt(), true) }
}
