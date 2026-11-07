package dev.siconnect.app.capabilities

import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.data.AppJson
import dev.siconnect.app.termux.TermuxBridge
import dev.siconnect.app.termux.TermuxContract
import dev.siconnect.app.termux.TermuxStatus
import dev.siconnect.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Actual Termux app IPC. The core engine owns approval, durable request IDs and crash recovery. */
fun registerTermuxCapabilities(g:RuntimeGraph) {
    fun bridge()=TermuxBridge.get(g.context)
    g.registry.register(object:Capability {
        override val descriptor=CapabilityDescriptor("termux.status",title="Termux native command connection status",
            description="Read installed Termux version, Android app UID, RUN_COMMAND permission, retained callback observations and unresolved count. Never starts a command or reads Termux private configuration. A past success is timestamped evidence, not live health or Codex authentication.",
            inputSchema=objectSchema(),backend=BackendKind.NATIVE,risk=RiskClass.READ_ONLY,interaction=InteractionMode.BACKGROUND)
        override fun availability()=Availability.IMPLEMENTED
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult=withContext(Dispatchers.IO) {
            val status=bridge().status(ctx.agentId)
            CapabilityResult(AppJson.encodeToJsonElement(TermuxStatus.serializer(),status),BackendKind.NATIVE,
                verification=VerificationStatus.VERIFIED,verificationDetail="Actual installed package and Android permission observations; external-apps setting inferred only from retained callbacks")
        }
    })
    g.registry.register(object:Capability {
        override val descriptor=CapabilityDescriptor("termux.execute",title="Execute a background command in Termux",
            description="Run an exact executable and argv in the installed Termux app UID through its official RUN_COMMAND service. Requires Termux>=0.109, Android Run commands in Termux permission, and owner-configured allow-external-apps=true. Uses a background process with no terminal UI; executable, environment, working directory and app network/storage follow Termux, not privileged shell. No Codex binary or login is assumed. Waits up to120seconds for a durable one-shot result; cancellation, timeout or SIConnect process death cannot guarantee the Termux command stopped. Never replay uncertain actions; use termux.result with the original executionId/requestId for late completion. Exit status/output do not prove external business outcomes.",
            inputSchema=objectSchema {
                string("executable","Absolute executable path in the Termux environment; no implicit shell or wrapper",true,maxLength=1024)
                prop("arguments",false,buildJsonObject {
                    put("type","array");put("description","Exact argv entries; optional empty array");put("maxItems",128)
                    put("items",buildJsonObject {put("type","string");put("maxLength",8192)})
                })
                string("cwd","Absolute working directory; omitted uses Termux's own default",maxLength=1024)
                string("stdin","Optional UTF-8 input; entire executable/argv/cwd/stdin bound to64KiB",maxLength=32768)
                integer("timeoutMs","Maximum wait for completion, not a promise to terminate the command",min=1,max=120000)
                integer("maxOutputBytes","Maximum retained UTF-8 bytes per stream; default16384",min=1024,max=32768)
            },permissions=listOf(TermuxBridge.RUN_COMMAND_PERMISSION),backend=BackendKind.NATIVE,risk=RiskClass.HIGH_IMPACT,
            sideEffects=listOf("Arbitrary code can read/change/delete Termux-accessible data, use app credentials/network, or launch other commands"),
            timeoutMs=125000,interaction=InteractionMode.BACKGROUND)
        override fun availability()=bridge().availability()
        override fun availabilityDetail()="Owner must install/enable Termux>=0.109, grant SIConnect Run commands in Termux, and enable allow-external-apps in Termux settings. Policy readiness requires actual callback evidence; Termux private files are never changed automatically."
        override fun assess(args:JsonObject)=RiskAssessment(RiskClass.HIGH_IMPACT,descriptor.title,
            target=TermuxContract.PACKAGE,sideEffects=descriptor.sideEffects,shellCommand=TermuxContract.policyCommand(args))
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {
            val output=bridge().execute(args,ctx) {
                // An internal queue must not outlive owner revocation or emergency stop.
                // Re-read exact core identity and current policy after slot acquisition,
                // and again immediately before the native service dispatch.
                val record=g.requests.get(ctx.requestId)
                    ?: throw CapabilityException("REQUEST_NOT_FOUND","Original Termux request no longer exists",noSideEffects=true)
                if(record.state!=RequestState.EXECUTING || record.agentId!=ctx.agentId || record.capabilityId!=descriptor.id ||
                    record.capabilityVersion!=descriptor.version || (record.selectedBackend!=null && record.selectedBackend!=BackendKind.NATIVE) ||
                    record.argsHash!=Canonical.actionHash(descriptor.id,descriptor.version,args) || System.currentTimeMillis()>=record.expiresAtMs)
                    throw CapabilityException("TERMUX_REQUEST_CHANGED","Original request changed or expired before Termux dispatch",noSideEffects=true)
                val decision=PolicyEngine().evaluate(g.agents.grant(ctx.agentId),descriptor,args,record.assessment ?: assess(args),
                    availability(),g.settings.controls.value,Origin.valueOf(record.origin),System.currentTimeMillis())
                if(decision.outcome in setOf(PolicyOutcome.DENY,PolicyOutcome.UNAVAILABLE) ||
                    (decision.outcome==PolicyOutcome.REQUIRE_APPROVAL && record.approvedBy==null))
                    throw CapabilityException("POLICY_DENIED",decision.reason,noSideEffects=true)
            }
            val successful=output["commandSucceeded"]?.jsonPrimitive?.booleanOrNull==true
            return CapabilityResult(output,BackendKind.NATIVE,output["identity"]?.jsonPrimitive?.content,
                verification=if(successful) VerificationStatus.NOT_APPLICABLE else VerificationStatus.FAILED,
                verificationDetail=if(successful) "Termux returned real command completion and bounded output; external effects are not verified"
                    else "Termux reported command/service failure; effects may have occurred. Original execution journal remains available through termux.result",
                errorCode=if(successful) null else "TERMUX_COMMAND_FAILED")
        }
    })
    g.registry.register(object:Capability {
        override val descriptor=CapabilityDescriptor("termux.result",title="Read your retained Termux command result",
            description="Read the durable Termux callback journal for the exact original request ID belonging to this agent. Includes late results after wait timeout or process death without resurrecting or replaying the original core request. Missing callback means outcome remains uncertain, not stopped. Metadata and bounded callback output remain retained; no automatic replay or age-based deletion.",
            inputSchema=objectSchema {string("executionId","Original termux.execute requestId/executionId",true,maxLength=200)},
            backend=BackendKind.NATIVE,risk=RiskClass.READ_ONLY,interaction=InteractionMode.BACKGROUND)
        override fun availability()=Availability.IMPLEMENTED
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult=withContext(Dispatchers.IO) {
            CapabilityResult(bridge().result(ctx.agentId,args.getValue("executionId").jsonPrimitive.content),BackendKind.NATIVE,
                verification=VerificationStatus.VERIFIED,verificationDetail="Actual durable per-agent callback journal, without command dispatch")
        }
    })
    g.registry.register(object:Capability {
        override val descriptor=CapabilityDescriptor("termux.recent",title="List your Termux execution metadata",
            description="Discover this agent's durable execution IDs after timeouts or process death, including unresolved commands. Returns metadata only, without argv, output, errors or callback nonces. Use termux.result for an exact own execution. Pagination never executes or retries commands.",
            inputSchema=objectSchema {
                integer("limit","Maximum metadata rows; default20",min=1,max=50)
                boolean("unresolvedOnly","Return only dispatches with no retained callback; this does not prove they are running")
                string("beforeExecutionId","Optional nextBeforeExecutionId from the preceding page belonging to this agent",maxLength=200)
            },backend=BackendKind.NATIVE,risk=RiskClass.READ_ONLY,interaction=InteractionMode.BACKGROUND)
        override fun availability()=Availability.IMPLEMENTED
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult=withContext(Dispatchers.IO) {
            CapabilityResult(bridge().recent(ctx.agentId,args["limit"]?.jsonPrimitive?.int ?: 20,args["unresolvedOnly"]?.jsonPrimitive?.boolean ?: false,
                args["beforeExecutionId"]?.jsonPrimitive?.content),BackendKind.NATIVE,
                verification=VerificationStatus.VERIFIED,verificationDetail="Actual own-agent journal metadata without command dispatch")
        }
    })
}
