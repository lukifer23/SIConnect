package dev.siconnect.app.clocks

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.siconnect.core.CapabilityException
import kotlinx.serialization.json.*

internal data class ManagedTimer(val timerId:String,val agentId:String,val startRequestId:String,val packageName:String,val uid:Int,
    val installedAtMs:Long,val durationSeconds:Int,val label:String,val publishedLabel:String,val createdAtMs:Long,val startPhase:String) {
    fun metadata():JsonObject=buildJsonObject {
        put("timerId",timerId);put("startRequestId",startRequestId);put("packageName",packageName);put("packageUid",uid)
        put("packageFirstInstallAtMs",installedAtMs);put("durationSeconds",durationSeconds);put("label",label);put("publishedLabel",publishedLabel)
        put("createdAtMs",createdAtMs);put("startPhase",startPhase);put("automaticReplayAllowed",false)
    }
}

/** Separate durable identities; neither readback nor process recovery replays a timer action. */
internal class ClockJournal(context:Context,name:String="clock.db"):SQLiteOpenHelper(context,name,null,1) {
    init {setWriteAheadLoggingEnabled(true)}
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE timers(timer_id TEXT PRIMARY KEY,agent_id TEXT NOT NULL,start_request_id TEXT NOT NULL UNIQUE,
            package_name TEXT NOT NULL,package_uid INTEGER NOT NULL,installed_at INTEGER NOT NULL,duration_seconds INTEGER NOT NULL,
            label TEXT NOT NULL,published_label TEXT NOT NULL,created_at INTEGER NOT NULL,start_phase TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX timers_owner ON timers(agent_id,created_at,timer_id)")
        db.execSQL("""CREATE TABLE operations(request_id TEXT PRIMARY KEY,timer_id TEXT NOT NULL,kind TEXT NOT NULL,
            phase TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX operations_timer ON operations(timer_id,kind)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {error("Unsupported Clock journal migration; preserving timer identities")}
    fun recoverOrphans() {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.execSQL("UPDATE timers SET start_phase='NOT_DISPATCHED' WHERE start_phase='PREPARED'")
            db.execSQL("UPDATE timers SET start_phase='OUTCOME_UNKNOWN' WHERE start_phase='DISPATCHING'")
            db.execSQL("UPDATE operations SET phase='NOT_DISPATCHED' WHERE phase='PREPARED'")
            db.execSQL("UPDATE operations SET phase='OUTCOME_UNKNOWN' WHERE phase='DISPATCHING'")
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun prepare(timer:ManagedTimer) {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.insertOrThrow("timers",null,ContentValues().apply {
                put("timer_id",timer.timerId);put("agent_id",timer.agentId);put("start_request_id",timer.startRequestId)
                put("package_name",timer.packageName);put("package_uid",timer.uid);put("installed_at",timer.installedAtMs)
                put("duration_seconds",timer.durationSeconds);put("label",timer.label);put("published_label",timer.publishedLabel)
                put("created_at",timer.createdAtMs);put("start_phase","PREPARED")
            })
            insertOperation(timer.startRequestId,timer.timerId,"START",timer.createdAtMs)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    private fun insertOperation(requestId:String,timerId:String,kind:String,now:Long) {
        writableDatabase.insertOrThrow("operations",null,ContentValues().apply {
            put("request_id",requestId);put("timer_id",timerId);put("kind",kind);put("phase","PREPARED");put("created_at",now);put("updated_at",now)
        })
    }
    fun timer(id:String):ManagedTimer?=readableDatabase.rawQuery("SELECT * FROM timers WHERE timer_id=?",arrayOf(id)).use {c->
        if(!c.moveToFirst()) return@use null
        fun s(k:String)=c.getString(c.getColumnIndexOrThrow(k))
        fun n(k:String)=c.getLong(c.getColumnIndexOrThrow(k))
        ManagedTimer(s("timer_id"),s("agent_id"),s("start_request_id"),s("package_name"),n("package_uid").toInt(),n("installed_at"),
            n("duration_seconds").toInt(),s("label"),s("published_label"),n("created_at"),s("start_phase"))
    }
    fun owned(agent:String,id:String)=timer(id)?.takeIf {it.agentId==agent}
        ?: throw CapabilityException("CLOCK_TIMER_NOT_FOUND","No managed timer belongs to this agent and timerId",noSideEffects=true)
    fun byStartRequest(requestId:String):ManagedTimer?=readableDatabase.rawQuery("SELECT timer_id FROM timers WHERE start_request_id=?",arrayOf(requestId))
        .use {if(it.moveToFirst()) it.getString(0) else null}?.let(::timer)
    fun page(agent:String,limit:Int,beforeId:String?):List<ManagedTimer> {
        val before=beforeId?.let {owned(agent,it)}
        val args=mutableListOf(agent)
        val after=if(before==null) "" else " AND (created_at<? OR (created_at=? AND timer_id<?))".also {args+=listOf(before.createdAtMs.toString(),before.createdAtMs.toString(),before.timerId)}
        args+=limit.coerceIn(1,50).toString()
        return readableDatabase.rawQuery("SELECT timer_id FROM timers WHERE agent_id=?$after ORDER BY created_at DESC,timer_id DESC LIMIT ?",args.toTypedArray())
            .use {c->buildList {while(c.moveToNext()) add(c.getString(0))}}.mapNotNull(::timer)
    }
    fun prepareCancel(timer:ManagedTimer,requestId:String,now:Long) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val existing=db.rawQuery("SELECT request_id FROM operations WHERE timer_id=? AND kind='CANCEL' AND phase IN ('PREPARED','DISPATCHING','ACCEPTED','OUTCOME_UNKNOWN') LIMIT 1",arrayOf(timer.timerId))
                .use {if(it.moveToFirst()) it.getString(0) else null}
            if(existing!=null) throw CapabilityException("CLOCK_CANCEL_ALREADY_DISPATCHED","Cancellation already has a recorded dispatch; inspect timer status and original request $existing instead of replaying",noSideEffects=true)
            insertOperation(requestId,timer.timerId,"CANCEL",now)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun operation(requestId:String):JsonObject?=readableDatabase.rawQuery("SELECT timer_id,kind,phase,created_at,updated_at FROM operations WHERE request_id=?",arrayOf(requestId)).use {c->
        if(!c.moveToFirst()) null else buildJsonObject {
            put("requestId",requestId);put("timerId",c.getString(0));put("kind",c.getString(1));put("phase",c.getString(2));put("createdAtMs",c.getLong(3));put("updatedAtMs",c.getLong(4))
        }
    }
    fun operations(timerId:String):List<JsonObject> = readableDatabase.rawQuery("SELECT request_id FROM operations WHERE timer_id=? ORDER BY created_at DESC,request_id DESC LIMIT 10",arrayOf(timerId))
        .use {c->buildList {while(c.moveToNext()) add(c.getString(0))}}.mapNotNull(::operation)
    fun phase(requestId:String,phase:String) {
        require(phase in setOf("DISPATCHING","ACCEPTED","OUTCOME_UNKNOWN","NOT_DISPATCHED"))
        val db=writableDatabase;db.beginTransaction()
        try {
            check(db.update("operations",ContentValues().apply {put("phase",phase);put("updated_at",System.currentTimeMillis())},"request_id=?",arrayOf(requestId))==1)
            db.execSQL("UPDATE timers SET start_phase=? WHERE start_request_id=?",arrayOf(phase,requestId))
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
}
