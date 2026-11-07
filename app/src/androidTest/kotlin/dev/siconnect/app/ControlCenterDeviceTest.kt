package dev.siconnect.app

import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.ui.MainActivity
import dev.siconnect.app.ui.botDisplayName
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real native navigation/form state; never saves a schedule, changes grants or approves an action. */
@RunWith(AndroidJUnit4::class)
class ControlCenterDeviceTest {
    @Test fun navigationAndUnsavedAutomationSurviveRecreation()=runBlocking {
        org.junit.Assume.assumeTrue("Coordinated owner UI test opt-in required",InstrumentationRegistry.getArguments().getString("allowControlCenterUi")=="true")
        val i=InstrumentationRegistry.getInstrumentation()
        val g=i.targetContext.graph;g.ready.await()
        org.junit.Assume.assumeTrue("Owner must unlock the phone for control-center interaction",!g.context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked && g.context.getSystemService(android.os.PowerManager::class.java).isInteractive)
        val automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo=automation.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        var step="launch"
        fun checkpoint(value:String) {
            step=value
            i.sendStatus(2,Bundle().apply {putString("stream","\nControl center step: $value\n")})
        }
        fun nodes():List<AccessibilityNodeInfo> {
            if(android.os.Build.VERSION.SDK_INT>=34) automation.clearCache()
            val queue=java.util.ArrayDeque<AccessibilityNodeInfo>()
            automation.windows.mapNotNull {it.root}.forEach(queue::add)
            var visited=0
            val visible=mutableListOf<AccessibilityNodeInfo>()
            while(queue.isNotEmpty() && visited++<3000) {
                val n=queue.removeFirst()
                if(n.packageName?.toString()==g.context.packageName && n.isVisibleToUser) visible+=n
                for(c in 0 until n.childCount) n.getChild(c)?.let(queue::add)
            }
            return visible
        }
        fun find(predicate:(AccessibilityNodeInfo)->Boolean)=nodes().firstOrNull(predicate)
        fun saveFailureDiagnostics() {
            runCatching {
                automation.takeScreenshot()?.let {bitmap->
                    java.io.File(g.context.cacheDir,"control-center-ui-timeout.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                }
                java.io.File(g.context.cacheDir,"control-center-ui-timeout.txt").writeText("Step: $step\n"+nodes().joinToString("\n") {node->
                    val bounds=Rect().also(node::getBoundsInScreen)
                    "${node.className} text=${node.text} description=${node.contentDescription} clickable=${node.isClickable} selected=${node.isSelected} bounds=$bounds"
                })
            }
        }
        suspend fun text(value:String):AccessibilityNodeInfo=try {withTimeout(10000) {
            while(true) {find {it.text?.toString()==value}?.let {return@withTimeout it};delay(50)}
            @Suppress("UNREACHABLE_CODE") error("Missing control")
        }} catch(e:TimeoutCancellationException) {throw AssertionError("Missing visible control: $value",e)}
        suspend fun click(value:String) {
            checkpoint("Click $value")
            var n=text(value)
            while(!n.isClickable) n=requireNotNull(n.parent)
            assertTrue("Control rejected: $value",n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        suspend fun navigate(destination:String) {
            checkpoint("Navigate to $destination")
            // Agent detail tabs and screen headings may share the navigation label.
            // The global rail/bar has an explicit destination description in every layout.
            var target=try {withTimeout(10000) {
                while(true) {
                    find {it.contentDescription?.toString()==destination}?.let {return@withTimeout it}
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE") error("Missing navigation destination")
            }} catch(e:TimeoutCancellationException) {throw AssertionError("Missing global navigation: $destination",e)}
            while(!target.isClickable) target=requireNotNull(target.parent)
            assertTrue("Navigation rejected: $destination",target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        suspend fun detailControl(value:String,anchorX:Int):AccessibilityNodeInfo=try {withTimeout(10000) {
            while(true) {
                find {it.text?.toString()==value}?.let {return@withTimeout it}
                val scroller=find {node->node.isScrollable && Rect().also(node::getBoundsInScreen).let {anchorX in it.left until it.right}}
                scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE") error("Missing detail control")
        }} catch(e:TimeoutCancellationException) {throw AssertionError("Missing detail control: $value",e)}
        suspend fun revealRequestIdentity():String {
            val anchor=Rect().also(text("Close")::getBoundsInScreen).centerX()
            var control=detailControl("Request details",anchor)
            while(!control.isClickable) control=requireNotNull(control.parent)
            fun containsLabel(node:AccessibilityNodeInfo,label:String):Boolean {
                if(node.text?.toString()==label) return true
                return (0 until node.childCount).any {index->node.getChild(index)?.let {containsLabel(it,label)}==true}
            }
            if(containsLabel(control,"Show details")) assertTrue(control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val storedIds=g.requests.historyPage(limit=100).map {it.requestId}.toSet()
            return withTimeout(10000) {
                while(true) {
                    find {it.text?.toString() in storedIds}?.text?.toString()?.let {return@withTimeout it}
                    val scroller=find {node->node.isScrollable && Rect().also(node::getBoundsInScreen).let {anchor in it.left until it.right}}
                    scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(150)
                }
                @Suppress("UNREACHABLE_CODE") error("Stored request identity not visible")
            }
        }
        val before=g.scheduler.all().map {it.id}.toSet()
        val beforeGrants=g.agents.all().associate {it.grant.agentId to it.grant}
        ActivityScenario.launch<MainActivity>(Intent(g.context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {scenario->
            try {
                withTimeout(10000) {while(find {it.text?.toString() in setOf("Overview","Your phone, connected.")}==null) delay(50)}
                // Seed the owner’s explicit Home deep link, then choose a different existing
                // identity. Revisiting Agents must retain the later selection, not replay A.
                val existingAgents=g.agents.all()
                val homeAgents=existingAgents.filterNot {it.grant.agentId.startsWith("local-") || it.grant.revoked}.take(3)
                val canCheckAgentSelection=find {it.text?.toString()=="Overview"}!=null && homeAgents.size>=2 &&
                    find {it.text?.toString()==botDisplayName(homeAgents[0],existingAgents)}!=null
                if(canCheckAgentSelection) {
                    val a=homeAgents[0];val b=homeAgents[1]
                    click(botDisplayName(a,existingAgents));text("Pair agent")
                    text("Identity …${a.grant.agentId.takeLast(8)}")
                    click(botDisplayName(b,existingAgents));text("Identity …${b.grant.agentId.takeLast(8)}")
                    navigate("Activity");text("Needs attention")
                    navigate("Agents");text("Identity …${b.grant.agentId.takeLast(8)}")
                    assertNull("The consumed Home deep link must not override the later agent selection",find {it.text?.toString()=="Identity …${a.grant.agentId.takeLast(8)}"})
                } else {
                    navigate("Agents");text("Pair agent")
                    i.sendStatus(0,Bundle().apply {putString("agentSelectionCoverage","Expanded Home with two visible existing agents was unavailable")})
                }
                navigate("Automations");click("New");text("New automation")
                val field=withTimeout(5000) {while(true) {find {it.isEditable && it.text?.toString()=="Reminder"}?.let {return@withTimeout it};delay(50)};error("Missing reminder title")}
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Unsaved disposable form")
                }))
                text("Unsaved disposable form")
                navigate("Activity");text("Needs attention")
                // On the real expanded UI, inspect an existing request beside the list. No
                // synthetic execution is needed to cover selection and navigation lifetime.
                val hasDetailPane=find {it.text?.toString()=="Select an action"}!=null
                var inspectedExistingRequest=false
                if(hasDetailPane) {
                    val titles=g.requests.historyPage(limit=10).map {r->g.registry.get(r.capabilityId)?.descriptor?.title ?: r.capabilityId}.toSet()
                    if(titles.isNotEmpty()) {
                        val visibleTitle=withTimeout(10000) {
                            while(true) {
                                find {it.text?.toString() in titles}?.text?.toString()?.let {return@withTimeout it}
                                delay(100)
                            }
                            @Suppress("UNREACHABLE_CODE") error("No recorded request visible")
                        }
                        click(visibleTitle);text("Close")
                        assertNull("A selected request must replace the empty detail pane",find {it.text?.toString()=="Select an action"})
                        val selectedRequestId=revealRequestIdentity()
                        checkpoint("Recreate selected Activity request")
                        scenario.recreate();text("Close")
                        assertEquals("Recreation must reload the same persisted request selection",selectedRequestId,revealRequestIdentity())
                        inspectedExistingRequest=true
                    } else i.sendStatus(0,Bundle().apply {
                        putString("activityPaneCoverage","No persisted request was available; selection coverage was not exercised")
                    })
                }
                navigate("Automations");text("Unsaved disposable form")
                if(inspectedExistingRequest) {
                    assertNull("The Activity result must not reopen as a dialog over another section",find {it.text?.toString()=="Close"})
                    navigate("Activity");text("Select an action")
                    navigate("Automations");text("Unsaved disposable form")
                }
                checkpoint("Recreate unsaved automation draft")
                scenario.recreate();text("Unsaved disposable form")
                click("Back");text("Discard unsaved changes?");click("Discard changes")
                navigate("Settings");text("Android access")
                assertEquals(before,g.scheduler.all().map {it.id}.toSet())
                assertEquals("Navigation and result inspection must not change agent grants",beforeGrants,g.agents.all().associate {it.grant.agentId to it.grant})
            } catch(e:Throwable) {
                saveFailureDiagnostics()
                throw AssertionError("Control-center UI failed at step '$step'. Private cache diagnostics: control-center-ui-timeout.png and control-center-ui-timeout.txt",e)
            }
        }
    }
}
