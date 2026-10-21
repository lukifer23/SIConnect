package dev.siconnect.app.data

import android.content.ContentValues
import dev.siconnect.core.*

/** Indexed list metadata is separate from private, on-demand execution arguments. */
class SqlProposalStore(private val db:SIConnectDb):ProposalStore {
    override fun <T> atomic(block:()->T):T {
        val w=db.writableDatabase;w.beginTransaction()
        try {val result=block();w.setTransactionSuccessful();return result} finally {w.endTransaction()}
    }
    override fun insert(proposal:ActionProposal)=db.writableDatabase.insertOrThrow("proposals",null,values(proposal))!=-1L
    override fun update(proposal:ActionProposal,expectedRevision:Int)=db.writableDatabase.update("proposals",values(proposal),"proposal_id=? AND revision=?",arrayOf(proposal.proposalId,expectedRevision.toString()))==1
    override fun get(id:String)=query("proposal_id=?",arrayOf(id)).firstOrNull()
    override fun find(agentId:String,key:String)=query("agent_id=? AND idempotency_key=?",arrayOf(agentId,key)).firstOrNull()
    override fun byRequest(id:String)=query("request_id=?",arrayOf(id)).firstOrNull()
    override fun active(afterId:String?,limit:Int)=query("state IN ('PROPOSED','VIEWED','ACCEPTED','EXECUTING')"+(if(afterId!=null) " AND proposal_id>?" else ""),afterId?.let {arrayOf(it)},limit.coerceIn(1,100),"proposal_id ASC")
    override fun expiring(nowMs:Long,limit:Int)=query("(state IN ('PROPOSED','VIEWED') AND expires_at<=?) OR (state='ACCEPTED' AND dispatch_expires_at<=? AND NOT EXISTS (SELECT 1 FROM requests WHERE requests.request_id=proposals.request_id))",arrayOf("$nowMs","$nowMs"),limit.coerceIn(1,100),"expires_at ASC")
    override fun activeCount(agentId:String,nowMs:Long)=count("agent_id=? AND ((state IN ('PROPOSED','VIEWED') AND expires_at>?) OR state IN ('ACCEPTED','EXECUTING'))",arrayOf(agentId,"$nowMs"))
    override fun pendingCount(nowMs:Long)=count("state IN ('PROPOSED','VIEWED') AND expires_at>?",arrayOf("$nowMs"))
    override fun nextExpiration(nowMs:Long):Long?=db.readableDatabase.rawQuery("SELECT min(expires_at) FROM proposals WHERE state IN ('PROPOSED','VIEWED') AND expires_at>?",arrayOf("$nowMs")).use {it.moveToFirst();if(it.isNull(0)) null else it.getLong(0)}
    override fun reconciliationCandidates(limit:Int):List<ActionProposal> = db.readableDatabase.rawQuery("""SELECT p.json FROM proposals p LEFT JOIN requests r ON r.request_id=p.request_id
        WHERE p.state IN ('ACCEPTED','EXECUTING') AND (r.request_id IS NULL OR p.request_state IS NULL OR p.request_state!=r.state)
        ORDER BY COALESCE(p.dispatch_attempt_at,0),p.created_at,p.proposal_id LIMIT ?""",arrayOf(limit.coerceIn(1,100).toString())).mapAll {AppJson.decodeFromString(it.getString(0))}
    override fun summaries(query:ProposalQuery,nowMs:Long):List<ProposalSummary> {
        require((query.cursorMs==null)==(query.cursorId==null)) {"Both cursor fields are required"}
        val effective="CASE WHEN state IN ('PROPOSED','VIEWED') AND expires_at<=${nowMs} THEN 'EXPIRED' ELSE state END"
        val where=mutableListOf<String>();val args=mutableListOf<String>()
        query.agentId?.let {where+="agent_id=?";args+=it}
        query.states?.let {states->if(states.isEmpty()) return emptyList();where+="$effective IN (${states.joinToString(",") {"?"}})";args+=states.map {it.name}}
        val op=if(query.oldestFirst) ">" else "<";val order=if(query.oldestFirst) "ASC" else "DESC"
        query.cursorMs?.let {where+="(created_at $op ? OR (created_at=? AND proposal_id $op ?))";args+=listOf("$it","$it",requireNotNull(query.cursorId))}
        return db.readableDatabase.query("proposals",arrayOf("proposal_id","agent_id","created_at","expires_at","title","summary","capability_id","risk","$effective AS effective_state","request_id","request_state","error_code","verification"),where.joinToString(" AND ").ifBlank {null},args.toTypedArray(),null,null,"created_at $order,proposal_id $order",query.limit.coerceIn(1,50).toString()).mapAll {c->
            ProposalSummary(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3),c.getString(4),c.getString(5),c.getString(6),RiskClass.valueOf(c.getString(7)),ProposalState.valueOf(c.getString(8)),c.getString(9),c.getString(10)?.let(RequestState::valueOf),c.getString(11),c.getString(12)?.let(VerificationStatus::valueOf))
        }
    }
    private fun count(where:String,args:Array<String>)=db.readableDatabase.rawQuery("SELECT count(*) FROM proposals WHERE $where",args).use {it.moveToFirst();it.getInt(0)}
    private fun query(where:String?,args:Array<String>?,limit:Int=1,order:String="created_at DESC,proposal_id DESC"):List<ActionProposal> = db.readableDatabase.query("proposals",arrayOf("json"),where,args,null,null,order,limit.toString()).mapAll {AppJson.decodeFromString(it.getString(0))}
    companion object {
        fun metadata(p:ActionProposal)=ContentValues().apply {
            put("expires_at",p.expiresAtMs);put("dispatch_expires_at",p.executionExpiresAtMs)
            put("title",p.title);put("summary",p.summary.take(240));put("capability_id",p.capabilityId);put("risk",p.assessment.risk.name)
            put("request_state",p.requestState?.name);put("error_code",p.errorCode);put("verification",p.verification?.name);put("dispatch_attempt_at",p.lastDispatchAtMs)
        }
        private fun values(p:ActionProposal)=metadata(p).apply {
            put("proposal_id",p.proposalId);put("agent_id",p.agentId);put("idempotency_key",p.idempotencyKey)
            put("created_at",p.createdAtMs);put("state",p.state.name);put("revision",p.revision)
            put("request_id",p.linkedRequestId);put("json",AppJson.encodeToString(p))
        }
    }
}
