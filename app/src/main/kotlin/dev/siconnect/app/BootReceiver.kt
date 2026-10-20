package dev.siconnect.app

import android.content.*
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val pending=goAsync()
        c.graph.scope.launch { try { c.graph.ready.await(); c.graph.scheduler.restore(); c.graph.reminders.restore() } finally { pending.finish() } }
    }
}
