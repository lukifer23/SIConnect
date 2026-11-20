package dev.siconnect.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import dev.siconnect.app.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test

class PairingDisclosureDeviceTest {
    @Test fun stoppedOrRecreatedActivityRejectsEarlierDisclosure() {
        // No invitation, clipboard, credential or approval needed to exercise stale callbacks.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var beforeStop: Any
            scenario.onActivity { beforeStop = it.pairingDisclosure.ticket }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            lateinit var beforeRecreation: Any
            scenario.onActivity {
                val disclosure = it.pairingDisclosure
                assertFalse(disclosure.current(beforeStop))
                disclosure.show(beforeStop)
                assertFalse(disclosure.visible)
                beforeRecreation = disclosure.ticket
            }
            scenario.recreate()
            scenario.onActivity {
                it.pairingDisclosure.show(beforeRecreation)
                assertFalse(it.pairingDisclosure.visible)
            }
        }
    }
}
