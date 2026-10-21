package dev.siconnect.app

import android.content.*
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        // These system broadcasts are foreground-service start exemptions. Start promptly,
        // before async initialization consumes the receiver's execution window.
        if(i.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED) && c.graph.settings.gateway.value.enabled) {
            try {c.startForegroundService(Intent(c,dev.siconnect.app.transport.ConnectionService::class.java))}
            catch(e:RuntimeException) {
                c.graph.scope.launch {c.graph.events.emit(dev.siconnect.core.Event(System.currentTimeMillis(),eventType="gateway.recovery.blocked",errorCode=e.javaClass.simpleName,detail="Android prevented connection service recovery; open SIConnect"))}
            }
        }
        val pending=goAsync()
        c.graph.scope.launch { try { c.graph.ready.await(); c.graph.scheduler.restore(); c.graph.reminders.restore() } finally { pending.finish() } }
    }
}
