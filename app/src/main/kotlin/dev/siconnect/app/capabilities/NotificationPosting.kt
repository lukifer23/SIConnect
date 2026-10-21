package dev.siconnect.app.capabilities

import android.app.Notification
import android.app.NotificationManager
import android.content.ContentValues
import dev.siconnect.app.R
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.data.Settings
import dev.siconnect.core.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/** A claimed alert is never blindly reposted after an interrupted result. The engine owns its record. */
internal suspend fun postNotification(g:RuntimeGraph,a:JsonObject,ctx:ExecutionContext):JsonObject {
    val key=a["deduplicationKey"]?.jsonPrimitive?.content
    val hash=key?.let(Settings::sha256Hex)
    if(hash!=null) {
        val w=g.db.writableDatabase;w.beginTransaction()
        try {
            w.insertWithOnConflict("notification_dedup",null,ContentValues().apply {
                put("agent_id",ctx.agentId);put("key_hash",hash);put("request_id",ctx.requestId)
            },android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
            val original=w.query("notification_dedup",arrayOf("request_id"),"agent_id=? AND key_hash=?",arrayOf(ctx.agentId,hash),null,null,null).use {it.moveToFirst();it.getString(0)}
            if(original!=ctx.requestId) {
                val record=g.requests.get(original)
                if(record!=null && Canonical.encode(record.arguments)!=Canonical.encode(a))
                    throw CapabilityException("IDEMPOTENCY_CONFLICT","Alert key belongs to different arguments; inspect its original request",noSideEffects=true)
                if(record?.state!=RequestState.SUCCEEDED)
                    throw CapabilityException("ORIGINAL_OUTCOME_REQUIRED","Alert key already claimed by $original. Retrieve its outcome; do not repost",noSideEffects=true)
                val out=record.result?.output as? JsonObject ?: throw CapabilityException("ORIGINAL_OUTCOME_REQUIRED","Original alert result is unavailable",noSideEffects=true)
                w.setTransactionSuccessful()
                return buildJsonObject {out.forEach {(k,v)->put(k,v)};put("originalRequestId",original);put("deduplicated",true)}
            }
            w.setTransactionSuccessful()
        } finally {w.endTransaction()}
    }
    val nm=g.context.getSystemService(NotificationManager::class.java)
    if(!nm.areNotificationsEnabled()) throw CapabilityException("PERMISSION_REQUIRED","Notification access was revoked",noSideEffects=true)
    val tag=hash?.let {"siconnect:"+Settings.sha256Hex(ctx.agentId+":"+it)}
    val id=if(tag==null) ctx.requestId.hashCode() else 0
    val notification=Notification.Builder(g.context,"messages").setSmallIcon(R.drawable.ic_stat).setContentTitle(a.str("title"))
        .setContentText(a.str("text")).setAutoCancel(true).setOnlyAlertOnce(key!=null).build()
    if(tag==null) nm.notify(id,notification) else nm.notify(tag,id,notification)
    var observed=false
    repeat(20) {
        if(!observed) {observed=nm.activeNotifications.any {it.id==id && it.tag==tag};if(!observed) delay(20)}
    }
    if(!observed) throw CapabilityException("NOTIFICATION_NOT_OBSERVED","Android accepted the post but its active state could not be verified; inspect original request before retrying")
    return buildJsonObject {put("notificationId",id);tag?.let {put("notificationTag",it)};put("postedObserved",true);put("ownerSeen",false)}
}
