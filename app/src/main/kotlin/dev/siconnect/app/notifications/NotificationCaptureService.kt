package dev.siconnect.app.notifications

import android.app.Notification
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.siconnect.app.graph
import dev.siconnect.core.CapabilityException
import dev.siconnect.core.Canonical
import kotlinx.coroutines.launch
import java.util.UUID

/** Live metadata/action references only. Notification bodies and RemoteInput choices are not retained. */
class NotificationCaptureService : NotificationListenerService() {
    private data class Version(val token:String,val fingerprint:String)
    private val versions=object:LinkedHashMap<String,Version>(32,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Version>?)=size>512
    }
    private val versionLock=Any()
    override fun onListenerConnected() { synchronized(versionLock) {versions.clear()};instance=this;connectionState.value=true;catalogChanged() }
    override fun onListenerDisconnected() { if(instance===this) {instance=null;connectionState.value=false};synchronized(versionLock) {versions.clear()};catalogChanged() }
    override fun onDestroy() { if(instance===this) {instance=null;connectionState.value=false};synchronized(versionLock) {versions.clear()};catalogChanged();super.onDestroy() }
    override fun onNotificationPosted(sbn:StatusBarNotification) {
        val fingerprint=runCatching {fingerprint(sbn)}.getOrNull()
        synchronized(versionLock) {
            if(fingerprint==null) versions.remove(sbn.key)
            else versions[sbn.key]=Version(UUID.randomUUID().toString(),fingerprint)
        }
    }
    override fun onNotificationRemoved(sbn:StatusBarNotification) {synchronized(versionLock) {versions.remove(sbn.key)}}
    fun metadata():List<StatusBarNotification> = activeNotifications?.toList() ?: emptyList()

    /** Fetch exactly the live key. References intentionally become invalid after process/listener restart. */
    fun actionSnapshot(key:String,expectedPackage:String):NotificationActionSnapshot = synchronized(versionLock) {
        if(instance!==this) reject("PERMISSION_REQUIRED","Notification listener is disconnected")
        val sbn=try {getActiveNotifications(arrayOf(key))?.singleOrNull {it.key==key}}
            catch(_:SecurityException) {reject("PERMISSION_REQUIRED","Notification listener access was revoked")}
            ?: reject("NOTIFICATION_GONE","Notification is no longer active; inspect current notifications")
        if(sbn.packageName!=expectedPackage) reject("NOTIFICATION_IDENTITY_MISMATCH","Notification belongs to another package")
        if(sbn.user!=Process.myUserHandle()) reject("UNSUPPORTED_USER","Notification actions are restricted to SIConnect's Android user; other profiles are isolated")
        val fp=runCatching {fingerprint(sbn)}.getOrElse {reject("ACTION_METADATA_UNAVAILABLE","Notification action metadata could not be decoded")}
        val version=versions[key]?.takeIf {it.fingerprint==fp}
            ?: Version(UUID.randomUUID().toString(),fp).also {versions[key]=it}
        val actions=sbn.notification.actions.orEmpty().filterNotNull()
        NotificationActionSnapshot(sbn.key,sbn.packageName,sbn.uid,sbn.postTime,version.token,
            actions.take(MAX_ACTIONS).mapIndexed {index,action ->
                LiveNotificationAction(index,"$index-${Canonical.sha256Hex("${version.token}:$index:${actionFingerprint(action)}").take(24)}",action)
            },actions.size>MAX_ACTIONS)
    }

    fun resolveAction(key:String,expectedPackage:String,version:String,actionId:String):ResolvedNotificationAction {
        val snapshot=actionSnapshot(key,expectedPackage)
        if(snapshot.version!=version) reject("NOTIFICATION_CHANGED","Notification changed; inspect its new actions before submitting another operation")
        val selected=snapshot.actions.singleOrNull {it.id==actionId}
            ?: reject("ACTION_CHANGED","Action reference no longer identifies a live supported action")
        return ResolvedNotificationAction(snapshot,selected)
    }
    private fun catalogChanged() {runCatching { val g=graph;g.scope.launch {g.ready.await();g.gateway.refreshCatalog()} }}

    /** Hash action identity, never expose PendingIntents, message extras, or their toString output. */
    private fun fingerprint(sbn:StatusBarNotification)=Canonical.sha256Hex(buildString {
        append(sbn.key);append('|');append(sbn.packageName);append('|');append(sbn.uid);append('|');append(sbn.postTime)
        append('|');append(sbn.notification.`when`)
        sbn.notification.actions.orEmpty().filterNotNull().take(MAX_ACTIONS).forEach {append('|');append(actionFingerprint(it))}
    })
    private fun actionFingerprint(a:Notification.Action)=Canonical.sha256Hex(buildString {
        append(a.actionIntent?.hashCode());append('|');append(a.actionIntent?.creatorUid);append('|');append(a.actionIntent?.creatorPackage)
        append('|');append(a.semanticAction);append('|');append(a.isAuthenticationRequired);append('|');append(a.isContextual)
        append('|');append(a.title?.take(256))
        a.remoteInputs.orEmpty().filterNotNull().take(MAX_INPUTS).forEach {append('|');append(it.resultKey.take(128));append(':');append(it.allowFreeFormInput);append(':');append(it.isDataOnly)}
    })
    private fun reject(code:String,message:String):Nothing=throw CapabilityException(code,message,noSideEffects=true)
    companion object {
        const val MAX_ACTIONS=16;const val MAX_INPUTS=8
        val connectionState=kotlinx.coroutines.flow.MutableStateFlow(false)
        @Volatile var instance:NotificationCaptureService?=null;private set
    }
}

data class LiveNotificationAction(val index:Int,val id:String,val action:Notification.Action)
data class NotificationActionSnapshot(val key:String,val packageName:String,val uid:Int,val postTimeMs:Long,
                                      val version:String,val actions:List<LiveNotificationAction>,val actionsTruncated:Boolean)
data class ResolvedNotificationAction(val snapshot:NotificationActionSnapshot,val selected:LiveNotificationAction)
