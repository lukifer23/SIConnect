package dev.siconnect.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticServiceStateTest {
    @Test fun configurationConnectionAndLockAreIndependent() {
        assertEquals("DISABLED_BY_OWNER",SemanticServiceState.classify(false,false,false,false))
        assertEquals("ENABLED_BUT_DISCONNECTED",SemanticServiceState.classify(true,false,true,false))
        assertEquals("CONNECTED_BUT_SCREEN_LOCKED",SemanticServiceState.classify(true,true,true,false))
        assertEquals("CONNECTED_WITH_NO_FOREGROUND_WINDOW",SemanticServiceState.classify(true,true,false,false))
        assertEquals("CONNECTED_READY",SemanticServiceState.classify(true,true,false,true))
    }
}
