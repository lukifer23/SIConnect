package dev.siconnect.app.termux

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.siconnect.core.CapabilityException
import kotlinx.serialization.json.*

internal data class TermuxEntry(val requestId:String,val agentId:String,val nonceHash:String,val packageUid:Int,val versionCode:Long,val installedAtMs:Long,
    val createdAtMs:Long,val deadlineAtMs:Long,val maxOutputBytes:Int,val phase:String,val waitStatus:String,
    val attemptedAtMs:Long?,val completedAtMs:Long?,val result:JsonObject?) {
    fun output():JsonObject=buildJsonObject {
        put("executionId",requestId);put("packageName",TermuxContract.PACKAGE);put("packageUid",packageUid)
        put("packageVersionCode",versionCode);put("packageFirstInstallAtMs",installedAtMs)
        put("identity","Termux app uid=$packageUid (Android package identity)")
        put("phase",phase);put("waitStatus",waitStatus);put("createdAtMs",createdAtMs);put("deadlineAtMs",deadlineAtMs)
        attemptedAtMs?.let {put("dispatchAttemptedAtMs",it)};completedAtMs?.let {put("callbackAtMs",it)}
        put("callbackReceived",result!=null);put("processTerminationVerified",false)
        put("mayStillBeRunning",phase=="DISPATCHING")
        put("automaticReplayAllowed",false)
        result?.forEach {(k,v)->put(k,v)}
    }
}

/** Independent bounded journal: command payloads already belong to core requests; never replay them here. */
internal class TermuxJournal(context:Context,name:String="termux.db"):SQLiteOpenHelper(context,name,null,1) {
    private var payloadCursor:Pair<Long,String>?=null
    init {setWriteAheadLoggingEnabled(true)}
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE executions(request_id TEXT PRIMARY KEY,agent_id TEXT NOT NULL,nonce_hash TEXT NOT NULL,
            package_uid INTEGER NOT NULL,package_version INTEGER NOT NULL,installed_at INTEGER NOT NULL,
            created_at INTEGER NOT NULL,deadline_at INTEGER NOT NULL,max_output INTEGER NOT NULL,
            phase TEXT NOT NULL,wait_status TEXT NOT NULL,attempted_at INTEGER,completed_at INTEGER,result_json TEXT,
            callback_success INTEGER,policy_state TEXT,error_message TEXT,payload_expired INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX executions_agent ON executions(agent_id,created_at)")
        db.execSQL("CREATE INDEX executions_phase ON executions(phase)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {error("Unsupported Termux journal migration; preserving evidence")}
    fun recoverOrphans() {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.execSQL("UPDATE executions SET phase='NOT_DISPATCHED',wait_status='PROCESS_LOST' WHERE phase='PREPARED'")
            db.execSQL("UPDATE executions SET wait_status='PROCESS_LOST' WHERE phase='DISPATCHING' AND wait_status='WAITING'")
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun prepare(id:String,agent:String,nonceHash:String,uid:Int,version:Long,installedAt:Long,now:Long,deadline:Long,maxOutput:Int) {
        val db=writableDatabase;db.beginTransaction()
        try {
            // No automatic pruning: a late result may be the only outcome evidence for
            // a core request left UNKNOWN_OUTCOME by process death or cancellation.
            // Rows stay DISPATCHING so a late callback can still be recorded, but a dispatch whose
            // deadline passed more than STALE_DISPATCH_GRACE_MS ago no longer blocks admission.
            // Otherwise 64 commands that never call back (servers, killed Termux) lock out Termux forever.
            val pending=db.rawQuery("SELECT count(*) FROM executions WHERE phase='PREPARED' OR (phase='DISPATCHING' AND deadline_at>?)",
                arrayOf((now-TermuxContract.STALE_DISPATCH_GRACE_MS).toString())).use {it.moveToFirst();it.getInt(0)}
            if(pending>=TermuxContract.MAX_PENDING) throw CapabilityException("TERMUX_UNRESOLVED_LIMIT","${TermuxContract.MAX_PENDING} recent Termux dispatches have no completion evidence; inspect existing results or wait for their deadlines to lapse before starting more",noSideEffects=true)
            db.insertOrThrow("executions",null,ContentValues().apply {
                put("request_id",id);put("agent_id",agent);put("nonce_hash",nonceHash);put("package_uid",uid)
                put("package_version",version);put("installed_at",installedAt)
                put("created_at",now);put("deadline_at",deadline);put("max_output",maxOutput)
                put("phase","PREPARED");put("wait_status","WAITING")
            })
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun entry(id:String):TermuxEntry?=readableDatabase.rawQuery("SELECT * FROM executions WHERE request_id=?",arrayOf(id)).use {c->
        if(!c.moveToFirst()) return@use null
        fun s(key:String)=c.getString(c.getColumnIndexOrThrow(key))
        fun long(key:String)=c.getColumnIndexOrThrow(key).let {if(c.isNull(it)) null else c.getLong(it)}
        TermuxEntry(s("request_id"),s("agent_id"),s("nonce_hash"),requireNotNull(long("package_uid")).toInt(),requireNotNull(long("package_version")),requireNotNull(long("installed_at")),
            requireNotNull(long("created_at")),requireNotNull(long("deadline_at")),requireNotNull(long("max_output")).toInt(),
            s("phase"),s("wait_status"),long("attempted_at"),long("completed_at"),
            s("result_json")?.let {Json.parseToJsonElement(it).jsonObject})
    }
    fun markDispatch(id:String,at:Long) {
        check(writableDatabase.update("executions",ContentValues().apply {put("phase","DISPATCHING");put("attempted_at",at)},
            "request_id=? AND phase='PREPARED'",arrayOf(id))==1) {"Termux dispatch journal was not prepared"}
    }
    fun notDispatched(id:String) {writableDatabase.execSQL("UPDATE executions SET phase='NOT_DISPATCHED',wait_status='REJECTED' WHERE request_id=? AND phase IN ('PREPARED','DISPATCHING') AND result_json IS NULL",arrayOf(id))}
    fun waiterEnded(id:String,reason:String) {
        require(reason in setOf("TIMED_OUT","CANCELLED","FAILED"))
        writableDatabase.execSQL("UPDATE executions SET wait_status=? WHERE request_id=? AND wait_status='WAITING'",arrayOf(reason,id))
    }
    fun receive(id:String,nonceHash:String,result:JsonObject,now:Long):Boolean {
        val db=writableDatabase;db.beginTransaction()
        try {
            val error=result["executionErrorMessage"]?.jsonPrimitive?.contentOrNull
            val success=TermuxContract.commandSucceeded(result["executionErrorCode"]?.jsonPrimitive?.intOrNull,result["exitCode"]?.jsonPrimitive?.intOrNull)
            val policy=when {
                error?.contains("allow-external-apps",ignoreCase=true)==true->"REJECTED"
                result["executionErrorCode"]?.jsonPrimitive?.intOrNull==TermuxContract.RESULT_ERR_SUCCESS || result["exitCode"]?.jsonPrimitive?.intOrNull!=null->"CONFIRMED_ALLOWED"
                else->null
            }
            val changed=db.update("executions",ContentValues().apply {
                put("phase","RESULT_RECEIVED");put("completed_at",now);put("result_json",result.toString())
                put("callback_success",if(success) 1 else 0);put("policy_state",policy);put("error_message",error)
            },"request_id=? AND nonce_hash=? AND phase='DISPATCHING' AND result_json IS NULL",arrayOf(id,nonceHash))==1
            if(changed) db.execSQL("UPDATE executions SET wait_status='COMPLETED' WHERE request_id=? AND wait_status='WAITING'",arrayOf(id))
            db.setTransactionSuccessful();return changed
        } finally {db.endTransaction()}
    }
    fun pendingCount(agent:String?=null):Int=readableDatabase.rawQuery("SELECT count(*) FROM executions WHERE phase IN ('PREPARED','DISPATCHING')"+
        if(agent==null) "" else " AND agent_id=?",agent?.let {arrayOf(it)}).use {it.moveToFirst();it.getInt(0)}
    fun recent(agent:String,limit:Int,unresolvedOnly:Boolean,beforeId:String?):JsonObject {
        val before=beforeId?.let {entry(it)?.takeIf {row->row.agentId==agent}}
        if(beforeId!=null && before==null) throw CapabilityException("TERMUX_RESULT_NOT_FOUND","Pagination cursor does not belong to this agent",noSideEffects=true)
        val args=mutableListOf(agent)
        var where="agent_id=?"
        if(unresolvedOnly) where+=" AND phase IN ('PREPARED','DISPATCHING')"
        before?.let {where+=" AND (created_at<? OR (created_at=? AND request_id<?))";args+=listOf(it.createdAtMs.toString(),it.createdAtMs.toString(),it.requestId)}
        val bound=limit.coerceIn(1,50)
        val rows=readableDatabase.query("executions",arrayOf("request_id","phase","wait_status","created_at","deadline_at","attempted_at","completed_at","package_uid","package_version","installed_at"),
            where,args.toTypedArray(),null,null,"created_at DESC,request_id DESC",bound.toString()).use {c->buildList {
            while(c.moveToNext()) add(buildJsonObject {
                put("executionId",c.getString(0));put("phase",c.getString(1));put("waitStatus",c.getString(2))
                put("createdAtMs",c.getLong(3));put("deadlineAtMs",c.getLong(4))
                if(!c.isNull(5)) put("dispatchAttemptedAtMs",c.getLong(5))
                if(!c.isNull(6)) put("callbackAtMs",c.getLong(6))
                put("callbackReceived",!c.isNull(6));put("packageUid",c.getInt(7));put("packageVersionCode",c.getLong(8));put("packageFirstInstallAtMs",c.getLong(9))
            })
        }}
        return buildJsonObject {
            put("executions",JsonArray(rows));put("unresolvedOnly",unresolvedOnly)
            put("nextBeforeExecutionId",if(rows.size==bound) rows.last().getValue("executionId") else JsonNull)
        }
    }
    private fun identityArgs(uid:Int,version:Long,installedAt:Long,agent:String?)=arrayOf(uid.toString(),version.toString(),installedAt.toString())+(agent?.let {arrayOf(it)} ?: emptyArray())
    private fun identityWhere(agent:String?)="package_uid=? AND package_version=? AND installed_at=?"+(if(agent==null) "" else " AND agent_id=?")
    fun lastCallbackAtMs(uid:Int,version:Long,installedAt:Long,agent:String?):Long?=scalarLong("SELECT max(completed_at) FROM executions WHERE "+identityWhere(agent),identityArgs(uid,version,installedAt,agent))
    fun lastSuccessAtMs(uid:Int,version:Long,installedAt:Long,agent:String?):Long?=scalarLong("SELECT max(completed_at) FROM executions WHERE callback_success=1 AND "+identityWhere(agent),identityArgs(uid,version,installedAt,agent))
    private fun scalarLong(sql:String,args:Array<String>):Long?=readableDatabase.rawQuery(sql,args).use {if(it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null}
    fun lastPolicyState(uid:Int,version:Long,installedAt:Long):String=readableDatabase.rawQuery("SELECT policy_state FROM executions WHERE policy_state IS NOT NULL AND "+identityWhere(null)+" ORDER BY completed_at DESC LIMIT 1",identityArgs(uid,version,installedAt,null))
        .use {if(it.moveToFirst()) it.getString(0) else "UNKNOWN"}
    fun lastError(uid:Int,version:Long,installedAt:Long,agent:String?):String?=readableDatabase.rawQuery("SELECT error_message FROM executions WHERE completed_at IS NOT NULL AND "+identityWhere(agent)+" ORDER BY completed_at DESC LIMIT 1",identityArgs(uid,version,installedAt,agent))
        .use {if(it.moveToFirst() && !it.isNull(0)) it.getString(0) else null}
    @Synchronized fun compactPayloads(cutoff:Long,limit:Int,canCompact:(String)->Boolean):Int {
        val cursor=payloadCursor
        val after=if(cursor==null) "" else " AND (completed_at>? OR (completed_at=? AND request_id>?))"
        val args=mutableListOf(cutoff.toString())
        cursor?.let {args+=listOf(it.first.toString(),it.first.toString(),it.second)}
        args+=limit.coerceIn(1,200).toString()
        val candidates=readableDatabase.rawQuery("SELECT request_id,completed_at FROM executions WHERE phase='RESULT_RECEIVED' AND payload_expired=0 AND completed_at<?$after ORDER BY completed_at,request_id LIMIT ?",
            args.toTypedArray()).use {c->buildList {while(c.moveToNext()) add(c.getString(0) to c.getLong(1))}}
        // Protected UNKNOWN/missing core records advance the bounded scan too.
        // Wrap on a subsequent pass only after the current keyset is exhausted.
        payloadCursor=candidates.lastOrNull()?.let {it.second to it.first}
        var count=0
        for((id,_) in candidates) {
            // Never hold the journal writer while asking the core request store.
            // The caller must require a known terminal state excluding UNKNOWN_OUTCOME.
            if(!canCompact(id)) continue
            val old=entry(id)?.result ?: continue
            val compacted=buildJsonObject {
                old.filterKeys {it !in setOf("stdout","stderr","executionErrorMessage")}.forEach {(k,v)->put(k,v)}
                put("payloadExpired",true);put("payloadExpiredAtMs",System.currentTimeMillis())
                put("stdoutRetainedBytes",0);put("stderrRetainedBytes",0)
            }
            count+=writableDatabase.update("executions",ContentValues().apply {
                put("result_json",compacted.toString());put("payload_expired",1);putNull("error_message")
            },"request_id=? AND phase='RESULT_RECEIVED' AND payload_expired=0",arrayOf(id))
        }
        return count
    }
}
