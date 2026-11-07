package dev.siconnect.app.capabilities

import android.app.NotificationManager
import android.content.ComponentName
import android.provider.Settings
import android.service.notification.NotificationListenerService
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.accessibility.SemanticAccessibilityService
import dev.siconnect.app.data.AppJson
import dev.siconnect.app.notifications.NotificationCaptureService
import dev.siconnect.app.termux.TermuxBridge
import dev.siconnect.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/** Actual configured access and live binding are separate observations. No repairs or grant changes. */
internal suspend fun recoverySnapshot(g:RuntimeGraph,agentId:String):JsonObject=withContext(Dispatchers.IO) {
    val c=g.context
    val component=ComponentName(c,SemanticAccessibilityService::class.java)
    val enabled=Settings.Secure.getString(c.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':')
        .any {ComponentName.unflattenFromString(it)==component}
    val bound=SemanticAccessibilityService.connectionState.value
    val notificationGranted=c.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(c,NotificationCaptureService::class.java))
    val notificationBound=NotificationCaptureService.connectionState.value
    val controls=g.settings.controls.value
    val termux=TermuxBridge.get(c).status(agentId)
    val gatewayEnabled=g.settings.gateway.value.enabled
    buildJsonObject {
        put("observedAtMs",System.currentTimeMillis());put("gatewayEnabled",gatewayEnabled);put("gatewayState",g.gateway.state.value.name)
        put("remoteActionsEnabled",controls.remoteExecutionEnabled);put("advancedControlsEnabled",controls.privilegedEnabled)
        put("selectedBackend",g.privileged.kind.name);put("selectedBackendAvailable",g.privileged.available())
        put("screenAccessEnabled",enabled);put("screenServiceConnected",bound)
        put("notificationAccessEnabled",notificationGranted);put("notificationServiceConnected",notificationBound)
        put("termuxInstalled",termux.installed);put("termuxRunPermissionGranted",termux.runCommandPermissionGranted)
        put("termuxExternalAccessObservation",termux.allowExternalAppsState)
        termux.lastCallbackAtMs?.let {put("termuxExternalAccessObservedAtMs",it)}
        put("startupStages",AppJson.encodeToJsonElement(ListSerializer(dev.siconnect.app.StartupStage.serializer()),g.startup.stages.value))
        put("issues",buildJsonArray {
            fun issue(code:String,description:String,action:String,severity:String="ATTENTION") {add(buildJsonObject {
                put("code",code);put("description",description);put("suggestedAction",action);put("severity",severity)
            })}
            if(!gatewayEnabled) issue("OWNER_GATEWAY_DISABLED","The gateway is paused by its saved owner setting.","CONNECTION_SETTINGS","INFO")
            else if(g.gateway.state.value.name!="ONLINE") issue("GATEWAY_NOT_ONLINE","No live gateway acknowledgement is currently observed.","RESTORE_SAVED_BRIDGE")
            if(!controls.remoteExecutionEnabled) issue("OWNER_ACTIONS_PAUSED","New remote actions are paused; saved permissions remain.","CONNECTION_SETTINGS","INFO")
            if(!controls.privilegedEnabled) issue("OWNER_ADVANCED_CONTROL_DISABLED","Advanced routing is disabled by the owner.","ADVANCED_SETUP","INFO")
            else if(!g.privileged.available()) issue("SELECTED_BACKEND_UNAVAILABLE","The selected device-control backend is not connected.","ADVANCED_SETUP")
            if(enabled && !bound) issue("SCREEN_SERVICE_DISCONNECTED","Screen access is saved in Android, but its service is disconnected.","ANDROID_SCREEN_SETTINGS")
            else if(!enabled) issue("SCREEN_ACCESS_NOT_ENABLED","Screen access has not been enabled; other ready tools can still work.","ANDROID_SCREEN_SETTINGS","INFO")
            if(notificationGranted && !notificationBound) issue("NOTIFICATION_SERVICE_DISCONNECTED","Notification access is saved, but its listener is disconnected.","ANDROID_NOTIFICATION_SETTINGS")
            else if(!notificationGranted) issue("NOTIFICATION_ACCESS_NOT_ENABLED","Notification actions require Android notification access.","ANDROID_NOTIFICATION_SETTINGS","INFO")
            if(termux.installed && !termux.runCommandPermissionGranted) issue("TERMUX_PERMISSION_REQUIRED","Termux is installed; Android command access is not granted.","TERMUX_SETTINGS")
            if(termux.installed && !termux.appEnabled) issue("TERMUX_APP_DISABLED","The installed Termux app is disabled.","TERMUX_SETTINGS")
            if(termux.installed && termux.resultApiSupported==false) issue("TERMUX_VERSION_UNSUPPORTED","The installed Termux version does not support the command callback API.","TERMUX_SETTINGS")
            if(termux.allowExternalAppsState=="REJECTED") issue("TERMUX_EXTERNAL_COMMANDS_REJECTED","The last bound Termux callback rejected external commands. Review Termux setup; this is a retained observation, not a fresh command.","TERMUX_SETTINGS")
            else if(termux.installed && termux.allowExternalAppsState=="UNKNOWN") issue("TERMUX_EXTERNAL_COMMANDS_UNCONFIRMED","External command access has no retained confirmation for this Termux installation. Run the real background check from Termux setup.","TERMUX_SETTINGS","INFO")
            if(g.startup.stages.value.any {!it.required && it.state in setOf("FAILED","INTERRUPTED")})
                issue("DEFERRED_STARTUP_TASK_FAILED","Some saved startup tasks need reconciliation; the local execution runtime is available.","RETRY_DEFERRED_STARTUP")
        })
        put("automaticMutationRetryAllowed",false)
    }
}

internal fun registerRecoveryCapabilities(g:RuntimeGraph) {
    g.registry.register(object:Capability {
        override val descriptor=CapabilityDescriptor("system.rebind_notifications",title="Owner: reconnect saved notification access",
            description="Local owner only. Ask Android to reconnect SIConnect's own notification listener only when the owner's existing Android access remains granted. Does not grant access, invoke notification actions or change agent permissions. Observe actual listener connection after the request.",
            inputSchema=objectSchema(),backend=BackendKind.NATIVE,risk=RiskClass.LOW_IMPACT,interaction=InteractionMode.BACKGROUND,ownerOnly=true)
        override fun availability()=Availability.IMPLEMENTED
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {
            if(ctx.agentId!="local-owner") throw CapabilityException("POLICY_DENIED","Only the local owner runtime may reconnect its own listener",noSideEffects=true)
            val component=ComponentName(g.context,NotificationCaptureService::class.java)
            if(!g.context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component))
                throw CapabilityException("PERMISSION_REQUIRED","Enable notification access in Android Settings first; no access was granted automatically",noSideEffects=true)
            NotificationListenerService.requestRebind(component)
            return CapabilityResult(buildJsonObject {put("rebindRequested",true);put("listenerConnectedAtReturn",NotificationCaptureService.connectionState.value)},BackendKind.NATIVE,
                verification=VerificationStatus.NOT_VERIFIED,verificationDetail="Android reconnect requested for existing own access; actual binding is observed separately")
        }
    })
    g.registry.register(object:Capability {
        override val descriptor=CapabilityDescriptor("system.recovery_status",title="Check connection and recovery readiness",
            description="Observe saved owner settings separately from actual gateway, selected-backend, screen and notification connections, Termux access and named startup stages. Returns no credentials or notification/message bodies. Does not repair, enable access, change permissions or replay actions.",
            inputSchema=objectSchema(),backend=BackendKind.NATIVE,risk=RiskClass.READ_ONLY,interaction=InteractionMode.BACKGROUND)
        override fun availability()=Availability.IMPLEMENTED
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext)=CapabilityResult(recoverySnapshot(g,ctx.agentId),BackendKind.NATIVE,
            verification=VerificationStatus.VERIFIED,verificationDetail="Point-in-time saved-setting, service-binding and startup-stage observations; no repair was performed")
    })
    g.registry.register(object:Capability {
        override val descriptor=CapabilityDescriptor("system.retry_startup",title="Owner: retry failed deferred startup tasks",
            description="Local owner only. Retry only failed/interrupted selected-backend observation, built-in workflow checks, audit maintenance and saved schedule/reminder reconciliation after essential startup succeeded. Never reruns database/authority/execution recovery, resets data, changes owner grants or blindly replays uncertain commands. Reconciliation retains original scheduled-slot identities.",
            inputSchema=objectSchema(),backend=BackendKind.NATIVE,risk=RiskClass.LOW_IMPACT,interaction=InteractionMode.BACKGROUND,ownerOnly=true,
            timeoutMs=30_000,sideEffects=listOf("Retries named failed maintenance tasks and restores dispatch for already saved schedules"))
        override fun availability()=Availability.IMPLEMENTED
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {
            if(ctx.agentId!="local-owner") throw CapabilityException("POLICY_DENIED","Only the local owner runtime may retry startup maintenance",noSideEffects=true)
            val retried=withContext(Dispatchers.IO) {g.startup.retryDeferred()}
            g.updateStartupHealth()
            return CapabilityResult(buildJsonObject {
                put("retriedStages",JsonArray(retried.map(::JsonPrimitive)))
                put("allDeferredStagesReady",g.startup.stages.value.filterNot {it.required}.all {it.state=="READY"})
                put("startupStages",AppJson.encodeToJsonElement(ListSerializer(dev.siconnect.app.StartupStage.serializer()),g.startup.stages.value))
                put("essentialRecoveryReplayed",false);put("ownerGrantsChanged",false)
            },BackendKind.NATIVE,verification=VerificationStatus.VERIFIED,verificationDetail="Actual stage outcomes after retry; inspect allDeferredStagesReady and each stage rather than assuming every task recovered")
        }
    })
}
