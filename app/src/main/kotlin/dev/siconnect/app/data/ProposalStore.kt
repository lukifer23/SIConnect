package dev.siconnect.app.data

import android.content.ContentValues
import dev.siconnect.core.*

class SqlProposalStore(private val db:SIConnectDb):ProposalStore {
    override fun <T> atomic(block:()->T):T {
        val w=db.writableDatabase;w.beginTransaction()
        try {val result=block();w.setTransactionSuccessful();return result} finally {w.endTransaction()}
    }
    private fun values(p:ActionProposal)=ContentValues().apply {
        put("proposal_id",p.proposalId);put("agent_id",p.agentId);put("idempotency_key",p.idempotencyKey)
        put("created_at",p.createdAtMs);put("state",p.state.name);put("revision",p.revision)
        put("request_id",p.linkedRequestId);put("json",AppJson.encodeToString(p))
    }
    override fun insert(proposal:ActionProposal):Boolean = db.writableDatabase.insertOrThrow("proposals",null,values(proposal))!=-1L
    override fun update(proposal:ActionProposal,expectedRevision:Int)=db.writableDatabase.update("proposals",values(proposal),"proposal_id=? AND revision=?",arrayOf(proposal.proposalId,expectedRevision.toString()))==1
    override fun get(id:String)=query("proposal_id=?",arrayOf(id)).firstOrNull()
    override fun find(agentId:String,key:String)=query("agent_id=? AND idempotency_key=?",arrayOf(agentId,key)).firstOrNull()
    override fun byRequest(id:String)=query("request_id=?",arrayOf(id)).firstOrNull()
    override fun active()=query("state IN ('PROPOSED','VIEWED','ACCEPTED','EXECUTING')",null,limit=null)
    override fun list(agentId:String?,limit:Int,beforeMs:Long?,beforeId:String?):List<ActionProposal> {
        require((beforeMs==null)==(beforeId==null)) {"Both cursor fields are required"}
        val where=mutableListOf<String>();val args=mutableListOf<String>()
        if(agentId!=null) {where+="agent_id=?";args+=agentId}
        if(beforeMs!=null) {where+="(created_at < ? OR (created_at=? AND proposal_id < ?))";args+=listOf(beforeMs.toString(),beforeMs.toString(),requireNotNull(beforeId))}
        return query(where.joinToString(" AND ").ifBlank {null},args.toTypedArray(),limit.coerceIn(1,50))
    }
    private fun query(where:String?,args:Array<String>?,limit:Int?=1):List<ActionProposal> = db.readableDatabase.query("proposals",arrayOf("json"),where,args,null,null,"created_at DESC,proposal_id DESC",limit?.toString()).mapAll {AppJson.decodeFromString(it.getString(0))}
}
