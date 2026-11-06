package dev.siconnect.app.privileged

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.siconnect.app.capabilities.HeadlessRules
import dev.siconnect.app.backend.ShellRequest
import dev.siconnect.app.backend.ShellRunner
import dev.siconnect.core.Canonical
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/** Lives in the existing authorized shell process. Never mirrors or injects into a physical display.
 * Hidden platform flags are resolved by name and verified on the resulting Display, not assumed.
 * A private display isolates rendering/input, not an app's storage, network or arbitrary app code. */
internal class HeadlessWorkspace(private val executionIdentity: String) {
    private val worker=HandlerThread("siconnect-private-displays").apply {start()}
    private val handler=Handler(worker.looper)
    private val sessions=linkedMapOf<String,Session>()
    private var automation:UiAutomation?=null
    private val context:Context by lazy {
        if(Looper.myLooper()==null) Looper.prepare()
        val type=Class.forName("android.app.ActivityThread")
        val thread=type.getMethod("systemMain").invoke(null)
        (type.getMethod("getSystemContext").invoke(thread) as Context).createPackageContext("com.android.shell",0)
    }
    private class Session(val id:String,val agent:String,val owner:String,val target:String,val display:VirtualDisplay,
                          val reader:ImageReader,val deadline:Long,val width:Int,val height:Int,val wakeLock:PowerManager.WakeLock) {
        val frameLock=Any()
        var frame:Image?=null
        @Volatile var frameCount=0L
        var frameAt=0L
        @Volatile var closed=false
        var snapshot:Snapshot?=null
        private var displayReleased=false
        private var readerReleased=false
        fun release() {
            closed=true;snapshot=null
            var failure:Exception?=null
            fun cleanup(block:()->Unit) {try {block()} catch(e:Exception) {if(failure==null) failure=e}}
            cleanup {if(!displayReleased) {display.release();displayReleased=true}}
            cleanup {if(wakeLock.isHeld) wakeLock.release()}
            cleanup {if(!readerReleased) {
                try {reader.setOnImageAvailableListener(null,null)} finally {
                    synchronized(frameLock) {try {frame?.close();frame=null} finally {reader.close();readerReleased=true}}
                }
            }}
            failure?.let {throw it}
        }
    }
    private data class Reference(val path:List<Int>,val fingerprint:String)
    private data class Snapshot(val id:String,val window:Int,val created:Long,val refs:Map<String,Reference>)
    // Cleanup must never block the looper delivering accessibility RPC callbacks or frames.
    private val maintenance=Executors.newSingleThreadScheduledExecutor {task->Thread(task,"siconnect-private-display-expiry").apply {isDaemon=true}}
    init {maintenance.scheduleWithFixedDelay({runCatching {synchronized(this@HeadlessWorkspace) {expire()}}},15,15,TimeUnit.SECONDS)}

    @Synchronized fun closeAll(owner:String?=null) {
        var failed=false
        sessions.values.filter {owner==null || it.owner==owner}.toList().forEach {s->
            runCatching {s.release()}.onSuccess {sessions.remove(s.id)}.onFailure {failed=true}
        }
        if(sessions.isEmpty()) disconnectAutomation()
        if(failed) throw WorkspaceFailure("WORKSPACE_CLEANUP_UNCONFIRMED","Some private resources did not confirm release; retained for cleanup retry",false)
    }
    private fun expire() {
        val now=SystemClock.elapsedRealtime()
        sessions.values.filter {it.closed || it.deadline<=now || !it.display.display.isValid}.toList().forEach {s->
            runCatching {s.release()}.onSuccess {sessions.remove(s.id)}
        }
        if(sessions.isEmpty()) disconnectAutomation()
    }
    private fun disconnectAutomation() {
        val previous=automation;automation=null
        if(previous!=null) runCatching {UiAutomation::class.java.getMethod("disconnect").invoke(previous)}
    }
    private fun ui():UiAutomation {
        automation?.let {return it}
        val connection=Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance()
        val created=UiAutomation::class.java.getConstructor(Looper::class.java,Class.forName("android.app.IUiAutomationConnection"))
            .newInstance(worker.looper,connection)
        try {
            UiAutomation::class.java.getMethod("connect",Int::class.javaPrimitiveType)
                .invoke(created,UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            created.serviceInfo=created.serviceInfo.apply {
                flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            automation=created;return created
        } catch(e:Exception) {
            runCatching {UiAutomation::class.java.getMethod("disconnect").invoke(created)}
            throw e
        }
    }
    private fun fail(code:String,message:String):Nothing=throw WorkspaceFailure(code,message)
    private fun owned(a:JsonObject,agent:String,owner:String):Session {
        val s=sessions[a.getValue("sessionId").jsonPrimitive.content] ?: fail("WORKSPACE_GONE","Workspace expired or its backend restarted; create a new workspace, never replay an uncertain action")
        if(s.owner!=owner) fail("WORKSPACE_GONE","Workspace belongs to an earlier app process; its volatile session cannot be restored")
        if(s.agent!=agent) fail("WORKSPACE_NOT_OWNED","Workspace belongs to another agent")
        if(s.target!=a.getValue("packageName").jsonPrimitive.content) fail("WORKSPACE_TARGET_CONFLICT","Workspace is bound to another app")
        if(s.closed && a["operation"]?.jsonPrimitive?.content!="close") fail("WORKSPACE_CLOSING","Workspace is retained only for resource cleanup; no app action is allowed")
        return s
    }

    @Synchronized fun execute(a:JsonObject,owner:String):JsonObject {
        expire()
        val agent=a.getValue("agentId").jsonPrimitive.content
        val operation=a.getValue("operation").jsonPrimitive.content
        return when(operation) {
            "open"->open(a,agent,owner)
            "status"->buildJsonObject {
                put("sessions",JsonArray(sessions.values.filter {it.agent==agent && it.owner==owner}.map(::metadata)))
                val locked=deviceLocked();val awake=physicalDisplayState()==Display.STATE_ON
                put("privateUiReady",awake && !locked);put("physicalDeviceLocked",locked);put("physicalDisplayAwake",awake)
                put("requiresPhysicalAwakeAndUnlocked",true);put("sharedScreenFallback",false)
            }
            "close_all"->{
                if(agent!="local-owner") fail("POLICY_DENIED","Only the local owner may stop all private workspaces")
                val count=sessions.size;closeAll();buildJsonObject {put("closed",count)}
            }
            else->{
                val s=owned(a,agent,owner)
                when(operation) {
                    "close"->{s.release();sessions.remove(s.id);if(sessions.isEmpty()) disconnectAutomation();buildJsonObject {put("closed",true);put("sessionId",s.id)}}
                    "inspect"->inspect(s,a)
                    "capture"->capture(s)
                    "click","set_text","scroll"->action(s,a,operation)
                    else->fail("INVALID_ARGUMENT","Unknown workspace operation")
                }
            }
        }
    }

    private val trust:Any by lazy {
        val binder=Class.forName("android.os.ServiceManager").getMethod("getService",String::class.java).invoke(null,"trust")
        Class.forName("android.app.trust.ITrustManager\$Stub").getMethod("asInterface",IBinder::class.java).invoke(null,binder)
    }
    private fun deviceLocked():Boolean {
        // KeyguardManager constructs WindowManagerGlobal, which requires app shared memory
        // unavailable in standalone app_process on Android 17. Use its real trust Binder.
        return try {trust.javaClass.getMethod("isDeviceLocked",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).invoke(trust,0,0) as Boolean}
            catch(_:NoSuchMethodException) {trust.javaClass.getMethod("isDeviceLocked",Int::class.javaPrimitiveType).invoke(trust,0) as Boolean}
    }
    private fun physicalDisplayState()=context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.state
    private fun requireAwakeAndUnlocked() {
        if(deviceLocked()) fail("DEVICE_LOCKED","Private app UI requires the physical phone already unlocked; no unlock or shared-screen fallback was attempted")
        if(physicalDisplayState()!=Display.STATE_ON)
            fail("DEVICE_ASLEEP","Private app UI requires the physical phone already awake; no wake or shared-screen fallback was attempted")
    }
    private fun open(a:JsonObject,agent:String,owner:String):JsonObject {
        requireAwakeAndUnlocked()
        val target=a.getValue("packageName").jsonPrimitive.content
        HeadlessRules.validatePackage(target)
        if(Build.VERSION.SDK_INT<34) fail("UNSUPPORTED","Private workspaces require Android 14+ own-focus support")
        if(sessions.size>=2 || sessions.values.any {it.agent==agent}) fail("WORKSPACE_LIMIT","At most two private workspaces, and one per agent, may be open")
        val width=a["width"]?.jsonPrimitive?.int ?: 720
        val height=a["height"]?.jsonPrimitive?.int ?: 1280
        val density=a["densityDpi"]?.jsonPrimitive?.int ?: 240
        val ttl=a["ttlMs"]?.jsonPrimitive?.long ?: 120_000L
        HeadlessRules.validateSize(width,height,density,ttl)
        // Never move an app the owner is currently using into an off-screen task.
        val existing=ui().windowsOnAllDisplays
        for(i in 0 until existing.size()) {
            val id=existing.keyAt(i)
            if(sessions.values.none {it.display.display.displayId==id} && existing.valueAt(i).any {
                    it.type==AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString()==target })
                fail("APP_VISIBLE_ON_SHARED_SCREEN","Selected app is already visible outside SIConnect's private workspaces; leave it or choose another app")
        }
        val manager=context.getSystemService(DisplayManager::class.java)
        var flags=DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        for(name in listOf("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH","VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL",
            "VIRTUAL_DISPLAY_FLAG_TRUSTED","VIRTUAL_DISPLAY_FLAG_OWN_FOCUS","VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED",
            "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP"))
            flags=flags or DisplayManager::class.java.getField(name).getInt(null)
        val reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3)
        var display:VirtualDisplay?=null
        var session:Session?=null
        var wakeLock:PowerManager.WakeLock?=null
        var launchStarted=false
        try {
            display=manager.createVirtualDisplay("SIConnect private workspace",width,height,density,reader.surface,flags,null,handler)
                ?: fail("WORKSPACE_CREATE_FAILED","Android did not create a private display")
            val required=Display::class.java.getField("FLAG_OWN_FOCUS").getInt(null) or Display::class.java.getField("FLAG_STEAL_TOP_FOCUS_DISABLED").getInt(null)
            if(display.display.displayId==Display.DEFAULT_DISPLAY || (display.display.flags and required)!=required)
                fail("DISPLAY_ISOLATION_UNAVAILABLE","Android did not apply the required own-focus and no-focus-stealing flags")
            // A private text field must never send its software keyboard to the physical
            // display. Semantic ACTION_SET_TEXT works without opening an IME surface.
            val windowBinder=Class.forName("android.os.ServiceManager").getMethod("getService",String::class.java).invoke(null,"window")
            val window=Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface",IBinder::class.java).invoke(null,windowBinder)
            val hideIme=Class.forName("android.view.WindowManager").getField("DISPLAY_IME_POLICY_HIDE").getInt(null)
            try {
                window.javaClass.getMethod("setDisplayImePolicy",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).invoke(window,display.display.displayId,hideIme)
                val actual=window.javaClass.getMethod("getDisplayImePolicy",Int::class.javaPrimitiveType).invoke(window,display.display.displayId) as Int
                if(actual!=hideIme) fail("DISPLAY_INPUT_ISOLATION_UNAVAILABLE","Android did not isolate the private display's software keyboard")
            } catch(e:java.lang.reflect.InvocationTargetException) {
                if(e.targetException is SecurityException) fail("DISPLAY_INPUT_ISOLATION_UNAVAILABLE","Selected backend lacks Android's INTERNAL_SYSTEM_WINDOW permission for private keyboard policy")
                throw e
            }
            // Keep the renderer's CPU available without ACQUIRE_CAUSES_WAKEUP: OEM power
            // policy may otherwise wake a physical panel even for a display-bound screen lock.
            val power=context.getSystemService(PowerManager::class.java)
            wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SIConnect:private-display")
            wakeLock.setReferenceCounted(false);wakeLock.acquire(ttl)
            val s=Session(UUID.randomUUID().toString(),agent,owner,target,display,reader,SystemClock.elapsedRealtime()+ttl,width,height,wakeLock)
            session=s
            reader.setOnImageAvailableListener({r->
                if(!s.closed) runCatching {synchronized(s.frameLock) {
                    val image=r.acquireLatestImage()
                    if(image!=null) {s.frame?.close();s.frame=image;s.frameCount++;s.frameAt=SystemClock.elapsedRealtime()}
                }}
            },handler)
            // Never request display power or wakefulness. Some OEMs group virtual and
            // physical display power together even when OWN_DISPLAY_GROUP was requested.
            requireAwakeAndUnlocked()
            // An explicit display, user and package; no monkey, default-screen input or launch fallback.
            val argv=mutableListOf("am","start","-W","--user","0","--display",display.display.displayId.toString(),"--activity-multiple-task")
            val uri=a["uri"]?.jsonPrimitive?.content
            if(uri!=null) {HeadlessRules.validateUri(uri);argv+=listOf("-a","android.intent.action.VIEW","-d",uri,"-p",target)}
            else {
                val component=context.packageManager.getLaunchIntentForPackage(target)?.component
                    ?: fail("NO_LAUNCH_INTENT","Selected app has no launcher activity")
                argv+=listOf("-n",component.flattenToString())
            }
            launchStarted=true
            // Reuse the bounded process supervisor: waiting before draining a pipe can
            // deadlock on verbose launch output, and readText().take() bounds only the copy.
            val launch=ShellRunner.run(ShellRequest("workspace-launch:${s.id}",argv=argv,
                timeoutMs=10_000,maxOutputBytes=16_384,outputEncoding="text"),executionIdentity)
            val reply=launch.stdout+launch.stderr
            if(launch.timedOut || launch.cancelled || launch.error!=null || launch.exitCode!=0 ||
                launch.stdoutTruncated || launch.stderrTruncated || reply.contains("Error:") || reply.contains("Exception"))
                fail("WORKSPACE_LAUNCH_FAILED","App launch on the private display failed; no default-display fallback was requested")
            sessions[s.id]=s
            return buildJsonObject {metadata(s).forEach {(k,v)->put(k,v)};put("launchDispatched",true);put("targetWindowVerified",false);put("nextStep","Inspect the private display to verify the app window")}
        } catch(e:Exception) {
            if(session!=null) runCatching {session.release()}.onFailure {sessions[session.id]=session} else {
                display?.release();runCatching {if(wakeLock?.isHeld==true) wakeLock.release()};reader.close()
            }
            if(e is WorkspaceFailure) throw WorkspaceFailure(e.code,e.message,!launchStarted)
            throw e
        }
    }
    private fun metadata(s:Session)=buildJsonObject {
        put("sessionId",s.id);put("cleanupPending",s.closed);put("packageName",s.target);put("displayId",s.display.display.displayId)
        put("width",s.width);put("height",s.height);put("remainingMs",(s.deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0))
        put("expiresAtElapsedMs",s.deadline)
        put("ownFocus",true);put("stealsTopFocus",false);put("mirrorsPhysicalDisplay",false)
        put("physicalUnlockRequested",false);put("physicalWakeRequested",false)
        put("requiresPhysicalAwakeAndUnlocked",true);put("softwareKeyboardPolicy","HIDE")
        put("rendererCpuHeld",s.wakeLock.isHeld);put("privateDisplayPowerState",s.display.display.state)
        synchronized(s.frameLock) {put("renderedFrames",s.frameCount);put("lastFrameAtElapsedMs",s.frameAt)}
        put("isolation","Separate rendering and targeted input; app data/network are shared with the installed app")
    }
    private fun root(s:Session):AccessibilityNodeInfo {
        requireAwakeAndUnlocked()
        val displays=ui().windowsOnAllDisplays
        val windows=displays[s.display.display.displayId].orEmpty()
        val window=windows.filter {it.type==AccessibilityWindowInfo.TYPE_APPLICATION || it.isFocused}
            .sortedWith(compareByDescending<AccessibilityWindowInfo> {it.isFocused}.thenByDescending {it.layer}).firstOrNull()
            ?: fail("WORKSPACE_WINDOW_UNAVAILABLE","App has no accessible window on private display ${s.display.display.displayId}; observed display count=${displays.size()}, private window count=${windows.size}, rendered frames=${s.frameCount}. Inspect again")
        val root=window.root ?: fail("WORKSPACE_WINDOW_UNAVAILABLE","App window content is not accessible")
        if(root.packageName?.toString()!=s.target || root.isPassword)
            fail("WORKSPACE_TARGET_CHANGED","Private display is showing ${root.packageName} instead of ${s.target}, or a protected surface; no input was sent")
        return root
    }
    private fun fingerprint(n:AccessibilityNodeInfo):String {
        val rect=Rect().also(n::getBoundsInScreen)
        val fields=listOf(n.packageName,n.windowId,n.viewIdResourceName,n.className,if(Build.VERSION.SDK_INT>=33) n.uniqueId else null,
            n.text?.take(4000),n.contentDescription?.take(4000),rect.flattenToString(),n.isPassword,n.isEnabled,n.isClickable,
            n.isEditable,n.isScrollable,n.isChecked,n.isSelected,n.isFocused,n.actionList.map {it.id})
        return Canonical.sha256Hex(JsonArray(fields.map {if(it==null) JsonNull else JsonPrimitive(it.toString())}).toString())
    }
    private fun inspect(s:Session,a:JsonObject):JsonObject {
        val root=root(s);val id=UUID.randomUUID().toString()
        val refs=linkedMapOf<String,Reference>();val rows=mutableListOf<JsonElement>()
        val queue=ArrayDeque<Pair<AccessibilityNodeInfo,List<Int>>>()
        queue.add(root to emptyList());var visited=0;var returnedBytes=0;var truncated=false
        val limit=(a["limit"]?.jsonPrimitive?.int ?: 200).coerceIn(1,200)
        while(queue.isNotEmpty() && visited++<1000 && rows.size<limit) {
            val (n,path)=queue.removeFirst()
            if(n.isPassword || n.packageName?.toString()!=s.target) continue
            if(n.isVisibleToUser) {
                val ref=UUID.randomUUID().toString()
                val b=Rect().also(n::getBoundsInScreen)
                val row=buildJsonObject {
                    put("nodeRef",ref);put("text",n.text?.take(1000)?.toString().orEmpty());put("description",n.contentDescription?.take(1000)?.toString().orEmpty())
                    put("resourceId",n.viewIdResourceName.orEmpty());put("clickable",n.isClickable);put("editable",n.isEditable)
                    put("focusable",n.isFocusable);put("actions",JsonArray(n.actionList.map {JsonPrimitive(it.id)}))
                    put("scrollable",n.isScrollable);put("focused",n.isFocused);put("enabled",n.isEnabled);put("selected",n.isSelected)
                    put("bounds",buildJsonObject {put("left",b.left);put("top",b.top);put("right",b.right);put("bottom",b.bottom)})
                }
                val bytes=row.toString().toByteArray(Charsets.UTF_8).size
                if(returnedBytes+bytes>192*1024) {truncated=true;break}
                rows+=row;returnedBytes+=bytes;refs[ref]=Reference(path,fingerprint(n))
            }
            if(path.size<32) for(index in 0 until minOf(n.childCount,128,1000-visited-queue.size))
                n.getChild(index)?.let {queue.add(it to path+index)}
        }
        s.snapshot=Snapshot(id,root.windowId,SystemClock.elapsedRealtime(),refs)
        return buildJsonObject {
            metadata(s).forEach {(k,v)->put(k,v)};put("snapshotId",id);put("windowId",root.windowId);put("nodes",JsonArray(rows))
            put("targetWindowVerified",true)
            put("scannedNodes",visited);put("scanIncomplete",truncated || queue.isNotEmpty());put("passwordContentIncluded",false)
        }
    }
    private fun injectOwnedEvent(s:Session,event:android.view.InputEvent):Boolean {
        val displayId=s.display.display.displayId
        if(displayId==Display.DEFAULT_DISPLAY || !s.display.display.isValid) fail("WORKSPACE_GONE","Owned private display is no longer valid")
        android.view.InputEvent::class.java.getMethod("setDisplayId",Int::class.javaPrimitiveType).invoke(event,displayId)
        check(android.view.InputEvent::class.java.getMethod("getDisplayId").invoke(event)==displayId) {"Private input display binding failed"}
        val type=Class.forName("android.hardware.input.InputManagerGlobal")
        val manager=type.getMethod("getInstance").invoke(null)
        return type.getMethod("injectInputEvent",android.view.InputEvent::class.java,Int::class.javaPrimitiveType).invoke(manager,event,2) as Boolean
    }
    private fun commitOwnedText(s:Session):Boolean {
        val now=SystemClock.uptimeMillis()
        fun event(action:Int)=injectOwnedEvent(s,android.view.KeyEvent(now,SystemClock.uptimeMillis(),action,android.view.KeyEvent.KEYCODE_TAB,0,0,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,android.view.InputDevice.SOURCE_KEYBOARD))
        val down=event(android.view.KeyEvent.ACTION_DOWN)
        val up=event(android.view.KeyEvent.ACTION_UP)
        return down && up
    }
    private fun tapOwnedNode(s:Session,node:AccessibilityNodeInfo):Boolean {
        val displayId=s.display.display.displayId
        if(displayId==Display.DEFAULT_DISPLAY || !s.display.display.isValid) fail("WORKSPACE_GONE","Owned private display is no longer valid")
        val bounds=Rect().also(node::getBoundsInScreen)
        if(bounds.isEmpty || bounds.left<0 || bounds.top<0 || bounds.right>s.width || bounds.bottom>s.height)
            fail("NODE_OUTSIDE_WORKSPACE","Node bounds are outside the private display; no touch was sent")
        val down=SystemClock.uptimeMillis()
        fun event(action:Int):Boolean {
            val motion=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
            try {
                motion.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                return injectOwnedEvent(s,motion)
            } finally {motion.recycle()}
        }
        var released=false
        try {
            if(!event(android.view.MotionEvent.ACTION_DOWN)) return false
            Thread.sleep(50)
            released=event(android.view.MotionEvent.ACTION_UP)
            return released
        } finally {if(!released) runCatching {event(android.view.MotionEvent.ACTION_CANCEL)}}
    }
    private fun action(s:Session,a:JsonObject,operation:String):JsonObject {
        val snapshot=s.snapshot ?: fail("STALE_NODE_REFERENCE","Inspect this workspace before acting")
        if(snapshot.id!=a["snapshotId"]?.jsonPrimitive?.content || SystemClock.elapsedRealtime()-snapshot.created>30_000)
            fail("STALE_NODE_REFERENCE","Workspace snapshot changed or expired; inspect again")
        val ref=snapshot.refs[a["nodeRef"]?.jsonPrimitive?.content] ?: fail("STALE_NODE_REFERENCE","Node is not part of this workspace snapshot")
        var node=root(s)
        if(node.windowId!=snapshot.window) fail("STALE_NODE_REFERENCE","Workspace window changed")
        for(index in ref.path) {
            if(node.isPassword || node.packageName?.toString()!=s.target) fail("PROTECTED_UI","Node ancestor changed or became protected")
            node=node.getChild(index) ?: fail("STALE_NODE_REFERENCE","Node path changed; inspect again")
        }
        if(node.isPassword || !node.isEnabled || !node.isVisibleToUser || fingerprint(node)!=ref.fingerprint)
            fail("STALE_NODE_REFERENCE","Node changed; no input was sent")
        val touch=operation=="click" && a["inputMethod"]?.jsonPrimitive?.content=="PRIVATE_TOUCH"
        val action=when(operation) {
            "click"->{if(!touch && !node.isClickable && node.actionList.none {it.id==AccessibilityNodeInfo.ACTION_CLICK}) fail("ACTION_UNSUPPORTED","Node has no supported click action");AccessibilityNodeInfo.ACTION_CLICK}
            "set_text"->{if(!node.isEditable) fail("ACTION_UNSUPPORTED","Node is not editable");AccessibilityNodeInfo.ACTION_SET_TEXT}
            else->{if(!node.isScrollable) fail("ACTION_UNSUPPORTED","Node is not scrollable");if(a["direction"]?.jsonPrimitive?.content=="BACKWARD") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD}
        }
        val text=a["text"]?.jsonPrimitive?.content
        val extras=if(operation=="set_text") Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,requireNotNull(text))} else null
        s.snapshot=null
        if(operation=="set_text" && !node.isFocused) {
            val focused=node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val focusDeadline=SystemClock.elapsedRealtime()+1500
            while(focused && node.refresh() && !node.isFocused && SystemClock.elapsedRealtime()<focusDeadline) Thread.sleep(50)
            if(!focused || !node.refresh() || !node.isFocused || node.isPassword || node.packageName?.toString()!=s.target)
                throw WorkspaceFailure("TEXT_FOCUS_NOT_CONFIRMED","Private field ${node.viewIdResourceName} did not confirm semantic focus (accepted=$focused, focusable=${node.isFocusable}); no text was dispatched",false)
        }
        val accepted=if(touch) tapOwnedNode(s,node) else node.performAction(action,extras)
        if(!accepted) throw WorkspaceFailure("ACTION_NOT_CONFIRMED","Android did not confirm the action; inspect this workspace before retrying",false)
        var focusCleared:Boolean?=null
        if(operation=="set_text" && a["commit"]?.jsonPrimitive?.booleanOrNull==true) {
            if(!node.refresh() || !node.isFocused || node.isPassword || node.packageName?.toString()!=s.target || root(s).windowId!=snapshot.window)
                throw WorkspaceFailure("TEXT_COMMIT_NOT_CONFIRMED","Private field changed after text dispatch; no commit key was sent",false)
            val committed=commitOwnedText(s)
            val deadline=SystemClock.elapsedRealtime()+1500
            while(committed && node.refresh() && node.isFocused && SystemClock.elapsedRealtime()<deadline) Thread.sleep(50)
            focusCleared=committed && node.refresh() && !node.isFocused
            if(!focusCleared) throw WorkspaceFailure("TEXT_COMMIT_NOT_CONFIRMED","Private commit key did not confirm focus release; inspect before retrying",false)
        }
        val readback=if(operation=="set_text") node.refresh() && node.text?.toString()==text else null
        return buildJsonObject {
            metadata(s).forEach {(k,v)->put(k,v)};put("actionAccepted",true);put("inputMethod",if(touch) "PRIVATE_TOUCH" else "SEMANTIC");focusCleared?.let {put("focusCleared",it)};readback?.let {put("textReadbackVerified",it)}
            put("businessOutcomeVerified",false);put("guidance","Inspect the resulting private window; dispatch alone does not prove an external action completed")
        }
    }
    private fun capture(s:Session):JsonObject {
        requireAwakeAndUnlocked()
        root(s)
        // Only the surface created for this session is readable; no physical screenshot API.
        val bitmap=synchronized(s.frameLock) {
            val image=s.frame ?: fail("WORKSPACE_FRAME_UNAVAILABLE","Private display has not rendered a frame yet")
            val plane=image.planes.single();val padded=plane.rowStride/plane.pixelStride
            val full=Bitmap.createBitmap(padded,s.height,Bitmap.Config.ARGB_8888)
            plane.buffer.rewind();full.copyPixelsFromBuffer(plane.buffer)
            if(padded==s.width) full else Bitmap.createBitmap(full,0,0,s.width,s.height).also {full.recycle()}
        }
        try {
            val bytes=ByteArrayOutputStream();var quality=75
            do {bytes.reset();bitmap.compress(Bitmap.CompressFormat.JPEG,quality,bytes);quality-=15} while(bytes.size()>128*1024 && quality>=15)
            if(bytes.size()>128*1024) fail("WORKSPACE_IMAGE_TOO_LARGE","Private image exceeds the bounded capture limit")
            return buildJsonObject {
                metadata(s).forEach {(k,v)->put(k,v)};put("mimeType","image/jpeg");put("encoding","BASE64")
                put("data",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP));put("bytes",bytes.size())
            }
        } finally {bitmap.recycle()}
    }
}

internal class WorkspaceFailure(val code:String,override val message:String,val noSideEffects:Boolean=true):Exception(message)
