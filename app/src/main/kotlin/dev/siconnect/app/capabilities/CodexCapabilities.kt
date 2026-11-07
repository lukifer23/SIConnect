package dev.siconnect.app.capabilities

import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.termux.CodexLaunch
import dev.siconnect.app.termux.TermuxBridge
import dev.siconnect.app.termux.TermuxContract
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Fresh noninteractive runs through the real Termux capability, its policy and durable result path. */
fun registerCodexCapabilities(g:RuntimeGraph) {
    fun launch(args:JsonObject)=CodexLaunch.fullAccessExec(
        args["nodeEntrypoint"]?.jsonPrimitive?.content,args.getValue("cwd").jsonPrimitive.content,
        args.getValue("prompt").jsonPrimitive.content,args["profile"]?.jsonPrimitive?.content,
        args["allowOutsideGitRepository"]?.jsonPrimitive?.boolean ?: false,args["timeoutMs"]?.jsonPrimitive?.long ?: 120000,
        androidCompatibilityLauncher=args["androidCompatibilityLauncher"]?.jsonPrimitive?.content)
    g.registry.register(object:Capability {
        override val descriptor=CapabilityDescriptor("codex.execute",title="Run Codex noninteractively in Termux",
            description="Start a fresh Codex exec run with an absolute workspace and a nonblank prompt argument. Choose exactly one launcher: nodeEntrypoint runs the existing npm JavaScript entry through fixed Termux Node; androidCompatibilityLauncher runs an explicitly selected local script through fixed Termux sh with the same Codex exec argv. Compatibility scripts may provide the owner's environment/DNS/proot setup; SIConnect does not choose, create, edit or independently verify a script or claim Android compatibility. The existing Termux login environment loads quietly and actual command JSONL stdout/stderr is preserved. Per-run approval_policy=never and danger-full-access avoid interactive approval/sandbox launches for this authorized workflow; Android UID/SELinux restrictions still apply. Existing CLI config/auth/profiles/rules/trusted hooks remain in effect. The launcher does not automatically login, globally reconfigure Codex, resume sessions or retry. This invokes a real same-agent termux.execute child: both capability scopes and the existing Termux arbitrary-shell policy are required; a child requiring approval is returned without approval bypass. Waits within the original deadline, then returns executionId for actual late termux.result retrieval. Native exit/callback or a turn.completed event is not proof of all requested external effects; bounded/truncated JSONL is reported as such.",
            inputSchema=objectSchema {
                string("nodeEntrypoint","Absolute Codex npm JavaScript entrypoint; exactly one of this or androidCompatibilityLauncher is required",maxLength=1024)
                string("androidCompatibilityLauncher","Absolute owner-selected local compatibility script executed by Termux sh; mutually exclusive with nodeEntrypoint; compatibility is not assumed",maxLength=1024)
                string("cwd","Absolute existing working directory",true,maxLength=1024)
                string("prompt","Exact nonblank prompt argument; '-' stdin mode is refused",true,maxLength=8192)
                string("profile","Optional existing Codex profile; no profile is invented",maxLength=200)
                boolean("allowOutsideGitRepository","Explicit --skip-git-repo-check; defaultfalse")
                integer("timeoutMs","Bounded native completion wait; default120000, no guaranteed process termination",min=1,max=120000)
            },backend=BackendKind.NATIVE,risk=RiskClass.HIGH_IMPACT,interaction=InteractionMode.BACKGROUND,timeoutMs=125000,
            sideEffects=listOf("Codex may read/change/delete Termux-accessible files, run commands and use configured network/accounts under full per-run Termux permissions"))
        override fun availability()=g.registry.get("termux.execute")?.availability() ?: Availability.UNSUPPORTED
        override fun availabilityDetail()="Requires real Termux RUN_COMMAND access and exactly one explicit Node entrypoint or owner-selected compatibility script. Script/platform compatibility is not verified by discovery. Both codex.execute and the real same-agent termux.execute child policy must allow the action."
        override fun assess(args:JsonObject)=RiskAssessment(RiskClass.HIGH_IMPACT,descriptor.title,target="com.termux",
            sideEffects=descriptor.sideEffects,shellCommand=TermuxContract.policyCommand(launch(args)))
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult=withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val native=launch(args)
            val parent=g.requests.get(ctx.requestId) ?: throw CapabilityException("REQUEST_NOT_FOUND","Original Codex request is unavailable",noSideEffects=true)
            val now=System.currentTimeMillis()
            val deadline=minOf(parent.expiresAtMs,ctx.deadlineAtMs ?: now+descriptor.timeoutMs)
            if(parent.state!=RequestState.EXECUTING || parent.agentId!=ctx.agentId || parent.capabilityId!=descriptor.id || parent.capabilityVersion!=descriptor.version ||
                (parent.selectedBackend!=null && parent.selectedBackend!=descriptor.backend) ||
                parent.argsHash!=Canonical.actionHash(descriptor.id,descriptor.version,args) || deadline<=now)
                throw CapabilityException("CODEX_REQUEST_CHANGED","Original Codex request changed, was cancelled or expired before child admission",noSideEffects=true)
            val policy=PolicyEngine().evaluate(g.agents.grant(ctx.agentId),descriptor,args,parent.assessment ?: assess(args),availability(),
                g.settings.controls.value,Origin.valueOf(parent.origin),now)
            if(policy.outcome in setOf(PolicyOutcome.DENY,PolicyOutcome.UNAVAILABLE) || (policy.outcome==PolicyOutcome.REQUIRE_APPROVAL && parent.approvedBy==null))
                throw CapabilityException("POLICY_DENIED",policy.reason,noSideEffects=true)
            val childId=CodexLaunch.childRequestId(ctx.requestId)
          try {
            currentCoroutineContext().ensureActive()
            var child=g.engine.submit(InvocationRequest(requestId=childId,traceId=ctx.traceId,agentId=ctx.agentId,
                capabilityId="termux.execute",arguments=native,expiresAtMs=deadline,parentRequestId=ctx.requestId),
                Origin.valueOf(parent.origin),awaitCompletion=false)
            // Leave response headroom before the enclosing deadline. The child remains
            // engine-owned and its exact durable ID is returned even when still running.
            if(!child.state.isTerminal && child.state!=RequestState.AWAITING_APPROVAL) {
                val remaining=deadline-System.currentTimeMillis()-500
                if(remaining>0) child=g.engine.await(childId,remaining,untilTerminal=true) ?: child
            }
            val retained=try {TermuxBridge.get(g.context).result(ctx.agentId,childId)}
                catch(error:CapabilityException) {if(error.code=="TERMUX_RESULT_NOT_FOUND") null else throw error}
            val output=retained ?: (child.result?.output as? JsonObject)
            CapabilityResult(buildJsonObject {
                put("parentRequestId",ctx.requestId);put("executionId",childId);put("childRequestId",childId);put("childState",child.state.name)
                put("awaitingApproval",child.state==RequestState.AWAITING_APPROVAL);put("nativeResult",output ?: JsonNull)
                child.errorCode?.let {put("childErrorCode",it)};child.errorMessage?.let {put("childErrorMessage",it)}
                put("jsonlRequested",true);put("jsonlTruncated",output?.get("stdoutTruncated") ?: JsonNull)
                put("launcherMode",if(args["androidCompatibilityLauncher"]!=null) "ANDROID_COMPATIBILITY_SCRIPT" else "NODE_ENTRYPOINT")
                put("androidCompatibilityVerified",false)
                put("taskOutcomeVerified",false);put("phoneControlConnectionVerified",false);put("processTerminationVerified",false);put("doNotReplay",true)
                put("phoneControlGuidance","This launches Codex in Termux. Using SIConnect phone tools from that Codex run requires its own configured MCP connection; this invocation does not pair a client or change credentials.")
                put("nextStep",if(child.state==RequestState.AWAITING_APPROVAL) "Review the actual Termux child request through the existing owner approval flow"
                    else "Read termux.result with this exact executionId for native output or late completion; never repeat an uncertain Codex run")
            },BackendKind.NATIVE,output?.get("identity")?.jsonPrimitive?.content,
                VerificationStatus.NOT_VERIFIED,"Real Termux child state and bounded native/JSONL observations; not independent task-effect verification")
          } catch(error:CancellationException) {
            // Cancel only the exact owned child wait/approval; Termux exposes no
            // verified subprocess termination API and late callbacks stay retained.
            withContext(NonCancellable) {withTimeoutOrNull(750) {runCatching {
                val child=g.requests.get(childId)
                if(child?.agentId==ctx.agentId && child.parentRequestId==ctx.requestId) g.engine.cancel(childId,ctx.agentId)
            }}}
            throw error
          }
        }
    })
}
