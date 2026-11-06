package dev.siconnect.app

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.ui.MainActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real owner screen and Android authentication cancellation; never fabricates authentication. */
class CommuteOwnerUiDeviceTest {
    @Test fun cancelledAndRecreatedAuthenticationCannotSaveOrDispatchCommute() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Owner UI test opt-in required", InstrumentationRegistry.getArguments().getString("allowControlCenterUi") == "true")
        val g = instrumentation.targetContext.graph
        g.ready.await()
        assumeTrue("Use an awake unlocked phone", !g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked &&
            g.context.getSystemService(PowerManager::class.java).isInteractive)
        val before = g.settings.commute
        fun requestCount() = g.db.readableDatabase.rawQuery(
            "SELECT count(*) FROM requests WHERE agent_id='local-owner' AND capability_id='commute.route'", null,
        ).use { it.moveToFirst(); it.getLong(0) }
        val count = requestCount()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun nodes(): List<AccessibilityNodeInfo> {
            // UiAutomation can retain the pre-scroll Compose tree on this Samsung build.
            if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            val windows = automation.windowsOnAllDisplays
            (0 until windows.size()).flatMap { windows.valueAt(it) }.mapNotNull { it.root }.forEach(queue::add)
            val found = mutableListOf<AccessibilityNodeInfo>()
            while (queue.isNotEmpty() && found.size < 5000) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser) found += node
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
            }
            return found
        }
        suspend fun text(value: String, scroll: Boolean = false, up: Boolean = false): AccessibilityNodeInfo = try { withTimeout(20_000) {
            while (true) {
                val snapshot = nodes()
                snapshot.firstOrNull {
                    it.text?.toString() == value || it.hintText?.toString() == value ||
                        (it.isEditable && (it.text?.contains(value) == true || it.contentDescription?.contains(value) == true))
                }?.let { return@withTimeout it }
                if (scroll) snapshot.filter { it.packageName?.toString() == g.context.packageName && it.isScrollable }
                    .maxByOrNull { node -> android.graphics.Rect().also(node::getBoundsInScreen).let { it.width().toLong() * it.height() } }
                    ?.performAction(if (up) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
                instrumentation.waitForIdleSync()
                delay(if (scroll) 500 else 100)
            }
            @Suppress("UNREACHABLE_CODE") error("Missing control")
        }} catch (error: TimeoutCancellationException) {
            automation.takeScreenshot()?.let { bitmap ->
                java.io.File(g.context.cacheDir, "commute-ui-timeout.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            throw AssertionError("Missing visible control: $value", error)
        }
        fun click(node: AccessibilityNodeInfo) {
            var target = node
            while (!target.isClickable) target = requireNotNull(target.parent)
            assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun unchanged() {
            assertEquals("Authentication must not save a draft", before, g.settings.commute)
            assertEquals("Authentication must not admit a route request", count, requestCount())
        }
        ActivityScenario.launch<MainActivity>(Intent(g.context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            click(text("Settings"))
            text("Commute", scroll = true)
            val field = nodes().first { it.packageName?.toString() == g.context.packageName && it.isEditable }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Ferry Building, San Francisco")
            }))
            click(text("Calculate with Maps", scroll = true))
            text("Open and read Maps route")
            unchanged()
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            text("Authentication cancelled. Nothing was approved.", scroll = true)
            unchanged()
            // Reopening the real prompt after cancellation must work; recreation must discard it.
            click(text("Calculate with Maps", scroll = true, up = true))
            text("Open and read Maps route")
            scenario.recreate()
            withTimeout(10_000) {
                while (nodes().any { it.text?.toString() == "Open and read Maps route" }) delay(100)
            }
            text("Calculate with Maps", scroll = true)
            unchanged()
        }
        unchanged()
    }
}
