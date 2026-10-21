package dev.siconnect.app.capabilities

import android.os.Process
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.data.AppJson
import dev.siconnect.core.*
import kotlinx.serialization.json.*

fun registerProposalCapabilities(g:RuntimeGraph) {
    fun register(id:String,title:String,input:JsonObject,body:(JsonObject,ExecutionContext)->JsonObject) {
        g.registry.register(object:Capability {
            override val descriptor=CapabilityDescriptor(id,title=title,
                description="$title. Proposals are untrusted suggestions, never owner approvals. Only the phone owner can accept. Acceptance invokes the original agent's exact action through current policy; additional execution approval may be needed. Do not include whole conversations or secrets. Accepted actions use a stable linked request; inspect it rather than resubmitting uncertain mutations.",
                inputSchema=input,outputSchema=objectSchema {obj("proposal","Stored proposal");prop("proposals",false,buildJsonObject {put("type","array");put("maxItems",50);putJsonObject("items") {put("type","object")}})},
                backend=BackendKind.NATIVE,risk=if(id.endsWith("create") || id.endsWith("withdraw")) RiskClass.LOW_IMPACT else RiskClass.READ_ONLY,
                interaction=InteractionMode.BACKGROUND,sideEffects=if(id.endsWith("create") || id.endsWith("withdraw")) listOf("Stores or dismisses this agent's proposed action; does not execute it") else emptyList())
            override fun availability()=Availability.IMPLEMENTED
            override suspend fun execute(args:JsonObject,ctx:ExecutionContext)=CapabilityResult(body(args,ctx),BackendKind.NATIVE,"uid=${Process.myUid()}")
        })
    }
    fun one(p:ActionProposal)=buildJsonObject {put("proposal",AppJson.encodeToJsonElement(p))}
    fun ObjectSchemaBuilder.id() {string("proposalId","Exact proposal identity belonging to this agent",true,maxLength=100)}
    register("proposals.create","Propose a follow-up for local owner review",objectSchema {
        string("title","Short action title",true,maxLength=120);string("summary","Minimal readable proposal, not instructions to the owner",true,maxLength=1000)
        string("reason","Why this follow-up may be useful",true,maxLength=1000);string("sourceType","Source category without private content",true,maxLength=40)
        string("sourceReference","Minimal reference; untrusted display text, not opened automatically",maxLength=500)
        string("capabilityId","Exact registered execution capability",true,maxLength=100);obj("arguments","Exact target arguments, at most 16 KiB",true)
        string("idempotencyKey","Stable per-agent proposal identity; reuse for duplicate submissions",true,maxLength=100)
        integer("expiresAtMs","Expiration, at most seven days from now",true,min=1)
    }) {a,c->one(g.proposals.create(c.agentId,a.str("title"),a.str("summary"),a.str("reason"),a.str("sourceType"),a["sourceReference"]?.jsonPrimitive?.content,
        a.str("capabilityId"),a.getValue("arguments").jsonObject,a.str("idempotencyKey"),a.getValue("expiresAtMs").jsonPrimitive.long))}
    register("proposals.get","Retrieve this agent's proposal and original request link",objectSchema {id()}) {a,c->one(g.proposals.getForAgent(c.agentId,a.str("proposalId")))}
    register("proposals.withdraw","Withdraw an unaccepted proposal owned by this agent",objectSchema {id()}) {a,c->one(g.proposals.withdraw(c.agentId,a.str("proposalId")))}
    register("proposals.list","List this agent's proposals with bounded keyset pagination",objectSchema {
        integer("limit","Page size, default 20",min=1,max=50);integer("beforeCreatedAtMs","Last row creation time; also supply beforeProposalId",min=1)
        string("beforeProposalId","Last row identity; also supply beforeCreatedAtMs",maxLength=100)
    }) {a,c->buildJsonObject {put("proposals",JsonArray(g.proposals.list(c.agentId,a.long("limit",20).toInt(),a["beforeCreatedAtMs"]?.jsonPrimitive?.longOrNull,a["beforeProposalId"]?.jsonPrimitive?.content).map {p->
        // Lists omit exact arguments, source content and assessment shell text. Fetch one proposal for review.
        buildJsonObject {
            put("proposalId",p.proposalId);put("title",p.title);put("summary",p.summary.take(240));put("state",p.state.name)
            put("createdAtMs",p.createdAtMs);put("expiresAtMs",p.expiresAtMs);put("capabilityId",p.capabilityId);put("risk",p.assessment.risk.name)
            p.linkedRequestId?.let {put("linkedRequestId",it)};p.requestState?.let {put("requestState",it.name)}
        }
    }))}}
}
