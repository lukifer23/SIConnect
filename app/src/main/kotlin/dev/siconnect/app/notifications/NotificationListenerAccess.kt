package dev.siconnect.app.notifications

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import dev.siconnect.core.Availability
import dev.siconnect.core.CapabilityException

/** Android authorization and a currently bound listener are distinct observations. */
internal object NotificationListenerAccess {
    fun granted(context:Context)=context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(context,NotificationCaptureService::class.java))

    fun classify(granted:Boolean,connected:Boolean)=when {
        !granted->Availability.PERMISSION_REQUIRED
        !connected->Availability.TEMPORARILY_UNAVAILABLE
        else->Availability.IMPLEMENTED
    }
    fun availability(context:Context)=classify(granted(context),NotificationCaptureService.instance!=null)
    fun detail(context:Context)=when(availability(context)) {
        Availability.PERMISSION_REQUIRED->"Enable SIConnect notification-listener access in Android Settings."
        Availability.TEMPORARILY_UNAVAILABLE->"Notification access is granted, but Android has disconnected the listener. Use Reconnect notification listener in SIConnect Settings."
        else->"Notification listener connected. Only live current-user notification metadata and published actions are supported."
    }
    fun requireConnected(context:Context):NotificationCaptureService {
        if(!granted(context)) throw CapabilityException("PERMISSION_REQUIRED",detail(context),noSideEffects=true)
        return NotificationCaptureService.instance
            ?: throw CapabilityException("UNAVAILABLE",detail(context),noSideEffects=true)
    }
}
