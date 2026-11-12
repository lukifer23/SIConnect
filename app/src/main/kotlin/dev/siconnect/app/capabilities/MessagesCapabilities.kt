package dev.siconnect.app.capabilities

import android.os.Build
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.capabilities.messages.*
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** One real local recipe; every app operation remains an independently authorized engine request. */
fun registerMessagesCapabilities(g:RuntimeGraph) {
    val locks=Array(32) {Mutex()}
    g.registry.register(object:Capability {
        private val memo=backendDescriptor({g.privileged.kind}) {kind->CapabilityDescriptor("messages.recent",title="Read recent visible Google Messages conversation",
            description="Read bounded visible Google Messages bubbles on an owned private display. Requires messages.recent plus existing headless.open/inspect/click/scroll/close scopes and applicable per-app/backend allowances; parent approval never grants child permissions. Exact visible conversation label and thread header are checked; this does not verify a Contacts identity, phone number, SMS/RCS database ID or complete message history. Opening a conversation may mark it read or send an app-managed read receipt. No composing, sending, deletion or authentication actions. The physical phone must already be awake/unlocked and Messages must not be visible on a shared display. New workspaces are closed when authorized; an optional existing owned Messages session remains open. Bounded local scrolling reduces agent round trips. Inspect countFulfilled, coverage and stopReason; incomplete results must not be described as the latest requested messages. Never automatically replay an uncertain request.",
            inputSchema=objectSchema {
                string("conversationLabel","Exact case-sensitive visible conversation name, not a contact lookup or verified phone identity",true,maxLength=500)
                integer("count","Requested recent visible bubbles; default10",min=1,max=50)
                integer("maxScrolls","Maximum thread scroll dispatches, including locating the latest edge; default8",min=1,max=12)
                integer("maxConversationScrolls","Maximum conversation-list scroll dispatches; default6",min=0,max=12)
                string("sessionId","Optional existing owned Google Messages private session; never closed by this recipe",maxLength=100)
            },backend=kind,risk=RiskClass.HIGH_IMPACT,interaction=InteractionMode.ISOLATED_SCREEN,
            timeoutMs=120_000,sideEffects=listOf("Private app navigation may mark a conversation read or trigger app-managed read receipts"))}
        override val descriptor get()=memo()
        override fun availability()=when {
            Build.VERSION.SDK_INT<34->Availability.UNSUPPORTED
            !g.privileged.available()->Availability.PRIVILEGE_REQUIRED
            else->try {g.context.packageManager.getApplicationInfo(MESSAGES_PACKAGE,0);Availability.IMPLEMENTED}
                catch(_:android.content.pm.PackageManager.NameNotFoundException) {Availability.UNSUPPORTED}
        }
        override fun assess(args:JsonObject)=RiskAssessment(RiskClass.HIGH_IMPACT,descriptor.title,target=MESSAGES_PACKAGE,sideEffects=descriptor.sideEffects)
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {
            if(args.getValue("conversationLabel").jsonPrimitive.content.isBlank())
                throw CapabilityException("INVALID_ARGUMENT","Supply an exact visible conversation label",noSideEffects=true)
            return locks[(ctx.agentId.hashCode() and Int.MAX_VALUE)%locks.size].withLock {
                val original=g.requests.get(ctx.requestId) ?: throw CapabilityException("REQUEST_NOT_FOUND","Original recipe request is missing",noSideEffects=true)
                val selected=original.selectedBackend ?: throw CapabilityException("CAPABILITY_CHANGED","Original recipe backend is missing",noSideEffects=true)
                val recipe=MessagesRecipe(g,ctx,args,original,descriptor.copy(backend=selected),selected)
                val output=recipe.run()
                CapabilityResult(output,selected,recipe.identity,VerificationStatus.NOT_VERIFIED,
                    "Actual visible Google Messages semantic observations only; contact identity, database identity and external read-receipt outcomes are not verified")
            }
        }
    })
}

private class MessagesStopped(val code:String,val childId:String?=null):Exception()
private class MessagesRecipe(private val g:RuntimeGraph,private val ctx:ExecutionContext,private val args:JsonObject,
    private val original:RequestRecord,private val descriptor:CapabilityDescriptor,private val selected:BackendKind) {
    private val label=args.getValue("conversationLabel").jsonPrimitive.content
    private val requested=args["count"]?.jsonPrimitive?.int ?: 10
    private val maxScrolls=args["maxScrolls"]?.jsonPrimitive?.int ?: 8
    private val maxConversationScrolls=args["maxConversationScrolls"]?.jsonPrimitive?.int ?: 6
    private val deadline=minOf(ctx.deadlineAtMs ?: Long.MAX_VALUE,original.expiresAtMs,System.currentTimeMillis()+100_000)
    private var binding:JsonObject?=args["sessionId"]?.jsonPrimitive?.content?.let {buildJsonObject {put("sessionId",it);put("packageName",MESSAGES_PACKAGE)}}
    private var created=false
    private var sequence=0
    private var inspections=0
    private var threadScrolls=0
    private var conversationScrolls=0
    private var latestEdge=false
    private var titleVerified=false
    private var incompleteScan=false
    private var stopReason="NOT_STARTED"
    private var stopChild:String?=null
    private var messages=emptyList<VisibleMessage>()
    private var unmerged=emptyList<VisibleMessage>()
    private val children=mutableListOf<Pair<String,String>>()
    var identity:String?=null
        private set

    private fun guard() {
        if(System.currentTimeMillis()>=deadline) throw MessagesStopped("RECIPE_DEADLINE")
        if(g.privileged.kind!=selected) throw MessagesStopped("CAPABILITY_CHANGED")
        val current=g.requests.get(ctx.requestId)
        if(current?.state!=RequestState.EXECUTING || current.agentId!=ctx.agentId || current.selectedBackend!=selected ||
            current.argsHash!=original.argsHash) throw MessagesStopped("PARENT_NO_LONGER_EXECUTING")
        val policy=PolicyEngine().evaluate(g.agents.grant(ctx.agentId),descriptor,args,original.assessment ?: RiskAssessment(RiskClass.HIGH_IMPACT,descriptor.title),
            if(g.privileged.available()) Availability.IMPLEMENTED else Availability.PRIVILEGE_REQUIRED,g.settings.controls.value,
            Origin.valueOf(original.origin),System.currentTimeMillis())
        if(policy.outcome in setOf(PolicyOutcome.DENY,PolicyOutcome.UNAVAILABLE) ||
            (policy.outcome==PolicyOutcome.REQUIRE_APPROVAL && original.approvedBy==null)) throw MessagesStopped("PARENT_POLICY_CHANGED")
    }
    private suspend fun child(operation:String,arguments:JsonObject,cleanup:Boolean=false):JsonObject {
        currentCoroutineContext().ensureActive()
        if(!cleanup) guard()
        if(g.privileged.kind!=selected) throw MessagesStopped("CAPABILITY_CHANGED")
        val capability="headless.$operation"
        if(g.registry.get(capability)?.descriptor?.backend!=selected) throw MessagesStopped("CAPABILITY_CHANGED")
        val id="messages:"+Canonical.sha256Hex(ctx.requestId)+":"+(sequence++).toString().padStart(3,'0')+":"+operation
        children+=operation to id
        val request=InvocationRequest(requestId=id,traceId=original.traceId,agentId=ctx.agentId,capabilityId=capability,
            arguments=arguments,expiresAtMs=if(cleanup) System.currentTimeMillis()+4_000 else deadline,parentRequestId=ctx.requestId)
        val record=try {g.engine.submit(request,Origin.valueOf(original.origin))}
        catch(e:CancellationException) {
            withContext(NonCancellable) {withTimeoutOrNull(750) {g.engine.cancel(id,ctx.agentId)}}
            throw e
        }
        if(record.state!=RequestState.SUCCEEDED) throw MessagesStopped(when(record.state) {
            RequestState.AWAITING_APPROVAL->"CHILD_AWAITING_APPROVAL"
            RequestState.DENIED->"CHILD_POLICY_DENIED"
            RequestState.UNKNOWN_OUTCOME->"CHILD_OUTCOME_UNCERTAIN"
            else->record.errorCode ?: "CHILD_NOT_SUCCEEDED"
        },record.requestId)
        if(record.selectedBackend!=selected) throw MessagesStopped("CAPABILITY_CHANGED",record.requestId)
        val result=record.result ?: throw MessagesStopped("CHILD_RESULT_MISSING",record.requestId)
        if(result.backend!=selected) throw MessagesStopped("CAPABILITY_CHANGED",record.requestId)
        val output=result.output.jsonObject
        if(operation=="open") {
            binding=buildJsonObject {put("sessionId",output.getValue("sessionId"));put("packageName",MESSAGES_PACKAGE)}
            created=true
        }
        if(!cleanup) guard()
        output["executionIdentity"]?.jsonPrimitive?.content?.let {identity=it}
        return output
    }
    private fun bound(extra:JsonObject=buildJsonObject {})=buildJsonObject {
        requireNotNull(binding).forEach {(k,v)->put(k,v)};extra.forEach {(k,v)->put(k,v)}
    }
    private suspend fun inspect():MessageWindow {
        if(++inspections>64) throw MessagesStopped("INSPECTION_BUDGET")
        val output=child("inspect",bound(buildJsonObject {put("limit",500);put("maxScanNodes",2000)}))
        return try {MessageWindow.parse(output)} catch(_:IllegalArgumentException) {throw MessagesStopped("MESSAGES_WINDOW_UNVERIFIED")}
    }
    private suspend fun awaitWindow(predicate:(MessageWindow)->Boolean):MessageWindow {
        val end=minOf(deadline,System.currentTimeMillis()+3_000)
        var last:MessageWindow?=null
        while(System.currentTimeMillis()<end) {
            try {val window=inspect();last=window;if(predicate(window)) return window}
            catch(e:MessagesStopped) {if(e.code !in setOf("WORKSPACE_WINDOW_UNAVAILABLE","WORKSPACE_TARGET_CHANGED")) throw e}
            delay(100)
        }
        throw MessagesStopped(if(last==null) "MESSAGES_WINDOW_UNAVAILABLE" else "MESSAGES_LAYOUT_UNSUPPORTED")
    }
    private suspend fun click(window:MessageWindow,node:MessageNode) {
        child("click",bound(buildJsonObject {put("snapshotId",window.snapshotId);put("nodeRef",node.ref);put("inputMethod","PRIVATE_TOUCH")}))
    }
    private suspend fun scroll(window:MessageWindow,container:String,direction:String,thread:Boolean):MessageWindow {
        val node=window.single(container) ?: throw MessagesStopped("SCROLL_CONTAINER_UNVERIFIED")
        if(thread) {if(threadScrolls>=maxScrolls) throw MessagesStopped("THREAD_SCROLL_BUDGET");threadScrolls++}
        else {if(conversationScrolls>=maxConversationScrolls) throw MessagesStopped("CONVERSATION_SCROLL_BUDGET");conversationScrolls++}
        val out=child("scroll",bound(buildJsonObject {
            put("snapshotId",window.snapshotId);put("nodeRef",node.ref);put("direction",direction);put("inputMethod","SEMANTIC")
        }))
        if(out["actionAccepted"]?.jsonPrimitive?.booleanOrNull!=true || out["scrollMovementVerified"]?.jsonPrimitive?.booleanOrNull!=true)
            throw MessagesStopped("SCROLL_MOVEMENT_UNVERIFIED")
        // Poll real snapshots until changed; do not use cached nodes or fixed guessed delays.
        val before=if(thread) window.messages().map {it.signature} else listOf(window.listSignature())
        return awaitWindow {next->
            if(thread) next.titleMatches(label) && next.messages().map {it.signature}!=before
            else next.nodes("conversation_name").isNotEmpty() && listOf(next.listSignature())!=before
        }
    }
    private suspend fun selectConversation(start:MessageWindow):MessageWindow {
        if(start.titleMatches(label) && start.hasThread()) return start
        var window=start
        if(window.hasThread()) {
            val back=window.backNode() ?: throw MessagesStopped("MESSAGES_BACK_CONTROL_UNVERIFIED")
            click(window,back)
            window=awaitWindow {it.nodes("conversation_name").isNotEmpty()}
        }
        if(window.nodes("conversation_name").isEmpty()) throw MessagesStopped("CONVERSATION_LIST_UNAVAILABLE")
        var direction="BACKWARD"
        val visited=mutableSetOf<String>()
        while(true) {
            val matches=window.conversationMatches(label)
            if(matches.size>1) throw MessagesStopped("AMBIGUOUS_VISIBLE_CONVERSATION_LABEL")
            if(matches.size==1) {
                click(window,matches.single())
                return awaitWindow {it.hasThread() && it.titleMatches(label)}
            }
            val list=window.single("list") ?: throw MessagesStopped("CONVERSATION_LIST_UNSUPPORTED")
            if(direction=="BACKWARD" && "BACKWARD" !in list.directions) direction="FORWARD"
            if(direction !in list.directions) throw MessagesStopped(if(window.incomplete) "CONVERSATION_SCAN_INCOMPLETE" else "CONVERSATION_NOT_FOUND_WITHIN_BOUNDS")
            val key=direction+window.listSignature()
            if(!visited.add(key)) throw MessagesStopped("CONVERSATION_LIST_UNCHANGED")
            window=scroll(window,"list",direction,false)
        }
    }
    suspend fun run():JsonObject {
        var closed=false
        var cleanupPending=false
        var cancelled:CancellationException?=null
        try {
            guard()
            if(binding==null) {
                val opened=child("open",buildJsonObject {put("packageName",MESSAGES_PACKAGE);put("width",540);put("height",1920);put("densityDpi",240);put("ttlMs",120_000)})
                binding=buildJsonObject {put("sessionId",opened.getValue("sessionId"));put("packageName",MESSAGES_PACKAGE)}
                created=true
            }
            var window=selectConversation(awaitWindow {it.hasThread() || it.nodes("conversation_name").isNotEmpty()})
            titleVerified=true
            // Do not assume launch/restored scroll position is the newest message edge.
            while(true) {
                if(!window.titleMatches(label)) throw MessagesStopped("THREAD_IDENTITY_CHANGED")
                if(window.incomplete) {incompleteScan=true;throw MessagesStopped("THREAD_SCAN_INCOMPLETE")}
                val list=window.single("message_list") ?: throw MessagesStopped("THREAD_CONTAINER_UNVERIFIED")
                if("FORWARD" !in list.directions) {
                    val confirm=inspect()
                    if(confirm.incomplete) incompleteScan=true
                    if(confirm.incomplete || !confirm.titleMatches(label) || confirm.single("message_list")==null) throw MessagesStopped("LATEST_EDGE_UNVERIFIED")
                    if("FORWARD" !in requireNotNull(confirm.single("message_list")).directions) {latestEdge=true;window=confirm;break}
                    window=confirm
                } else window=scroll(window,"message_list","FORWARD",true)
            }
            messages=window.messages()
            while(messages.size<requested) {
                val list=window.single("message_list") ?: throw MessagesStopped("THREAD_CONTAINER_UNVERIFIED")
                if("BACKWARD" !in list.directions) {stopReason="OLDEST_VISIBLE_EDGE_REACHED";break}
                window=scroll(window,"message_list","BACKWARD",true)
                if(!window.titleMatches(label)) throw MessagesStopped("THREAD_IDENTITY_CHANGED")
                if(window.incomplete) {incompleteScan=true;throw MessagesStopped("THREAD_SCAN_INCOMPLETE")}
                val page=window.messages();val merged=mergeOlderMessages(page,messages)
                if(merged.ambiguity!=null) {unmerged=page.takeLast(50);throw MessagesStopped(merged.ambiguity)}
                messages=merged.messages
            }
            if(messages.size>=requested) stopReason="REQUESTED_VISIBLE_COUNT_OBSERVED"
        } catch(e:CancellationException) {cancelled=e;stopReason="CANCELLED"}
          catch(e:MessagesStopped) {stopReason=e.code;stopChild=e.childId}
          catch(e:CapabilityException) {stopReason=e.code}
        finally {
            if(created && binding!=null) {
                withContext(NonCancellable) {
                    try {withTimeout(4_500) {closed=child("close",bound(),cleanup=true)["closed"]?.jsonPrimitive?.booleanOrNull==true}}
                    catch(_:Exception) {cleanupPending=true}
                }
                cleanupPending=cleanupPending || !closed
            }
        }
        cancelled?.let {throw it}
        var outputBytes=0
        val returned=messages.takeLast(requested).reversed().takeWhile {
            val bytes=it.toJson().toString().toByteArray(Charsets.UTF_8).size
            (outputBytes+bytes<=96*1024).also {fits->if(fits) outputBytes+=bytes else stopReason="OUTPUT_BYTE_BUDGET"}
        }
        if(stopReason=="REQUESTED_VISIBLE_COUNT_OBSERVED") {
            if(returned.any {it.textMayBeTruncated}) stopReason="VISIBLE_TEXT_BOUND_REACHED"
            else if(returned.any {it.contentType=="VISIBLE_UNREADABLE_BUBBLE"}) stopReason="BUBBLE_CONTENT_UNAVAILABLE"
        }
        val fulfilled=latestEdge && titleVerified && !incompleteScan && unmerged.isEmpty() && returned.size==requested &&
            returned.none {it.textMayBeTruncated || it.contentType=="VISIBLE_UNREADABLE_BUBBLE"} && stopReason=="REQUESTED_VISIBLE_COUNT_OBSERVED"
        return buildJsonObject {
            put("source","GOOGLE_MESSAGES_PRIVATE_ACCESSIBILITY");put("packageName",MESSAGES_PACKAGE)
            put("requestedConversationLabel",label);put("observedThreadLabel",if(titleVerified) JsonPrimitive(label) else JsonNull)
            put("conversationMatchScope","EXACT_VISIBLE_LABEL_AND_THREAD_HEADER");put("contactIdentityVerified",false)
            put("smsRcsDatabaseAccessed",false);put("distinctDatabaseMessageIdsVerified",false)
            put("requestedCount",requested);put("returnedCount",returned.size);put("countFulfilled",fulfilled)
            put("order","NEWEST_VISIBLE_FIRST");put("messages",JsonArray(returned.map {it.toJson()}))
            put("latestVisibleEdgeVerified",latestEdge);put("allConversationHistoryRead",false);put("stopReason",stopReason)
            stopChild?.let {put("blockingChildRequestId",it)}
            if(unmerged.isNotEmpty()) {
                var unmergedBytes=0
                val bounded=unmerged.takeWhile {message->
                    val size=message.toJson().toString().toByteArray(Charsets.UTF_8).size
                    (unmergedBytes+size<=32*1024).also {if(it) unmergedBytes+=size}
                }
                put("unmergedVisibleWindow",JsonArray(bounded.map {it.toJson()}));put("unmergedWindowOutputTruncated",bounded.size<unmerged.size)
            }
            put("threadScanIncomplete",incompleteScan);put("observationOutputBytes",outputBytes)
            put("threadScrolls",threadScrolls);put("conversationScrolls",conversationScrolls);put("inspections",inspections)
            put("workspaceCreated",created);put("workspaceClosed",closed);put("cleanupPending",cleanupPending)
            if(cleanupPending || !created) binding?.get("sessionId")?.let {put("sessionId",it)}
            if(cleanupPending) put("cleanupGuidance","Existing owner authority must close this exact session or await its bounded expiry; no cleanup permission bypass was attempted")
            put("children",JsonArray(children.map {(operation,id)->buildJsonObject {put("operation",operation);put("requestId",id)}}))
            put("automaticReplayAllowed",false)
        }
    }
}
