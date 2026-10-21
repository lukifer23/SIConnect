package dev.siconnect.app.capabilities

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Process
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.notifications.*
import dev.siconnect.core.*
import kotlinx.serialization.json.*

/** Published notification actions only: no private SMS/RCS database access or inferred recipients. */
fun registerNotificationActionCapabilities(g:RuntimeGraph) {
    fun listener()=NotificationCaptureService.instance
        ?: throw CapabilityException("PERMISSION_REQUIRED","Enable notification-listener access on this phone",noSideEffects=true)
    fun referenceSchema(reply:Boolean=false)=objectSchema {
        string("key","Exact active notification key from notifications.list",true,maxLength=2000)
        string("packageName","Exact originating package returned by discovery",true,maxLength=200)
        string("notificationVersion","Exact live version from notifications.actions",true,maxLength=100)
        string("actionId","Exact action reference from notifications.actions",true,maxLength=100)
        string("leaseId","Optional same-agent shared-screen lease for this package",maxLength=100)
        if(reply) {
            string("text","Exact outgoing reply; remote dispatch is not recipient delivery confirmation",true,maxLength=4000)
            string("resultKey","Free-form input key; optional only if action has exactly one supported text input",maxLength=128)
        } else boolean("allowOpenScreen","Explicit permission to dispatch an Activity PendingIntent; default false")
    }
    val actionSchema=objectSchema {
        string("actionId","Exact live action reference",true);integer("index","Published action index",true,min=0,max=15)
        integer("semanticAction","App-supplied Android semantic action",true);string("label","Opt-in bounded app label",maxLength=200)
        boolean("authenticationRequired","Owner authentication required by originating action",true);boolean("contextual","App contextual action",true)
        string("pendingIntentKind","Android PendingIntent kind",true);integer("remoteInputCount","Published text input count",true)
        stringArray("freeFormResultKeys","Supported free-form input identifiers",true,maxItems=8)
        boolean("invocationSupported","Whether generic invocation is supported",true);boolean("replySupported","Whether inline reply is supported",true)
        string("invocationUnavailableCode","Reason generic invocation is refused");string("replyUnavailableCode","Reason inline reply is refused")
    }
    val discoveryOutput=objectSchema {
        string("key","Current notification key",true);string("packageName","Originating package",true);integer("postTimeMs","Post time",true)
        string("notificationVersion","Exact version reference",true);boolean("actionsTruncated","Actions beyond limit excluded",true)
        prop("actions",true,buildJsonObject {put("type","array");put("maxItems",16);put("items",actionSchema)})
        boolean("bodiesIncluded","Always false",true);boolean("recipientIdentityVerified","Always false",true)
    }
    val dispatchOutput=objectSchema {
        string("state","Dispatch state",true,enum=listOf("DISPATCHED"));string("packageName","Originating package",true);string("key","Notification key",true)
        string("notificationVersion","Checked version",true);string("actionId","Checked action",true);boolean("reply","Whether RemoteInput text was supplied",true)
        string("pendingIntentKind","Android PendingIntent kind",true);boolean("deliveryUnverified","Always true",true)
        boolean("recipientIdentityVerified","Always false",true);boolean("doNotRetryMutation","Never blind retry",true);string("nextStep","Recovery guidance",true)
    }
    fun register(id:String,title:String,schema:JsonObject,risk:RiskClass,output:JsonObject,
                 body:suspend (JsonObject,ExecutionContext)->CapabilityResult) {
        g.registry.register(object:Capability {
            override val descriptor=CapabilityDescriptor(id,title=title,
                description=if(risk==RiskClass.READ_ONLY) "Discover exact live notification action references. Bodies, conversation text and suggested replies are excluded. References expire on notification changes or listener/process restart. Semantic labels are app-supplied, not independent identity proof."
                else "$title. Requires exact live notification and action references. High-impact dispatch through the existing policy and shared-screen lease. Authentication-required and SIConnect owner-management actions are refused. A successful result means DISPATCHED only; do not retry uncertain sends. Does not prove recipient identity or delivery.",
                inputSchema=schema,outputSchema=output,backend=BackendKind.NATIVE,risk=risk,
                interaction=if(risk==RiskClass.READ_ONLY) InteractionMode.BACKGROUND else InteractionMode.MAY_OPEN_SCREEN,
                sideEffects=if(risk==RiskClass.READ_ONLY) emptyList() else listOf("Executes another app's published PendingIntent; may send external communication or change device/app state"),
                reversibility=if(risk==RiskClass.READ_ONLY) Reversibility.NOT_APPLICABLE else Reversibility.IRREVERSIBLE,
                retryable=false,verification="Live action identity revalidated before dispatch; external outcome is unverified")
            override fun availability()=if(NotificationCaptureService.instance!=null) Availability.IMPLEMENTED else Availability.PERMISSION_REQUIRED
            override fun availabilityDetail()="Enable SIConnect notification-listener access in Android settings. Only actions actually published by an accessible current-user notification are supported."
            override fun assess(args:JsonObject)=RiskAssessment(risk,title,target=args["packageName"]?.jsonPrimitive?.content,
                sideEffects=descriptor.sideEffects,reversibility=descriptor.reversibility,externalCommunication=risk>RiskClass.READ_ONLY)
            override suspend fun execute(args:JsonObject,ctx:ExecutionContext)=body(args,ctx)
        })
    }
    register("notifications.actions","Inspect supported notification actions",objectSchema {
        string("key","Exact active notification key",true,maxLength=2000)
        string("packageName","Expected originating package",true,maxLength=200)
        boolean("includeLabels","Opt in to bounded app-supplied action labels, which may contain sensitive text; default false")
    },RiskClass.READ_ONLY,discoveryOutput) {a,_ ->
        val snapshot=listener().actionSnapshot(a.str("key"),a.str("packageName"))
        val labels=a["includeLabels"]?.jsonPrimitive?.booleanOrNull==true
        CapabilityResult(buildJsonObject {
            put("key",snapshot.key);put("packageName",snapshot.packageName);put("postTimeMs",snapshot.postTimeMs)
            put("notificationVersion",snapshot.version);put("actionsTruncated",snapshot.actionsTruncated)
            put("actions",JsonArray(snapshot.actions.map {selected -> actionJson(g,snapshot,selected,labels)}))
            put("bodiesIncluded",false);put("recipientIdentityVerified",false)
        },BackendKind.NATIVE,"uid=${Process.myUid()}",VerificationStatus.VERIFIED,
            "Action references describe the current live notification; source app semantics and recipient are not independently verified")
    }
    fun resolve(a:JsonObject)=listener().resolveAction(a.str("key"),a.str("packageName"),a.str("notificationVersion"),a.str("actionId"))
    suspend fun dispatch(a:JsonObject,ctx:ExecutionContext,reply:Boolean):CapabilityResult {
        // All non-read action invocations reserve shared screen because an app receiver may navigate.
        return g.ui.withScreen(ctx,a.str("packageName"),(a["leaseId"] as? JsonPrimitive)?.content) {
            val selected=resolve(a)
            val reason=unsupported(g,selected.snapshot,selected.selected,reply)
            if(reason!=null) reject(reason,"This notification action cannot be invoked safely through this capability; use the originating app or local notification")
            val action=selected.selected.action;val pending=requireNotNull(action.actionIntent)
            val fill=if(reply) {
                val text=a.str("text")
                if(text.isBlank()) reject("EMPTY_REPLY","Reply text must not be empty")
                val inputs=freeFormInputs(action)
                val requested=(a["resultKey"] as? JsonPrimitive)?.content
                val input=if(requested!=null) inputs.singleOrNull {it.resultKey==requested}
                    else inputs.singleOrNull()
                if(input==null) reject("REMOTE_INPUT_SELECTION_REQUIRED","Select one supported free-form resultKey from notifications.actions")
                Intent().also {intent ->
                    RemoteInput.addResultsToIntent(arrayOf(input),intent,Bundle().apply {putCharSequence(input.resultKey,text)})
                    RemoteInput.setResultsSource(intent,RemoteInput.SOURCE_FREE_FORM_INPUT)
                }
            } else {
                if(action.remoteInputs.orEmpty().isNotEmpty()) reject("REMOTE_INPUT_REQUIRED","Use notifications.reply_supported for an action containing text inputs")
                if(pending.isActivity && a["allowOpenScreen"]?.jsonPrimitive?.booleanOrNull!=true)
                    reject("SCREEN_ACTION_NOT_AUTHORIZED","Set allowOpenScreen explicitly to invoke an Activity action")
                null
            }
            // Re-fetch immediately before send; listener callbacks or changed PendingIntent replace the reference.
            val rechecked=resolve(a)
            if(rechecked.selected.action.actionIntent!=pending) reject("ACTION_CHANGED","Action PendingIntent changed before dispatch")
            try { if(fill==null) pending.send() else pending.send(g.context,0,fill) }
            catch(_:PendingIntent.CanceledException) {reject("ACTION_CANCELLED","Originating app cancelled the action; no new send was dispatched")}
            catch(_:SecurityException) {throw CapabilityException("ACTION_PERMISSION_DENIED","Android rejected dispatch; verify receiver state before retrying")}
            CapabilityResult(buildJsonObject {
                put("state","DISPATCHED");put("packageName",selected.snapshot.packageName);put("key",selected.snapshot.key)
                put("notificationVersion",selected.snapshot.version);put("actionId",selected.selected.id)
                put("reply",reply);put("pendingIntentKind",pendingKind(pending));put("deliveryUnverified",true)
                put("recipientIdentityVerified",false);put("doNotRetryMutation",true)
                put("nextStep","Inspect the originating app or notification. For transport interruption, retrieve this original request via requests.get; do not resend blindly.")
            },BackendKind.NATIVE,"uid=${Process.myUid()}",VerificationStatus.NOT_VERIFIED,
                "Android accepted the PendingIntent dispatch; receiver handling, actual recipient, sent state and external delivery are not verified")
        }
    }
    register("notifications.invoke_action","Invoke one published notification action",referenceSchema(),RiskClass.HIGH_IMPACT,dispatchOutput) {a,ctx ->dispatch(a,ctx,false)}
    register("notifications.reply_supported","Send a supported notification RemoteInput reply",referenceSchema(true),RiskClass.HIGH_IMPACT,dispatchOutput) {a,ctx ->dispatch(a,ctx,true)}
}

private fun freeFormInputs(action:Notification.Action)=action.remoteInputs.orEmpty().filterNotNull().take(NotificationCaptureService.MAX_INPUTS).filter {it.allowFreeFormInput && !it.isDataOnly && it.resultKey.length in 1..128}
private fun pendingKind(pending:PendingIntent)=when {
    pending.isActivity -> "ACTIVITY"
    pending.isBroadcast -> "BROADCAST"
    pending.isForegroundService -> "FOREGROUND_SERVICE"
    pending.isService -> "SERVICE"
    else -> "UNKNOWN"
}
private fun unsupported(g:RuntimeGraph,snapshot:NotificationActionSnapshot,selected:LiveNotificationAction,reply:Boolean):String? {
    val action=selected.action;val pending=action.actionIntent ?: return "ACTION_HAS_NO_INTENT"
    if(snapshot.packageName==g.context.packageName || pending.creatorUid==Process.myUid() || pending.creatorPackage==g.context.packageName) return "LOCAL_OWNER_ACTION_PROTECTED"
    if(action.isAuthenticationRequired) return "OWNER_AUTHENTICATION_REQUIRED"
    if(pending.creatorPackage!=snapshot.packageName || pending.creatorUid!=snapshot.uid) return "ACTION_CREATOR_MISMATCH"
    if(action.remoteInputs.orEmpty().size>NotificationCaptureService.MAX_INPUTS) return "REMOTE_INPUT_LIMIT"
    if(reply && pending.isActivity) return "ACTIVITY_REPLY_UNSUPPORTED"
    if(reply && pending.isImmutable) return "IMMUTABLE_REMOTE_INPUT"
    if(reply && action.semanticAction !in setOf(Notification.Action.SEMANTIC_ACTION_REPLY,Notification.Action.SEMANTIC_ACTION_NONE)) return "NON_REPLY_ACTION"
    if(reply && freeFormInputs(action).isEmpty()) return "FREE_FORM_REPLY_UNSUPPORTED"
    if(pendingKind(pending)=="UNKNOWN") return "PENDING_INTENT_KIND_UNSUPPORTED"
    return null
}
private fun actionJson(g:RuntimeGraph,snapshot:NotificationActionSnapshot,selected:LiveNotificationAction,labels:Boolean)=buildJsonObject {
    val a=selected.action;val pending=a.actionIntent
    put("actionId",selected.id);put("index",selected.index);put("semanticAction",a.semanticAction)
    if(labels) put("label",a.title?.toString()?.take(200) ?: "")
    put("authenticationRequired",a.isAuthenticationRequired);put("contextual",a.isContextual)
    put("pendingIntentKind",pending?.let(::pendingKind) ?: "NONE")
    put("remoteInputCount",a.remoteInputs.orEmpty().size)
    put("freeFormResultKeys",JsonArray(freeFormInputs(a).take(NotificationCaptureService.MAX_INPUTS).map {JsonPrimitive(it.resultKey)}))
    val invoke=unsupported(g,snapshot,selected,false) ?: if(a.remoteInputs.orEmpty().isNotEmpty()) "REMOTE_INPUT_REQUIRED" else null
    val reply=unsupported(g,snapshot,selected,true)
    put("invocationSupported",invoke==null);put("replySupported",reply==null)
    invoke?.let {put("invocationUnavailableCode",it)};reply?.let {put("replyUnavailableCode",it)}
}
private fun reject(code:String,message:String):Nothing=throw CapabilityException(code,message,noSideEffects=true)
