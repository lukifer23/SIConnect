package dev.siconnect.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.siconnect.app.BuildConfig
import dev.siconnect.app.graph
import dev.siconnect.core.CapabilityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID

/** Native semantic interaction. All entry points run on main; capability layer owns policy and leases. */
class SemanticAccessibilityService : AccessibilityService() {
    private val processGeneration = UUID.randomUUID().toString()
    private val changes = MutableStateFlow(0L)
    private var connected = false
    private data class Reference(val path: List<Int>, val fingerprint: String)
    private data class Snapshot(val id: String, val agent: String, val target: String, val window: Int, val epoch: Long, val createdElapsed: Long, val refs: Map<String, Reference>)
    private val snapshots = linkedMapOf<String, Snapshot>()
    private var debugFixture: WeakReference<Activity>? = null

    override fun onServiceConnected() { connected = true; instance = this; _connectionState.value = true; invalidate(); catalogChanged() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event payload/text is never logged or retained. A content or window change invalidates refs.
        if (event != null && event.eventType and INVALIDATING_EVENTS != 0) invalidate()
    }
    override fun onInterrupt() { invalidate() }
    override fun onUnbind(intent: Intent?): Boolean { disconnected(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnected(); super.onDestroy() }
    private fun disconnected() {
        connected = false
        val current = instance === this
        if (current) { instance = null; _connectionState.value = false }
        invalidate()
        if (current) catalogChanged()
    }
    private fun catalogChanged() { runCatching { val g = graph; g.scope.launch { g.ready.await(); g.gateway.refreshCatalog() } } }
    private fun invalidate() { snapshots.clear(); changes.value = changes.value + 1 }
    private fun error(code: String, message: String): Nothing = throw CapabilityException(code, message, noSideEffects = true)
    private fun guard(target: String, windowId: Int? = null): AccessibilityNodeInfo {
        if (!connected || instance !== this) error("ACCESSIBILITY_DISCONNECTED", "Accessibility service disconnected; restore it in Android settings")
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) error("SCREEN_LOCKED", "Semantic UI actions require an unlocked device; background native tools remain independent")
        val fixture = debugFixture?.get()?.takeIf { BuildConfig.DEBUG && it.hasWindowFocus() && !it.isFinishing && it.javaClass.name == FIXTURE_ACTIVITY }
        if (SemanticUiRules.protectedPackage(target) || target == packageName && fixture == null)
            error("PROTECTED_UI", "Owner management, permission, lock-screen and biometric surfaces cannot be automated by this backend")
        val root = rootInActiveWindow ?: error("NO_ACTIVE_WINDOW", "No accessible active application window is available")
        if (root.packageName?.toString() != target) error("TARGET_NOT_FOREGROUND", "Expected package is not the active application; no action was performed")
        if (windowId != null && root.windowId != windowId) error("WINDOW_CHANGED", "Active window changed; inspect again before acting")
        if (root.isPassword) error("PROTECTED_UI", "Protected input cannot be inspected or automated")
        return root
    }

    suspend fun status(): JsonObject = withContext(Dispatchers.Main.immediate) { buildJsonObject {
        put("connected", connected && instance === this@SemanticAccessibilityService); put("serviceGeneration", processGeneration)
        put("deviceLocked", getSystemService(KeyguardManager::class.java).isDeviceLocked)
        put("snapshotTtlMs", SNAPSHOT_TTL); put("maxScannedNodes", MAX_SCAN); put("maxReturnedNodes", MAX_RETURN)
        put("protectedControlsAutomatable", false); put("eventDrivenWaits", true)
        put("observedAtMs", System.currentTimeMillis()); put("observedAtElapsedMs", SystemClock.elapsedRealtime())
    } }

    private fun fingerprint(node: AccessibilityNodeInfo): String {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        // Exact semantic identity, including bounded text hash. Never keep an AccessibilityNodeInfo.
        val value = listOf(node.packageName, node.windowId, node.viewIdResourceName, node.className,
            node.text?.take(4000), node.contentDescription?.take(4000), bounds.flattenToString(), node.isPassword,
            node.isClickable, node.isEditable, node.isEnabled, node.isVisibleToUser, node.isCheckable,
            node.isChecked, node.isSelected, node.isScrollable, node.isFocusable, node.isFocused,
            node.hashCode(), if (Build.VERSION.SDK_INT >= 33) node.uniqueId else null,
            node.actionList.take(32).map { it.id to it.label?.take(200) }, node.actionList.size)
            .map { if (it == null) JsonNull else JsonPrimitive(it.toString()) }.let(::JsonArray).toString()
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun matches(node: AccessibilityNodeInfo, query: JsonObject): Boolean {
        if (node.isPassword || !node.isVisibleToUser) return false
        fun match(key: String, actual: String?): Boolean = query[key]?.jsonPrimitive?.content?.let { desired ->
            if (query["contains"]?.jsonPrimitive?.booleanOrNull == true && key in setOf("text", "description")) actual?.contains(desired, ignoreCase = false) == true else actual == desired
        } ?: true
        if (!match("text", node.text?.toString()) || !match("description", node.contentDescription?.toString()) || !match("resourceId", node.viewIdResourceName) || !match("className", node.className?.toString())) return false
        return listOf("clickable" to node.isClickable, "editable" to node.isEditable, "scrollable" to node.isScrollable, "enabled" to node.isEnabled,
            "checkable" to node.isCheckable, "checked" to node.isChecked, "selected" to node.isSelected, "focused" to node.isFocused)
            .all { (key, value) -> query[key]?.jsonPrimitive?.booleanOrNull?.let { it == value } ?: true }
    }

    private fun collect(agent: String, target: String, query: JsonObject, limit: Int): JsonObject {
        val startedElapsed = SystemClock.elapsedRealtime()
        val scanLimit = (query["maxScanNodes"]?.jsonPrimitive?.intOrNull ?: MAX_SCAN).coerceIn(1, MAX_SCAN)
        val root = guard(target)
        val id = UUID.randomUUID().toString(); val refs = linkedMapOf<String, Reference>(); val rows = mutableListOf<JsonObject>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, List<Int>>>(); queue.add(root to emptyList())
        var scanned = 0; var omittedChildren = false; var extraMatch = false
        while (queue.isNotEmpty() && scanned < scanLimit) {
            val (node, path) = queue.removeFirst(); scanned++
            // Password protection is inherited: never expose or reference descendants of a protected node.
            if (node.packageName?.toString() != target || node.isPassword) continue
            if (matches(node, query)) {
                if (rows.size == limit) { extraMatch = true; break }
                val ref = "n${rows.size}"; refs[ref] = Reference(path, fingerprint(node))
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                rows += buildJsonObject {
                    put("nodeRef", ref); node.viewIdResourceName?.let { put("resourceId", it.take(300)) }; node.className?.toString()?.let { put("className", it.take(200)) }
                    node.text?.toString()?.let { put("text", it.take(200)); put("textTruncated", it.length > 200) }
                    node.contentDescription?.toString()?.let { put("description", it.take(200)); put("descriptionTruncated", it.length > 200) }
                    put("clickable", node.isClickable); put("editable", node.isEditable); put("scrollable", node.isScrollable)
                    put("enabled", node.isEnabled); put("checkable", node.isCheckable); put("checked", node.isChecked); put("selected", node.isSelected); put("focused", node.isFocused)
                    put("bounds", buildJsonObject { put("left", bounds.left); put("top", bounds.top); put("right", bounds.right); put("bottom", bounds.bottom) })
                    put("actions", JsonArray(node.actionList.take(32).map { JsonPrimitive(it.id) }))
                }
            }
            if (path.size < MAX_DEPTH) {
                val count = minOf(node.childCount, MAX_CHILDREN, scanLimit - scanned - queue.size)
                if (count < node.childCount) omittedChildren = true
                for (index in 0 until count) node.getChild(index)?.let { queue.add(it to (path + index)) }
            } else if (node.childCount > 0) omittedChildren = true
        }
        val observedElapsed = SystemClock.elapsedRealtime()
        snapshots.remove(agent)
        snapshots[agent] = Snapshot(id, agent, target, root.windowId, changes.value, observedElapsed, refs)
        while (snapshots.size > 8) snapshots.remove(snapshots.keys.first())
        return buildJsonObject {
            put("snapshotId", id); put("serviceGeneration", processGeneration); put("windowId", root.windowId); put("packageName", target); put("epoch", changes.value)
            put("nodes", JsonArray(rows)); put("scannedNodes", scanned); put("limitReached", rows.size == limit)
            put("scanIncomplete", queue.isNotEmpty() || omittedChildren || extraMatch); put("hasMoreMatches", extraMatch); put("scanNodeLimit", scanLimit)
            put("observedAtMs", System.currentTimeMillis()); put("observedAtElapsedMs", observedElapsed); put("scanDurationMs", observedElapsed - startedElapsed)
            put("snapshotTtlMs", SNAPSHOT_TTL)
            put("passwordContentIncluded", false)
        }
    }

    suspend fun inspect(agent: String, target: String, query: JsonObject, limit: Int): JsonObject = withContext(Dispatchers.Main.immediate) {
        collect(agent, target, query, limit.coerceIn(1, MAX_RETURN))
    }

    private fun reference(agent: String, target: String, snapshotId: String, ref: String, windowId: Int): AccessibilityNodeInfo {
        val snapshot = snapshots[agent] ?: error("STALE_NODE_REFERENCE", "Snapshot invalidated by a UI event or service restart; inspect again")
        if (snapshot.agent != agent || snapshot.id != snapshotId || snapshot.target != target || snapshot.window != windowId || snapshot.epoch != changes.value || SystemClock.elapsedRealtime() - snapshot.createdElapsed > SNAPSHOT_TTL)
            error("STALE_NODE_REFERENCE", "Snapshot owner, window, epoch or lifetime changed; inspect again")
        val stored = snapshot.refs[ref] ?: error("UNKNOWN_NODE_REFERENCE", "Node reference is not part of this agent's snapshot")
        var node = guard(target, windowId)
        for (index in stored.path) {
            if (node.isPassword || node.packageName?.toString() != target) error("PROTECTED_OR_INACTIVE_NODE", "A node ancestor became protected or changed package")
            node = node.getChild(index) ?: error("STALE_NODE_REFERENCE", "Node path changed; inspect again")
        }
        if (!node.refresh() || node.windowId != windowId || node.packageName?.toString() != target || fingerprint(node) != stored.fingerprint)
            error("STALE_NODE_REFERENCE", "Target semantics changed; inspect again rather than retrying the action")
        if (node.isPassword || !node.isVisibleToUser || !node.isEnabled) error("PROTECTED_OR_INACTIVE_NODE", "Target is protected, hidden or disabled")
        if ((node.text?.length ?: 0) > 4000 || (node.contentDescription?.length ?: 0) > 4000) error("NODE_TEXT_TOO_LARGE", "Choose a narrower semantic control; action identity text exceeds the bounded fingerprint budget")
        return node
    }

    suspend fun action(agent: String, target: String, snapshotId: String, ref: String, windowId: Int, action: String, text: String?): JsonObject = withContext(Dispatchers.Main.immediate) {
        val node = reference(agent, target, snapshotId, ref, windowId)
        val actionId = when (action) { "click" -> AccessibilityNodeInfo.ACTION_CLICK; "set_text" -> AccessibilityNodeInfo.ACTION_SET_TEXT; "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; "scroll_backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; else -> error("INVALID_ACTION", "Unknown semantic action") }
        if (node.actionList.none { it.id == actionId }) error("ACTION_UNAVAILABLE", "The target does not expose this semantic action")
        if (action == "set_text" && !node.isEditable) error("NOT_EDITABLE", "Target does not expose an editable field")
        val extras = if (action == "set_text") Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text ?: "") } else null
        val accepted = node.performAction(actionId, extras)
        invalidate() // Never replay a consumed reference, even if the target rejected the action.
        if (!accepted) throw CapabilityException("ACTION_REJECTED", "Android did not acknowledge the semantic action; verify app state before retrying")
        val textVerified = if (action == "set_text") node.refresh() && node.text?.toString() == text else false
        buildJsonObject {
            put("actionAccepted", true); put("action", action); put("packageName", target); put("previousWindowId", windowId)
            put("textReadbackVerified", textVerified); put("businessOutcomeVerified", false)
            put("nextStep", "Inspect or wait for the expected app state. Never repeat a send/delete action following an uncertain outcome.")
        }
    }

    suspend fun global(target: String, windowId: Int, home: Boolean): JsonObject = withContext(Dispatchers.Main.immediate) {
        guard(target, windowId)
        val accepted = performGlobalAction(if (home) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
        invalidate()
        if (!accepted) throw CapabilityException("ACTION_REJECTED", "Android did not acknowledge global navigation; inspect the actual screen state")
        buildJsonObject { put("actionAccepted", true); put("action", if (home) "home" else "back"); put("businessOutcomeVerified", false); put("nextStep", "Inspect the expected destination package before further actions") }
    }

    /** StateFlow closes the check/subscribe race and wakes only for actual accessibility events. */
    suspend fun waitFor(agent: String, target: String, query: JsonObject, timeoutMs: Long): JsonObject {
        val result = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val observation = withContext(Dispatchers.Main.immediate) {
                    val before = changes.value
                    val snapshot = try { collect(agent, target, query, 20) } catch (e: CapabilityException) {
                        if (e.code in setOf("TARGET_NOT_FOREGROUND", "NO_ACTIVE_WINDOW")) null else throw e
                    }
                    before to snapshot
                }
                val snapshot = observation.second
                if (snapshot != null && snapshot.getValue("nodes").jsonArray.isNotEmpty()) return@withTimeoutOrNull buildJsonObject { put("matched", true); put("timedOut", false); put("snapshot", snapshot) }
                changes.first { it != observation.first }
            }
            @Suppress("UNREACHABLE_CODE") buildJsonObject { }
        }
        return result ?: buildJsonObject { put("matched", false); put("timedOut", true); put("packageName", target) }
    }

    companion object {
        private const val SNAPSHOT_TTL = 30_000L
        private const val MAX_SCAN = 1000
        private const val MAX_RETURN = 200
        private const val MAX_CHILDREN = 128
        private const val MAX_DEPTH = 32
        private const val FIXTURE_ACTIVITY = "dev.siconnect.app.accessibility.SemanticUiFixtureActivity"
        private const val INVALIDATING_EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_FOCUSED
        @Volatile var instance: SemanticAccessibilityService? = null
            private set
        private val _connectionState = MutableStateFlow(false)
        val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()
        /** Debug fixture only; production owner controls remain forbidden, including debug MainActivity. */
        fun debugFixtureResumed(activity: Activity) { if (BuildConfig.DEBUG && activity.javaClass.name == FIXTURE_ACTIVITY) instance?.debugFixture = WeakReference(activity) }
        fun debugFixturePaused(activity: Activity) { if (instance?.debugFixture?.get() === activity) instance?.debugFixture = null }
    }
}

internal object SemanticUiRules {
    fun protectedPackage(name: String): Boolean = name in setOf("com.android.systemui", "com.android.settings", "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.samsung.android.biometrics.app.setting", "com.samsung.android.biometrics", "com.android.packageinstaller", "com.google.android.packageinstaller") || name.contains("biometric", ignoreCase = true)
}
