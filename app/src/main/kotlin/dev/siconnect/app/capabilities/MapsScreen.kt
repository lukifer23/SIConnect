package dev.siconnect.app.capabilities

import android.app.KeyguardManager
import android.util.Xml
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.accessibility.SemanticAccessibilityService
import dev.siconnect.app.backend.ShellRequest
import dev.siconnect.core.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import org.xmlpull.v1.XmlPullParser
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Real Maps controls inside an owned shared-screen lease or private display. */
internal class MapsScreen(private val g:RuntimeGraph,private val ctx:ExecutionContext,private val backend:BackendKind,
                          private val workspace:JsonObject?=null) {
    private val privateClient=workspace?.let {HeadlessClient(g,ctx,backend)}
    companion object {
        fun validateTime(epochMs:Long,zone:ZoneId) {
            val target=Instant.ofEpochMilli(epochMs).atZone(zone)
            val today=LocalDate.now(zone)
            if(epochMs%60_000!=0L || epochMs<=System.currentTimeMillis() || target.toLocalDate() !in today..today.plusDays(6))
                throw CapabilityException("INVALID_COMMUTE_TIME","Use a future whole-minute time within the next seven local dates",noSideEffects=true)
            if(zone.rules!=ZoneId.systemDefault().rules)
                throw CapabilityException("MAPS_TIMEZONE_UNSUPPORTED","Maps control automation currently requires the device timezone",noSideEffects=true)
        }
    }
    private var sequence=0
    var captures=0
        private set
    private fun executionId(step:String)=ctx.requestId.take(160)+":maps:"+(sequence++)+":"+step
    private fun guard() {
        if(workspace==null && g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            throw CapabilityException("SCREEN_LOCKED","Unlock the phone to control Maps")
        if(ctx.agentId !in setOf("local-owner","local-automation") && !g.settings.controls.value.remoteExecutionEnabled)
            throw CapabilityException("REMOTE_EXECUTION_DISABLED","Owner stopped remote execution")
        if(backend!=BackendKind.ACCESSIBILITY && (!g.settings.controls.value.privilegedEnabled || g.privileged.kind!=backend))
            throw CapabilityException("PRIVILEGE_REQUIRED","Selected execution authority changed or was disabled")
    }
    suspend fun capture():Pair<List<RouteLabel>,String> {
        guard();captures++
        if(workspace!=null) {
            val snapshot=try {privateClient!!.call("inspect",workspace)} catch(e:CapabilityException) {
                if(e.code in setOf("WORKSPACE_WINDOW_UNAVAILABLE","WORKSPACE_TARGET_CHANGED"))
                    throw CapabilityException("ROUTE_SCREEN_UNREADABLE","Maps private window is not ready: ${e.message}")
                throw e
            }
            return snapshot.getValue("nodes").jsonArray.map {it.jsonObject}.map {node->
                val b=node.getValue("bounds").jsonObject
                RouteLabel(node["text"]?.jsonPrimitive?.content.orEmpty(),node["description"]?.jsonPrimitive?.content.orEmpty(),b.getValue("top").jsonPrimitive.int,b.getValue("bottom").jsonPrimitive.int,
                    b.getValue("left").jsonPrimitive.int,b.getValue("right").jsonPrimitive.int,node["resourceId"]?.jsonPrimitive?.content.orEmpty(),
                    (node["clickable"]?.jsonPrimitive?.booleanOrNull==true || node["actions"]?.jsonArray?.any {it.jsonPrimitive.int==android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK}==true),node["focused"]?.jsonPrimitive?.booleanOrNull==true,
                    node.getValue("nodeRef").jsonPrimitive.content,snapshot.getValue("snapshotId").jsonPrimitive.content,snapshot.getValue("windowId").jsonPrimitive.int,node["selected"]?.jsonPrimitive?.booleanOrNull==true)
            } to snapshot.getValue("executionIdentity").jsonPrimitive.content
        }
        if(backend==BackendKind.ACCESSIBILITY) {
            val service=SemanticAccessibilityService.instance ?: throw CapabilityException("ACCESSIBILITY_DISCONNECTED","Accessibility disconnected; restore it or make a new request using the selected privileged backend")
            val snapshot=service.inspect(ctx.agentId,MAPS_PACKAGE,buildJsonObject {put("maxScanNodes",1000)},200)
            return snapshot.getValue("nodes").jsonArray.map {it.jsonObject}.map {node ->
                val b=node.getValue("bounds").jsonObject
                RouteLabel(node["text"]?.jsonPrimitive?.content.orEmpty(),node["description"]?.jsonPrimitive?.content.orEmpty(),b.getValue("top").jsonPrimitive.int,b.getValue("bottom").jsonPrimitive.int,
                    b.getValue("left").jsonPrimitive.int,b.getValue("right").jsonPrimitive.int,node["resourceId"]?.jsonPrimitive?.content.orEmpty(),
                    (node["clickable"]?.jsonPrimitive?.booleanOrNull==true || node["actions"]?.jsonArray?.any {it.jsonPrimitive.int==android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK}==true),node["focused"]?.jsonPrimitive?.booleanOrNull==true,
                    node.getValue("nodeRef").jsonPrimitive.content,snapshot.getValue("snapshotId").jsonPrimitive.content,snapshot.getValue("windowId").jsonPrimitive.int,node["selected"]?.jsonPrimitive?.booleanOrNull==true)
            } to "uid=${android.os.Process.myUid()}"
        }
        if(!g.settings.controls.value.privilegedEnabled || g.privileged.kind!=backend)
            throw CapabilityException("PRIVILEGE_REQUIRED","The selected privileged backend changed or was disabled; make a fresh request",noSideEffects=true)
        // The owned shell process has no controlling terminal. Write to its actual stdout pipe.
        val result=g.privileged.execute(ShellRequest(executionId("read"),argv=listOf("uiautomator","dump","/proc/self/fd/1"),timeoutMs=8_000,maxOutputBytes=128*1024))
        if(result.exitCode!=0 || result.timedOut || result.cancelled || result.stdoutTruncated)
            throw CapabilityException("ROUTE_SCREEN_UNREADABLE","Maps hierarchy capture did not complete; inspect the screen or restore accessibility")
        val start=result.stdout.indexOf("<?xml")
        val end=result.stdout.lastIndexOf("</hierarchy>")
        if(start<0 || end<start) throw CapabilityException("ROUTE_SCREEN_UNREADABLE","Maps hierarchy was not available")
        val parser=Xml.newPullParser().apply {setInput(result.stdout.substring(start,end+12).reader())}
        val labels=mutableListOf<RouteLabel>()
        var rootChecked=false
        while(parser.eventType!=XmlPullParser.END_DOCUMENT && labels.size<1000) {
            if(!rootChecked && parser.eventType==XmlPullParser.START_TAG && parser.name=="node") {
                rootChecked=true
                if(parser.getAttributeValue(null,"package")!=MAPS_PACKAGE)
                    throw CapabilityException("MAPS_FOREGROUND_CHANGED","Another app replaced Maps on the shared screen; no further input was sent")
            }
            if(parser.eventType==XmlPullParser.START_TAG && parser.name=="node" && parser.getAttributeValue(null,"package")==MAPS_PACKAGE && parser.getAttributeValue(null,"password")!="true") {
                val bounds=parser.getAttributeValue(null,"bounds").orEmpty()
                val match=Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").matchEntire(bounds)
                if(match!=null) labels+=RouteLabel(parser.getAttributeValue(null,"text").orEmpty(),parser.getAttributeValue(null,"content-desc").orEmpty(),match.groupValues[2].toInt(),match.groupValues[4].toInt(),match.groupValues[1].toInt(),match.groupValues[3].toInt(),
                    parser.getAttributeValue(null,"resource-id").orEmpty(),parser.getAttributeValue(null,"clickable")=="true",parser.getAttributeValue(null,"focused")=="true")
            }
            parser.next()
        }
        guard()
        return labels to result.identity
    }

    private suspend fun click(labels:List<RouteLabel>,node:RouteLabel,privateTouch:Boolean=false,rebinds:Int=2) {
        guard()
        require(node.right>node.left && node.bottom>node.top) {"Map control has no visible bounds"}
        if(workspace!=null || backend==BackendKind.ACCESSIBILITY) {
            val target=if(workspace!=null && privateTouch) node else labels.filter {it.clickable && it.left<=node.left && it.right>=node.right && it.top<=node.top && it.bottom>=node.bottom}
                .minByOrNull {(it.right-it.left).toLong()*(it.bottom-it.top)}
                ?: throw CapabilityException("MAPS_CONTROL_UNSUPPORTED","Maps control ${node.resourceId} [${node.text.take(80)} / ${node.description.take(80)}] has no accessible clickable parent")
            if(workspace!=null) {
                try {privateClient!!.call("click",buildJsonObject {
                    workspace.forEach {(k,v)->put(k,v)};put("snapshotId",target.snapshotId);put("nodeRef",target.nodeRef)
                    if(privateTouch) put("inputMethod","PRIVATE_TOUCH")
                })} catch(e:CapabilityException) {
                    // This fixed recipe changes route-preview controls only. Rebind only a
                    // confirmed pre-dispatch stale rejection, never an uncertain input.
                    if(e.code!="STALE_NODE_REFERENCE" || !e.noSideEffects || rebinds<=0) throw e
                    delay(100)
                    val fresh=capture().first
                    val same=fresh.singleOrNull {it.resourceId==node.resourceId && it.text==node.text && it.description==node.description && it.left==node.left && it.right==node.right} ?: throw e
                    if(!(same.text in setOf("Leave","Arrive") && same.selected)) click(fresh,same,privateTouch,rebinds-1)
                }
            } else {
                val service=SemanticAccessibilityService.instance ?: throw CapabilityException("ACCESSIBILITY_DISCONNECTED","Accessibility disconnected")
                service.action(ctx.agentId,MAPS_PACKAGE,target.snapshotId,target.nodeRef,target.windowId,"click",null)
            }
        } else {
            val result=g.privileged.execute(ShellRequest(executionId("tap"),argv=listOf("input","tap",((node.left+node.right)/2).toString(),((node.top+node.bottom)/2).toString()),timeoutMs=3_000,maxOutputBytes=2048))
            if(result.exitCode!=0 || result.cancelled || result.timedOut) throw CapabilityException("MAPS_CONTROL_FAILED","Maps tap did not complete; inspect the screen before retrying")
        }
    }

    private suspend fun awaitLabels(step:String,predicate:(List<RouteLabel>)->Boolean):List<RouteLabel> {
        val deadline=android.os.SystemClock.elapsedRealtime()+12_000
        do {
            try {
                val labels=capture().first
                if(predicate(labels)) return labels
            } catch(e:CapabilityException) {
                if(e.code !in setOf("TARGET_NOT_FOREGROUND","NO_ACTIVE_WINDOW","ROUTE_SCREEN_UNREADABLE")) throw e
            }
            delay(150)
        } while(android.os.SystemClock.elapsedRealtime()<deadline)
        throw CapabilityException("MAPS_CONTROL_UNAVAILABLE","Maps control unavailable at $step; inspect its actual screen")
    }
    private suspend fun awaitControl(step:String,predicate:(RouteLabel)->Boolean):Pair<List<RouteLabel>,RouteLabel> {
        val labels=awaitLabels(step) {it.any(predicate)}
        return labels to labels.first(predicate)
    }

    private fun fields(labels:List<RouteLabel>)=labels.filter {it.resourceId=="android:id/numberpicker_input"}.sortedBy {it.left}
    private fun date(labels:List<RouteLabel>):LocalDate {
        val label=labels.firstOrNull {it.resourceId=="$MAPS_PACKAGE:id/textbox"}
            ?: throw CapabilityException("MAPS_DATE_UNREADABLE","Maps date selection is not visible")
        val value=label.description.replace(Regex("^(Today|Tomorrow),\\s*"),"")
        return runCatching {LocalDate.parse(value,DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu",Locale.US))}.getOrElse {
            throw CapabilityException("MAPS_DATE_UNREADABLE","Maps date format is unsupported; no date was guessed")
        }
    }

    private suspend fun setPrivateNumber(input:RouteLabel,value:Int,rebinds:Int=2):JsonObject {
        try {return privateClient!!.call("set_text",buildJsonObject {
            workspace!!.forEach {(k,v)->put(k,v)};put("snapshotId",input.snapshotId);put("nodeRef",input.nodeRef);put("text",value.toString());put("commit",true)
        })} catch(e:CapabilityException) {
            if(e.code!="STALE_NODE_REFERENCE" || !e.noSideEffects || rebinds<=0) throw e
            delay(100)
            val same=capture().first.singleOrNull {it.resourceId==input.resourceId && it.text==input.text && it.description==input.description && it.left==input.left && it.right==input.right} ?: throw e
            if(!same.focused) throw e
            return setPrivateNumber(same,value,rebinds-1)
        }
    }

    private suspend fun editNumber(index:Int,value:Int) {
        var labels=capture().first
        var input=fields(labels).getOrNull(index) ?: throw CapabilityException("MAPS_TIME_UNREADABLE","Maps time fields are not visible")
        if(input.text.toIntOrNull()==value) return
        run {
            click(labels,input,privateTouch=workspace!=null)
            labels=awaitLabels("time field focus") {fields(it).getOrNull(index)?.focused==true}
            input=fields(labels).getOrNull(index) ?: throw CapabilityException("MAPS_TIME_UNREADABLE","Maps time fields changed")
            if(!input.focused) throw CapabilityException("MAPS_TIME_FOCUS_UNVERIFIED","Expected Maps time field did not gain focus; no text entered")
        }
        if(workspace!=null || backend==BackendKind.ACCESSIBILITY) {
            val result=if(workspace!=null) setPrivateNumber(input,value) else {
                val service=SemanticAccessibilityService.instance ?: throw CapabilityException("ACCESSIBILITY_DISCONNECTED","Accessibility disconnected")
                service.action(ctx.agentId,MAPS_PACKAGE,input.snapshotId,input.nodeRef,input.windowId,"set_text",value.toString())
            }
            if(result["textReadbackVerified"]?.jsonPrimitive?.booleanOrNull!=true)
                throw CapabilityException("MAPS_TIME_EDIT_UNVERIFIED","Maps time edit was not confirmed")
            if(workspace==null) {
                labels=capture().first
                val other=fields(labels).getOrNull(if(index==0) 1 else 0) ?: throw CapabilityException("MAPS_TIME_UNREADABLE","Maps time fields changed")
                click(labels,other)
            } else if(result["focusCleared"]?.jsonPrimitive?.booleanOrNull!=true)
                throw CapabilityException("MAPS_TIME_EDIT_UNVERIFIED","Maps numeric edit did not confirm focus release")
        } else {
            guard()
            // Only a validated numeric value is interpolated; no caller text enters this command.
            require(value in 0..59)
            val result=g.privileged.execute(ShellRequest(executionId("edit"),command="input keycombination KEYCODE_CTRL_LEFT KEYCODE_A && input text $value && input keyevent KEYCODE_TAB",timeoutMs=4_000,maxOutputBytes=2048))
            if(result.exitCode!=0 || result.cancelled || result.timedOut) throw CapabilityException("MAPS_TIME_EDIT_UNVERIFIED","Maps numeric edit did not complete")
        }
        awaitLabels("retained numeric time field $index") {fields(it).getOrNull(index)?.text?.toIntOrNull()==value}
    }

    /** Bounded, observed control sequence; no fixed coordinates or repeated minute-tap loop. */
    suspend fun selectTime(epochMs:Long,arrive:Boolean,zone:ZoneId)=withTimeout(85_000) {
        val target=Instant.ofEpochMilli(epochMs).atZone(zone)
        val initial=awaitControl("route or time dialog") {it.description=="Overflow menu" || it.resourceId=="$MAPS_PACKAGE:id/dialog_positive_button"}.first
        if(initial.none {it.resourceId=="$MAPS_PACKAGE:id/dialog_positive_button"}) {
            val menu=awaitControl("route menu") {it.description=="Overflow menu"}
            click(menu.first,menu.second)
            val setting=awaitControl("time menu") {it.text=="Set depart or arrive time"}
            click(setting.first,setting.second)
        }
        val tab=awaitControl("time mode") {it.text==(if(arrive) "Arrive" else "Leave")}
        if(!tab.second.selected) {
            click(tab.first,tab.second)
            if(workspace!=null) awaitControl("selected time mode") {it.text==(if(arrive) "Arrive" else "Leave") && it.selected}
        }
        var labels=awaitControl("date picker") {it.resourceId=="$MAPS_PACKAGE:id/textbox"}.first
        var day=date(labels)
        repeat(7) {
            if(day!=target.toLocalDate()) {
                val direction=if(day<target.toLocalDate()) "Next date" else "Previous date"
                val node=labels.firstOrNull {it.description==direction} ?: throw CapabilityException("MAPS_DATE_UNREADABLE","Maps date control is missing")
                click(labels,node)
                labels=awaitLabels("date change") {runCatching {date(it)!=day}.getOrDefault(false)}
                val changed=date(labels)
                if(ChronoUnit.DAYS.between(day,changed)!=if(direction=="Next date") 1L else -1L)
                    throw CapabilityException("MAPS_DATE_UNVERIFIED","Maps date did not advance as requested")
                day=changed
            }
        }
        if(day!=target.toLocalDate()) throw CapabilityException("MAPS_DATE_UNVERIFIED","Maps did not show the requested date")
        val twelveHour=fields(labels).any {it.text in setOf("AM","PM")}
        val hour=if(twelveHour) ((target.hour+11)%12)+1 else target.hour
        editNumber(0,hour);editNumber(1,target.minute)
        labels=capture().first
        if(twelveHour) {
            val period=if(target.hour<12) "AM" else "PM"
            val current=fields(labels).getOrNull(2) ?: throw CapabilityException("MAPS_TIME_UNREADABLE","Maps AM/PM field is missing")
            if(current.text!=period) {
                val option=labels.firstOrNull {it.text==period && it.left==current.left && it.right==current.right}
                    ?: throw CapabilityException("MAPS_TIME_UNREADABLE","Maps AM/PM choice is missing")
                click(labels,option)
                labels=awaitLabels("AM/PM selection") {fields(it).getOrNull(2)?.text==period}
            }
        }
        labels=awaitLabels("verified date/time fields") {observed->
            val final=fields(observed)
            runCatching {date(observed)==target.toLocalDate()}.getOrDefault(false) && final.getOrNull(0)?.text?.toIntOrNull()==hour &&
                final.getOrNull(1)?.text?.toIntOrNull()==target.minute && (!twelveHour || final.getOrNull(2)?.text==(if(target.hour<12) "AM" else "PM"))
        }
        val apply=labels.firstOrNull {it.resourceId=="$MAPS_PACKAGE:id/dialog_positive_button" && it.text=="Set"}
            ?: throw CapabilityException("MAPS_CONTROL_UNAVAILABLE","Maps Set button is not visible")
        click(labels,apply)
        if(workspace!=null) {
            awaitLabels("applied private time") {nodes->nodes.none {it.resourceId=="$MAPS_PACKAGE:id/dialog_positive_button"}}
            return@withTimeout
        }
        // A visible keyboard can consume the first touch while committing its edit. Inspect
        // again and only reapply this same preview control if its values are still correct.
        labels=capture().first
        labels.firstOrNull {it.resourceId=="$MAPS_PACKAGE:id/dialog_positive_button" && it.text=="Set"}?.let {button ->
            val actual=fields(labels)
            if(date(labels)!=target.toLocalDate() || actual.getOrNull(0)?.text?.toIntOrNull()!=hour || actual.getOrNull(1)?.text?.toIntOrNull()!=target.minute ||
                twelveHour && actual.getOrNull(2)?.text!=(if(target.hour<12) "AM" else "PM"))
                throw CapabilityException("MAPS_TIME_UNVERIFIED","Maps values changed before applying its preview")
            click(labels,button)
            if(capture().first.any {it.resourceId=="$MAPS_PACKAGE:id/dialog_positive_button"})
                throw CapabilityException("MAPS_TIME_NOT_APPLIED","Maps did not close its time dialog; no route estimate was accepted")
        }
    }
}
