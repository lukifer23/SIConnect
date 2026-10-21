package dev.siconnect.app.approvals

import android.app.*
import android.content.*
import android.net.Uri
import dev.siconnect.app.R
import dev.siconnect.app.graph
import dev.siconnect.core.*
import kotlinx.coroutines.launch

/** Pending requests remain visible in SQLite/UI even when notifications are disabled. */
class AndroidApprovals(private val c: Context) : ApprovalGate {
    override fun approvalRequested(record: RequestRecord) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val intent = Intent(c, ApprovalActivity::class.java).setData(Uri.parse("siconnect:approval/${record.requestId}"))
            .putExtra("requestId", record.requestId).putExtra("hash", record.argsHash)
        val open = PendingIntent.getActivity(c, record.requestId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val deny = PendingIntent.getBroadcast(c, record.requestId.hashCode(), Intent(c, ApprovalReceiver::class.java)
            .setData(Uri.parse("siconnect:deny/${record.requestId}"))
            .putExtra("requestId", record.requestId).putExtra("hash", record.argsHash), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val exact = record.assessment?.shellCommand ?: "${record.capabilityId} ${record.arguments}"
        nm.notify(record.requestId.hashCode(), Notification.Builder(c, "approvals").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Approval: ${record.agentId}").setContentText(exact)
            .setStyle(Notification.BigTextStyle().bigText("${record.assessment?.risk}: $exact\n${record.assessment?.sideEffects?.joinToString()}"))
            .setContentIntent(open).setAutoCancel(false).setOngoing(true).setTimeoutAfter(((record.approvalExpiresAtMs ?: record.expiresAtMs) - System.currentTimeMillis()).coerceAtLeast(1))
            .addAction(Notification.Action.Builder(null, "Review action", open).build())
            .addAction(Notification.Action.Builder(null, "Deny", deny).build()).build())
    }
    override fun approvalClosed(record: RequestRecord) { c.getSystemService(NotificationManager::class.java).cancel(record.requestId.hashCode()) }
}
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getStringExtra("requestId") ?: return
        val hash = intent.getStringExtra("hash") ?: return
        val pending = goAsync()
        c.graph.scope.launch { try { c.graph.ready.await(); c.graph.engine.resolveApproval(id, false, "local-owner", hash) } finally { pending.finish() } }
    }
}
class ApprovalActivity : dev.siconnect.app.ui.MainActivity()
