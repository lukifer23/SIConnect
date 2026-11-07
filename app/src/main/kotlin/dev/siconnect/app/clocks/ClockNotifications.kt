package dev.siconnect.app.clocks

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import dev.siconnect.app.notifications.*
import dev.siconnect.core.CapabilityException
import kotlinx.serialization.json.*

internal data class OwnedTimerNotification(val notification:StatusBarNotification,val actions:NotificationActionSnapshot,
    val cancel:LiveNotificationAction?,val cancelUnavailable:String?,val countdownPublished:Boolean,val remainingSeconds:Long?) {
    fun observation():JsonObject=buildJsonObject {
        put("notificationObserved",true);put("notificationKey",notification.key);put("notificationPostTimeMs",notification.postTime)
        put("notificationVersion",actions.version);put("countdownPublished",countdownPublished)
        put("remainingSecondsObserved",remainingSeconds?.let(::JsonPrimitive) ?: JsonNull)
        put("timerStartVerified",countdownPublished && remainingSeconds!=null && remainingSeconds>0)
        put("cancellationSupported",cancel!=null);cancelUnavailable?.let {put("cancellationUnavailableCode",it)}
        put("pauseSupported",false);put("resumeSupported",false)
        put("observation","Exact owned label in current-user Clock notification; countdown only when native chronometer metadata is published")
    }
}

internal class ClockNotifications(private val context:Context) {
    fun requireNoExistingClockWork(packageName:String) {
        if(NotificationListenerAccess.requireConnected(context).metadata().any {it.packageName==packageName && it.user==Process.myUserHandle()})
            throw CapabilityException("CAPABILITY_BLOCKED_PREEXISTING_CLOCK_WORK","Clock already has current-user notifications. Existing timers, alarms or alerts were left untouched; managed start requires a quiet Clock app",noSideEffects=true)
    }
    private fun labelFields(sbn:StatusBarNotification):List<String> {
        val extras=sbn.notification.extras
        val fields=listOf(Notification.EXTRA_TITLE,Notification.EXTRA_TEXT,Notification.EXTRA_SUB_TEXT,Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,Notification.EXTRA_INFO_TEXT).mapNotNull {extras.getCharSequence(it)?.take(4000)?.toString()}
        return fields+extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().take(32).map {it.take(4000).toString()}
    }
    fun observe(timer:ManagedTimer):OwnedTimerNotification? {
        val listener=NotificationListenerAccess.requireConnected(context)
        val pkg=try {context.packageManager.getPackageInfo(timer.packageName,0)} catch(_:android.content.pm.PackageManager.NameNotFoundException) {null}
        if(pkg?.applicationInfo?.uid!=timer.uid || pkg.firstInstallTime!=timer.installedAtMs)
            throw CapabilityException("CLOCK_INSTALL_CHANGED","Clock installation no longer matches this managed timer; no control was dispatched",noSideEffects=true)
        val children=listener.metadata().filter {sbn->sbn.packageName==timer.packageName && sbn.uid==timer.uid && sbn.user==Process.myUserHandle() &&
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY==0}
        val matches=children.filter {sbn->
            ClockContract.matchesLabel(timer.packageName,timer.publishedLabel,labelFields(sbn),
                sbn.notification.extras.getString("android.ongoingActivityNoti.secondaryInfo")?.take(4000))
        }
        if(matches.isEmpty()) return null
        if(matches.size!=1) throw CapabilityException("CLOCK_TIMER_AMBIGUOUS","Multiple Clock notifications contain this owned label; no timer control was dispatched",noSideEffects=true)
        val sbn=matches.single();val snapshot=listener.actionSnapshot(sbn.key,timer.packageName)
        val safe=snapshot.actions.filter {selected->
            val a=selected.action;val pending=a.actionIntent
            ClockContract.cancelMeaning(timer.packageName,a.semanticAction,a.title?.toString().orEmpty()) &&
                !a.isAuthenticationRequired && a.remoteInputs.orEmpty().isEmpty() && pending!=null &&
                pending.creatorPackage==timer.packageName && pending.creatorUid==timer.uid && !pending.isActivity &&
                (pending.isBroadcast || pending.isService || pending.isForegroundService)
        }
        val cancel=safe.singleOrNull().takeIf {children.size==1}
        val countdown=sbn.notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER,false) &&
            sbn.notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN,false)
        val seconds=if(countdown && sbn.notification.`when`>0) ((sbn.notification.`when`-System.currentTimeMillis())/1000).coerceAtLeast(0) else null
        return OwnedTimerNotification(sbn,snapshot,cancel,when {children.size!=1->"CLOCK_OTHER_WORK_PRESENT";safe.size>1->"CLOCK_CANCEL_AMBIGUOUS";cancel==null->"CLOCK_CANCEL_NOT_PUBLISHED";else->null},countdown,seconds)
    }
    fun resolveCancel(timer:ManagedTimer,observed:OwnedTimerNotification):ResolvedNotificationAction {
        val action=observed.cancel ?: throw CapabilityException(observed.cancelUnavailable ?: "CLOCK_CANCEL_NOT_PUBLISHED","Clock did not publish one safe cancellation action for this exact managed timer",noSideEffects=true)
        val current=observe(timer) ?: throw CapabilityException("CLOCK_TIMER_NOT_OBSERVED","Owned Clock notification is no longer visible; no cancellation was dispatched",noSideEffects=true)
        if(current.notification.key!=observed.notification.key || current.actions.version!=observed.actions.version || current.cancel?.id!=action.id)
            throw CapabilityException("NOTIFICATION_CHANGED","Clock notification changed; inspect the timer again before cancelling",noSideEffects=true)
        return NotificationListenerAccess.requireConnected(context).resolveAction(observed.notification.key,timer.packageName,observed.actions.version,action.id)
    }
}
