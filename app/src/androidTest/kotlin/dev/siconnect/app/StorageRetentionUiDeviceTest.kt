package dev.siconnect.app

import android.app.UiAutomation
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.ui.MainActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Reviews and cancels expiry; never confirms authentication or changes the owner's policy. */
class StorageRetentionUiDeviceTest {
    @Test fun expiryReviewCanBeCancelledWithoutChangingOwnerPolicy() = runBlocking {
        assumeTrue("Owner UI test opt-in required",InstrumentationRegistry.getArguments().getString("allowControlCenterUi")=="true")
        val i=InstrumentationRegistry.getInstrumentation();val g=i.targetContext.graph;g.ready.await()
        assumeTrue("Phone must be unlocked and interactive",!g.context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked &&
            g.context.getSystemService(android.os.PowerManager::class.java).isInteractive)
        val before=g.settings.executionRetention
        val automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo=automation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun nodes():List<AccessibilityNodeInfo> {
            val queue=java.util.ArrayDeque<AccessibilityNodeInfo>()
            val displays=automation.windowsOnAllDisplays
            (0 until displays.size()).flatMap {displays.valueAt(it)}.mapNotNull {it.root}.forEach(queue::add)
            val result=mutableListOf<AccessibilityNodeInfo>()
            while(queue.isNotEmpty() && result.size<5000) {
                val n=queue.removeFirst()
                if(n.packageName?.toString()==g.context.packageName && n.isVisibleToUser) result+=n
                for(c in 0 until n.childCount) n.getChild(c)?.let(queue::add)
            }
            return result
        }
        suspend fun text(value:String,scroll:Boolean=false):AccessibilityNodeInfo=try {withTimeout(20_000) {
            while(true) {
                val snapshot=nodes()
                snapshot.firstOrNull {it.text?.toString()==value}?.let {return@withTimeout it}
                if(scroll) {
                    val container=snapshot.filter {it.isScrollable}.maxByOrNull {node->
                        android.graphics.Rect().also(node::getBoundsInScreen).let {it.width().toLong()*it.height()}
                    }
                    container?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
                    i.waitForIdleSync()
                    delay(500) // Let Compose's scroll settle before another viewport-sized action.
                } else delay(150)
            }
            @Suppress("UNREACHABLE_CODE") error("Missing control")
        }} catch(e:TimeoutCancellationException) {
            automation.takeScreenshot()?.let {bitmap->
                java.io.File(g.context.cacheDir,"retention-ui-timeout.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            }
            throw AssertionError("Missing visible control: $value; scroll containers=${nodes().filter {it.isScrollable}.map {it.className}}",e)
        }
        fun click(node:AccessibilityNodeInfo) {
            var target=node
            while(!target.isClickable) target=requireNotNull(target.parent)
            assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        ActivityScenario.launch<MainActivity>(Intent(g.context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {scenario->
            click(text("Settings"));text("Android access")
            val selected=dev.siconnect.app.data.RetentionPolicy.OPTIONS.first {it!=before.days}
            click(text("$selected days",scroll=true))
            text("Expire payloads after $selected days?")
            scenario.recreate();text("Expire payloads after $selected days?")
            click(text("Cancel"))
            assertEquals(before,g.settings.executionRetention)
            assertFalse(nodes().any {it.text?.toString()=="Enable expiry"})
        }
    }
}
