package dev.siconnect.app.accessibility

import dev.siconnect.app.graph
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.app.data.Settings
import dev.siconnect.core.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.accessibility.AccessibilityNodeInfo

/** Debug APK only. Isolated disposable controls, no access to owner grants or personal data. */
class SemanticUiFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = intent.getStringExtra("semanticSessionId")
        if (intent.getBooleanExtra("semanticHarnessCleanup", false)) {
            requireSession(session)
            graph.scope.launch {
                graph.ready.await()
                sessionLock.withLock {
                    val file = sessionFile(requireNotNull(session))
                    if (file.exists()) {
                        val saved = Json.parseToJsonElement(file.readText()).jsonObject
                        check(saved.getValue("sessionId").jsonPrimitive.content == session)
                        val id = saved.getValue("agentId").jsonPrimitive.content
                        check(id == "semantic-harness:$session")
                        val record = graph.agents.get(id)
                        check(record == null || record.tokenSha256 == Settings.sha256Hex(saved.getValue("token").jsonPrimitive.content)) { "Disposable session credential changed; cleanup refused" }
                        if (record != null) {
                            graph.ui.status()?.takeIf { it.agentId == id && it.activeRequestId == null }?.let { lease ->
                                graph.ui.release(ExecutionContext("semantic-cleanup:$session", "semantic-cleanup:$session", id, graph.settings.deviceId), lease.leaseId)
                            }
                            graph.agents.delete(id)
                        }
                        check(file.delete()) { "Disposable session cleanup did not finish" }
                    }
                    // Explicit one-time orphan maintenance only, never part of automatic lifecycle cleanup.
                    if (intent.getBooleanExtra("semanticHarnessCleanupOrphans", false)) {
                        val removed = mutableSetOf<String>()
                        graph.agents.all().filter { row ->
                            row.grant.agentId.startsWith("semantic-harness:") && row.grant.label == "Disposable semantic harness" &&
                                row.tokenHint == "disposable test" && row.grant.scopes == listOf("ui.*")
                        }.forEach { row ->
                            val lease = graph.ui.status()?.takeIf { it.agentId == row.grant.agentId }
                            if (lease?.activeRequestId == null) {
                                lease?.let { graph.ui.release(ExecutionContext("semantic-orphan-cleanup:$session", "semantic-orphan-cleanup:$session", row.grant.agentId, graph.settings.deviceId), it.leaseId) }
                                graph.agents.delete(row.grant.agentId); removed += row.grant.agentId
                            }
                        }
                        noBackupFilesDir.listFiles().orEmpty().filter { it.name == "semantic-harness-session.json" || it.name.matches(Regex("semantic-harness-[a-f0-9-]{36}\\.json")) }.forEach { candidate ->
                            val value = runCatching { Json.parseToJsonElement(candidate.readText()).jsonObject }.getOrNull()
                            val candidateId = value?.get("agentId")?.jsonPrimitive?.content
                            if (candidateId != null && candidateId in removed) check(candidate.delete())
                        }
                    }
                }
                withContext(Dispatchers.Main.immediate) { finish() }
            }
            return
        }
        val inboxHarness=intent.getBooleanExtra("ownerInboxHarness", false)
        if (intent.getBooleanExtra("semanticHarness", false) || inboxHarness) {
            requireSession(session)
            graph.scope.launch {
                graph.ready.await()
                sessionLock.withLock {
                    val file = sessionFile(requireNotNull(session))
                    if (!file.exists()) {
                        val id = "semantic-harness:$session"; val token = Settings.randomToken("sia_")
                        graph.agents.put(AgentRecord(AgentGrant(id, if(inboxHarness) "Disposable Inbox review" else "Disposable semantic harness", scopes = if(inboxHarness) listOf("proposals.*","device.status") else listOf("ui.*"), autoApproveUpTo = if(inboxHarness) RiskClass.LOW_IMPACT else RiskClass.HIGH_IMPACT,
                            expiresAtMs = System.currentTimeMillis() + (if(inboxHarness) 30 else 10) * 60_000), Settings.sha256Hex(token), System.currentTimeMillis(), tokenHint = "disposable test"))
                        file.writeText(buildJsonObject { put("sessionId", session); put("agentId", id); put("token", token) }.toString())
                        file.setReadable(false, false); file.setReadable(true, true); file.setWritable(false, false); file.setWritable(true, true)
                    }
                }
            }
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        content.addView(TextView(this).apply { text = "SIConnect semantic fixture"; textSize = 20f })
        content.addView(EditText(this).apply { contentDescription = "Fixture entry"; hint = "Disposable fixture text"; inputType = InputType.TYPE_CLASS_TEXT })
        content.addView(EditText(this).apply { contentDescription = "Fixture protected input"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText("fixture-secret-value") })
        content.addView(object : LinearLayout(this) {
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(info); info.isPassword = true }
        }.apply {
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(TextView(this@SemanticUiFixtureActivity).apply { text = "fixture-protected-descendant" })
        })
        content.addView(CheckBox(this).apply { text = "Fixture checkbox"; contentDescription = "Fixture checkbox" })
        val status = TextView(this).apply { text = "Fixture waiting"; contentDescription = "Fixture status" }
        content.addView(Button(this).apply { text = "Complete fixture"; setOnClickListener { status.text = "Fixture complete" } })
        content.addView(status)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(60) { number -> rows.addView(TextView(this).apply { text = "Disposable fixture row $number"; setPadding(12, 12, 12, 12) }) }
        content.addView(ScrollView(this).apply { contentDescription = "Fixture scroll region"; addView(rows) }, LinearLayout.LayoutParams(-1, 400))
        setContentView(content)
    }
    private fun requireSession(id: String?) { require(id != null && id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) { "An explicit disposable session UUID is required" } }
    private fun sessionFile(id: String) = File(noBackupFilesDir, "semantic-harness-$id.json")
    companion object { private val sessionLock = Mutex() }
    override fun onResume() { super.onResume(); SemanticAccessibilityService.debugFixtureResumed(this) }
    override fun onPause() { SemanticAccessibilityService.debugFixturePaused(this); super.onPause() }
}
