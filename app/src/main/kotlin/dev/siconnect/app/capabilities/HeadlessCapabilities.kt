package dev.siconnect.app.capabilities

import android.os.Build
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.core.*
import kotlinx.serialization.json.*

/** Only registered engine capabilities or a fixed, already-approved native recipe call this client. */
internal class HeadlessClient(private val g:RuntimeGraph,private val ctx:ExecutionContext,private val selected:BackendKind=g.privileged.kind) {
    suspend fun call(operation:String,args:JsonObject):JsonObject {
        val grant=g.agents.grant(ctx.agentId)
        if(grant==null || grant.revoked || grant.expiresAtMs?.let {it<=System.currentTimeMillis()}==true)
            throw CapabilityException("POLICY_DENIED","Current agent grant was revoked or expired",noSideEffects=true)
        if((!g.settings.controls.value.privilegedEnabled && !(operation=="close_all" && ctx.agentId=="local-owner")) || g.privileged.kind!=selected)
            throw CapabilityException("PRIVILEGE_REQUIRED","Selected privileged backend changed or was disabled",noSideEffects=true)
        if(ctx.agentId !in setOf("local-owner","local-automation") && !g.settings.controls.value.remoteExecutionEnabled)
            throw CapabilityException("REMOTE_EXECUTION_DISABLED","Owner stopped new remote execution",noSideEffects=true)
        try {
            args["packageName"]?.jsonPrimitive?.content?.let(HeadlessRules::validatePackage)
            if(operation=="open") {
                val pkg=args.getValue("packageName").jsonPrimitive.content
                g.context.packageManager.getApplicationInfo(pkg,0)
                args["uri"]?.jsonPrimitive?.content?.let(HeadlessRules::validateUri)
            }
        } catch(e:IllegalArgumentException) {throw CapabilityException("INVALID_ARGUMENT",e.message ?: "Invalid private workspace arguments",noSideEffects=true)}
        catch(_:java.net.URISyntaxException) {throw CapabilityException("INVALID_ARGUMENT","Invalid private app URI",noSideEffects=true)}
        catch(_:android.content.pm.PackageManager.NameNotFoundException) {throw CapabilityException("APP_NOT_FOUND","Selected app is not installed for this user",noSideEffects=true)}
        return g.privileged.workspace(buildJsonObject {
            args.forEach {(k,v)->put(k,v)};put("operation",operation);put("agentId",ctx.agentId);put("requestId",ctx.requestId)
            val now=System.currentTimeMillis()
            put("deadlineAtMs",if(operation in setOf("close","close_all")) now+30_000 else minOf(ctx.deadlineAtMs ?: (now+30_000),now+30_000))
        },selected)
    }
}

fun registerHeadlessCapabilities(g:RuntimeGraph) {
    fun boundSchema(nodes:Boolean=false,text:Boolean=false,scroll:Boolean=false,touch:Boolean=false)=objectSchema {
        string("sessionId","Exact private workspace returned by headless.open",true,maxLength=100)
        string("packageName","Exact app bound to this workspace; also binds standing app permission",true,maxLength=200)
        if(nodes) {string("snapshotId","Fresh headless.inspect snapshot",true,maxLength=100);string("nodeRef","Exact node reference from that snapshot",true,maxLength=100)}
        if(touch) string("inputMethod","SEMANTIC (default), or PRIVATE_TOUCH within the fresh verified node on its owned display only. Private scroll gestures are vertical and never accept caller coordinates.",enum=listOf("SEMANTIC","PRIVATE_TOUCH"))
        if(text) string("text","Exact text to enter after verified semantic focus; password nodes are excluded",true,maxLength=4000)
        if(text) boolean("commit","Send a Tab key only to this owned display after verified text entry to commit a widget and verify focus release")
        if(scroll) string("direction","FORWARD scrolls down; BACKWARD scrolls up. Inspect supportedScrollDirections first; accepted dispatch alone does not prove movement.",true,enum=listOf("FORWARD","BACKWARD"))
    }
    fun register(id:String,title:String,schema:JsonObject,risk:RiskClass,operation:String,
                 interaction:InteractionMode=InteractionMode.ISOLATED_SCREEN,description:String="") {
        g.registry.register(object:Capability {
            override val descriptor get()=CapabilityDescriptor("headless.$id",title=title,
                description="$description Uses the selected authorized shell backend and an agent-owned virtual display with its own focus and top-focus stealing disabled. No physical-display mirroring, default-display input or physical unlock. Software keyboard is hidden for this display; semantic text entry is supported. App storage/network are shared with the installed app; app compatibility and external outcomes must be verified. The physical phone must already be awake and unlocked; no wake or unlock is requested. Permissions, authentication and biometric surfaces stay protected. Sessions expire and disappear on app/backend restart; never replay an uncertain mutation.",
                inputSchema=schema,backend=g.privileged.kind,risk=risk,interaction=interaction,timeoutMs=30_000,ownerOnly=operation=="close_all",
                sideEffects=if(risk==RiskClass.READ_ONLY) emptyList() else listOf(if(operation=="open") "Starts the installed app on a private display" else if(operation.startsWith("close")) "Destroys owned private display tasks" else "Acts in the selected app, including possible external sends or deletes"),
                permissions=listOf("Shell backend: android.permission.ADD_TRUSTED_DISPLAY","Shell backend: android.permission.INTERNAL_SYSTEM_WINDOW","Shell backend: android.permission.INJECT_EVENTS (private node touch)"))
            override fun availability()=when {Build.VERSION.SDK_INT<34->Availability.UNSUPPORTED;!g.privileged.available()->Availability.PRIVILEGE_REQUIRED;else->Availability.IMPLEMENTED}
            override fun availabilityDetail()="Android 14+ and the selected bundled/Shizuku service with actual trusted-display permissions. No shared-screen fallback. New headless scopes and per-app standing permissions are separate owner choices."
            override fun assess(args:JsonObject)=RiskAssessment(risk,title,target=args["packageName"]?.jsonPrimitive?.content,sideEffects=descriptor.sideEffects)
            override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {
                if(operation=="close_all" && ctx.agentId!="local-owner") throw CapabilityException("POLICY_DENIED","Only the local owner may stop all private workspaces",noSideEffects=true)
                val selected=g.requests.get(ctx.requestId)?.selectedBackend ?: g.privileged.kind
                if(g.privileged.kind!=selected) throw CapabilityException("CAPABILITY_CHANGED","Selected private workspace backend changed before dispatch",noSideEffects=true)
                val result=HeadlessClient(g,ctx,selected).call(operation,args)
                return CapabilityResult(result,selected,result["executionIdentity"]?.jsonPrimitive?.content,
                    if(operation in setOf("inspect","capture","status","close","close_all")) VerificationStatus.VERIFIED else VerificationStatus.NOT_VERIFIED,
                    if(operation in setOf("click","set_text","scroll")) "Android input dispatch only; inspect resulting app state and verify any external outcome" else "Actual private-display lifecycle, frame or semantic window observation")
            }
        })
    }
    register("open","Open a private app workspace",objectSchema {
        string("packageName","Exact installed app; must not already be visible on a shared display",true,maxLength=200)
        string("uri","Optional web/map deep link sent only to the selected package",maxLength=2000)
        integer("width","Private display width; default 720",min=320,max=1080)
        integer("height","Private display height; default 1280",min=480,max=1920)
        integer("densityDpi","Private display density; default 240",min=160,max=480)
        integer("ttlMs","Session lifetime; default 120000; maximum ten minutes",min=15_000,max=600_000)
    },RiskClass.MODERATE_IMPACT,"open",description="Open one private workspace per agent (two device-wide). Use inspect to verify its real app window before acting.")
    register("status","List your private app workspaces",objectSchema(),RiskClass.READ_ONLY,"status",InteractionMode.BACKGROUND)
    register("inspect","Inspect your private app window",objectSchema {
        string("sessionId","Exact owned private workspace",true,maxLength=100);string("packageName","Exact bound package",true,maxLength=200)
        integer("limit","Maximum returned nodes; default 200, up to 500 for larger or split-pane windows. Check scanIncomplete and refine the window size or limit when truncated.",min=1,max=500)
        integer("maxScanNodes","Maximum visited nodes, default 2000. Use selector to reduce returned content without stopping at unrelated rows.",min=1,max=2000)
        prop("selector",false,HeadlessSelection.schema)
    },RiskClass.READ_ONLY,"inspect",description="Read bounded fresh semantic nodes from this display only. Optional selector matches all supplied predicates and returns actionable references only for matching nodes; text/description match their bounded visible prefixes. Check scanIncomplete before concluding no match exists. Password subtrees are excluded; each snapshot replaces the previous one. Returned className and supportedScrollDirections help select the actual scrolling container.")
    register("capture","Capture your private app display",boundSchema(),RiskClass.READ_ONLY,"capture",description="Bounded JPEG of this session's own rendered surface; never captures the physical display.")
    register("click","Click a private app control",boundSchema(nodes=true,touch=true),RiskClass.HIGH_IMPACT,"click",description="Uses semantic click by default. Explicit PRIVATE_TOUCH targets the verified node center on this owned display; no caller coordinates or default-display input.")
    register("set_text","Enter text in a private app",boundSchema(nodes=true,text=true),RiskClass.HIGH_IMPACT,"set_text")
    register("scroll","Scroll your private app window",boundSchema(nodes=true,scroll=true,touch=true),RiskClass.HIGH_IMPACT,"scroll",
        description="SEMANTIC uses an advertised directional scroll action. Explicit PRIVATE_TOUCH sends a bounded vertical gesture inside the fresh node's visible private-display bounds. Inspect afterward; actionAccepted is dispatch acceptance, while scrollVerification and visibleContentChanged report bounded movement observations.")
    register("close","Close your private app workspace",boundSchema(),RiskClass.LOW_IMPACT,"close",InteractionMode.BACKGROUND)
    register("stop_all","Owner: stop every private workspace",objectSchema(),RiskClass.LOW_IMPACT,"close_all",InteractionMode.BACKGROUND,
        "Local-owner-only emergency resource cleanup. Remote agents cannot invoke this operation.")
}
