package dev.siconnect.app

import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.ui.MainActivity
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
        fun find(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            val queue=java.util.ArrayDeque<AccessibilityNodeInfo>()
            automation.windows.mapNotNull {it.root}.forEach(queue::add)
            var visited=0
            while(queue.isNotEmpty() && visited++<3000) {
                val n=queue.removeFirst()
                if(n.packageName?.toString()==g.context.packageName && n.isVisibleToUser && predicate(n)) return n
                for(c in 0 until n.childCount) n.getChild(c)?.let(queue::add)
            }
            return null
        }
        suspend fun text(value:String):AccessibilityNodeInfo=try {withTimeout(10000) {
            while(true) {find {it.text?.toString()==value}?.let {return@withTimeout it};delay(50)}
            @Suppress("UNREACHABLE_CODE") error("Missing control")
        }} catch(e:TimeoutCancellationException) {throw AssertionError("Missing visible control: $value",e)}
        suspend fun click(value:String) {
            var n=text(value)
            while(!n.isClickable) n=requireNotNull(n.parent)
            assertTrue("Control rejected: $value",n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        val before=g.scheduler.all().map {it.id}.toSet()
        ActivityScenario.launch<MainActivity>(Intent(g.context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {scenario->
            text("SIConnect")
            click("Agents");text("Pair new agent")
            click("Automations");click("New");text("New automation")
            val field=withTimeout(5000) {while(true) {find {it.isEditable && it.text?.toString()=="Reminder"}?.let {return@withTimeout it};delay(50)};error("Missing reminder title")}
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Unsaved disposable form")
            }))
            text("Unsaved disposable form")
            click("Activity");text("Needs attention")
            click("Automations");text("Unsaved disposable form")
            scenario.recreate();text("Unsaved disposable form")
            click("Back");text("Discard unsaved changes?");click("Discard changes")
            click("Settings");text("Capabilities")
            assertEquals(before,g.scheduler.all().map {it.id}.toSet())
        }
    }
}
