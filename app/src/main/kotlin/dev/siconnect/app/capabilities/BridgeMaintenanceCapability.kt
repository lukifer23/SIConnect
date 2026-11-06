package dev.siconnect.app.capabilities

import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.backend.ShellRequest
import dev.siconnect.app.transport.ConnectionService
import dev.siconnect.core.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Fixed local-owner maintenance through the same policy/audit engine as other privileged work. */
internal fun registerBridgeMaintenance(g:RuntimeGraph) {
    g.registry.register(object:Capability {
        override val descriptor get()=CapabilityDescriptor("system.restore_bridge",title="Owner: restore the saved background bridge",
            description="Local-owner runtime only. Restores an already enabled gateway using the selected authorized backend and a ten-second Android startup allowance for SIConnect's own package. Does not enable a paused gateway or change persistent power exemptions. No caller package, duration or shell arguments. Foreground-service acceptance is separate from actual gateway acknowledgement.",
            inputSchema=objectSchema(),backend=g.privileged.kind,risk=RiskClass.LOW_IMPACT,interaction=InteractionMode.BACKGROUND,timeoutMs=15_000,ownerOnly=true,
            sideEffects=listOf("Requests a ten-second startup allowance for SIConnect only","Starts the already owner-enabled bridge service"))
        override fun availability()=if(g.privileged.available()) Availability.IMPLEMENTED else Availability.PRIVILEGE_REQUIRED
        override fun availabilityDetail()="Local owner, saved gateway enabled, advanced control enabled and selected backend available. Remote agents cannot invoke this maintenance operation."
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {
            if(ctx.agentId!="local-owner") throw CapabilityException("POLICY_DENIED","Only the local owner runtime may request bridge startup assistance",noSideEffects=true)
            val selected=g.privileged.kind
            if(!g.settings.gateway.value.enabled) return CapabilityResult(buildJsonObject {put("startupRequested",false);put("reason","OWNER_DISABLED")},selected,
                verification=VerificationStatus.VERIFIED,verificationDetail="Saved gateway preference remains disabled; no startup allowance was requested")
            if(!g.settings.controls.value.privilegedEnabled) throw CapabilityException("PRIVILEGE_REQUIRED","Owner disabled advanced device control",noSideEffects=true)
            val result=g.privileged.execute(ShellRequest("bridge-start-${UUID.randomUUID()}",
                argv=listOf("cmd","deviceidle","tempwhitelist","-u","0","-d","10000",g.context.packageName),
                timeoutMs=5_000,maxOutputBytes=1024),selected)
            val accepted=result.exitCode==0 && !result.timedOut && !result.cancelled
            g.events.emit(Event(System.currentTimeMillis(),eventType="gateway.background_start.assisted",requestId=ctx.requestId,traceId=ctx.traceId,
                agentId=ctx.agentId,deviceId=g.settings.deviceId,backend=selected.name,
                detail=if(accepted) "Authorized backend requested a ten-second startup allowance for SIConnect only" else "Authorized startup allowance was not confirmed"))
            if(!accepted) throw CapabilityException("BRIDGE_ALLOWANCE_UNCONFIRMED","Startup allowance was not confirmed; inspect the original maintenance request")
            if(!g.settings.gateway.value.enabled || !g.settings.controls.value.privilegedEnabled || g.privileged.kind!=selected)
                throw CapabilityException("BRIDGE_POLICY_CHANGED","Owner controls changed after the allowance request; bridge startup was not requested")
            try {ConnectionService.requestSavedStart(g)}
            catch(error:RuntimeException) {throw CapabilityException("BRIDGE_START_BLOCKED","Android did not accept the existing bridge startup (${error.javaClass.simpleName})")}
            return CapabilityResult(buildJsonObject {put("startupRequested",true);put("temporaryAllowanceMs",10_000);put("gatewayStateAtReturn",g.gateway.state.value.name)},
                selected,result.identity,VerificationStatus.NOT_VERIFIED,"Android accepted service startup; observe the actual gateway acknowledgement separately")
        }
    })
}
