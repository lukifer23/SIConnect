package dev.siconnect.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.siconnect.app.automation.*
import dev.siconnect.core.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class HealthAlertLedger(val states:Map<String,HealthIssueState> = emptyMap(),val requestId:String?=null,val outcome:String="NONE")

/** Existing notification capability owns dispatch. Claims persist before posting; no background daemon. */
internal class HealthAlertStore(private val db:SIConnectDb) {
    private val locks=Array(32) {Mutex()}
    suspend fun dispatch(agentId:String,requestId:String,args:JsonObject,clear:(suspend (String)->Boolean)?=null,post:suspend (String)->JsonObject):JsonObject {
        val policy=args.getValue("minimumBatteryPercent").jsonPrimitive.content
        val key=Settings.sha256Hex("health-v1:$agentId:$policy")
        return locks[(key.hashCode() and Int.MAX_VALUE)%locks.size].withLock {
            val now=System.currentTimeMillis();val w=db.writableDatabase
            val evaluated:HealthAlertEvaluation;val old:HealthAlertLedger
            w.beginTransaction()
            try {
                old=w.query("health_alert_state",arrayOf("json"),"agent_id=? AND policy_id=?",arrayOf(agentId,key),null,null,null,"1").use {
                    if(it.moveToFirst()) AppJson.decodeFromString(HealthAlertLedger.serializer(),it.getString(0)) else HealthAlertLedger()
                }
                evaluated=HealthAlertPolicy.evaluate(args,old.states,now)
                w.delete("health_alert_state","active=0 AND updated_at<?",arrayOf((now-30*86_400_000L).toString()))
                val exists=w.query("health_alert_state",arrayOf("policy_id"),"agent_id=? AND policy_id=?",arrayOf(agentId,key),null,null,null,"1").use {it.moveToFirst()}
                if(!exists) {
                    val total=android.database.DatabaseUtils.queryNumEntries(w,"health_alert_state")
                    val owned=android.database.DatabaseUtils.queryNumEntries(w,"health_alert_state","agent_id=?",arrayOf(agentId))
                    if(total>=512 || owned>=64) throw CapabilityException("ALERT_STATE_LIMIT","Health alert state limit reached; no notification posted",noSideEffects=true)
                }
                val claimed=old.copy(states=evaluated.states,requestId=if(evaluated.notify.isEmpty()) old.requestId else requestId,
                    outcome=if(evaluated.notify.isEmpty()) old.outcome else "CLAIMED")
                save(w,agentId,key,now,claimed)
                w.setTransactionSuccessful()
            } finally {w.endTransaction()}
            val result=try {if(evaluated.notify.isEmpty()) null else post("siconnect-health:$key")}
            catch(e:CapabilityException) {
                if(e.noSideEffects) {
                    // A confirmed pre-post denial must not consume an alert's cooldown.
                    val released=evaluated.states.mapValues {(id,state)->state.copy(
                        lastAlertAtMs=old.states[id]?.lastAlertAtMs ?: 0,
                        lastAlertSeverity=old.states[id]?.lastAlertSeverity ?: 0)}
                    save(w,agentId,key,now,HealthAlertLedger(released,requestId,"NOT_POSTED"))
                }
                throw e
            }
            if(result!=null) {
                check(result["postedObserved"]?.jsonPrimitive?.booleanOrNull==true) {"Health notification was not verified"}
                save(w,agentId,key,now,HealthAlertLedger(evaluated.states,requestId,"POSTED"))
            }
            val clearedNotification=if(evaluated.active.isEmpty()) clear?.invoke("siconnect-health:$key") else null
            buildJsonObject {
                result?.forEach {(k,v)->put(k,v)}
                put("postedObserved",result!=null)
                clearedNotification?.let {put("clearedNotificationObserved",it)}
                put("healthAlert",buildJsonObject {
                    put("reason",when {result!=null->"POSTED";evaluated.unavailable.size==HealthAlertPolicy.ids.size->"READINGS_UNAVAILABLE";evaluated.active.isEmpty()->"NO_ACTIVE_ISSUES";evaluated.suppressed.values.any {it=="COOLDOWN"}->"COOLDOWN";else->"UNCHANGED"})
                    put("activeIssues",JsonArray(evaluated.active.map(::JsonPrimitive)))
                    put("unavailableReadings",JsonArray(evaluated.unavailable.map(::JsonPrimitive)))
                    put("clearedIssues",JsonArray(evaluated.cleared.map(::JsonPrimitive)))
                    put("notificationReasons",JsonObject(evaluated.notify.mapValues {JsonPrimitive(it.value)}))
                    evaluated.nextEligibleAtMs?.let {put("nextEligibleAtMs",it)}
                    put("previousAlertOutcome",old.outcome)
                    old.requestId?.let {put("previousAlertRequestId",it)}
                })
            }
        }
    }
    private fun save(w:SQLiteDatabase,agent:String,key:String,now:Long,ledger:HealthAlertLedger) {
        w.insertOrThrowReplace(ContentValues().apply {
            put("agent_id",agent);put("policy_id",key);put("updated_at",now)
            put("active",if(ledger.states.values.any {it.severity>0}) 1 else 0)
            put("json",AppJson.encodeToString(HealthAlertLedger.serializer(),ledger))
        })
    }
    private fun SQLiteDatabase.insertOrThrowReplace(values:ContentValues) {
        check(insertWithOnConflict("health_alert_state",null,values,SQLiteDatabase.CONFLICT_REPLACE)!=-1L) {"Health alert state could not be saved; database preserved"}
    }
}
