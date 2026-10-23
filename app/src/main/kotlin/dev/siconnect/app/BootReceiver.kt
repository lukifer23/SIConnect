package dev.siconnect.app

import android.content.*
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        // These system broadcasts are foreground-service start exemptions. Start promptly,
        // before async initialization consumes the receiver's execution window.
        if(i.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED) && c.graph.settings.gateway.value.enabled) {
            dev.siconnect.app.transport.ConnectionService.restoreOwnerEnabled(c.graph)
        }
        val pending=goAsync()
        c.graph.scope.launch { try { c.graph.ready.await(); c.graph.scheduler.restore(); if(i.action in setOf(Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED)) c.graph.scheduler.clockChanged(); c.graph.reminders.restore() } finally { pending.finish() } }
    }
}
