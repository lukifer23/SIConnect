package dev.siconnect.app

import dev.siconnect.app.notifications.NotificationListenerAccess
import dev.siconnect.core.Availability
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationListenerAccessTest {
    @Test fun grantedButDisconnectedDoesNotAskForPermissionAgain() {
        assertEquals(Availability.PERMISSION_REQUIRED,NotificationListenerAccess.classify(false,false))
        assertEquals(Availability.PERMISSION_REQUIRED,NotificationListenerAccess.classify(false,true))
        assertEquals(Availability.TEMPORARILY_UNAVAILABLE,NotificationListenerAccess.classify(true,false))
        assertEquals(Availability.IMPLEMENTED,NotificationListenerAccess.classify(true,true))
    }
}
