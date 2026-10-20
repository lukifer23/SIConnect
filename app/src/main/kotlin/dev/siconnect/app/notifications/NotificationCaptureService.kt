package dev.siconnect.app.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Live metadata only. Message bodies are never retained in the database. */
class NotificationCaptureService : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }
    override fun onDestroy() { instance = null; super.onDestroy() }
    fun metadata(): List<StatusBarNotification> = activeNotifications?.toList() ?: emptyList()
    companion object { @Volatile var instance: NotificationCaptureService? = null; private set }
}
