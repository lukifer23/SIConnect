package dev.siconnect.app

import dev.siconnect.app.data.AppJson
import dev.siconnect.app.transport.PairingInvitation
import org.junit.Assert.*
import org.junit.Test

class PairingInvitationTest {
    @Test fun persistedInvitationsRequireConfirmationBeforeCopy() {
        val legacy=AppJson.decodeFromString(PairingInvitation.serializer(),
            """{"code":"fixture","agentId":"bot_fixture","expiresAt":1000,"sealed":"fixture"}""")
        assertFalse(legacy.readyToCopy(999))
        val saved=legacy.copy(published=true)
        val restored=AppJson.decodeFromString(PairingInvitation.serializer(),
            AppJson.encodeToString(PairingInvitation.serializer(),saved))
        assertTrue(restored.readyToCopy(999))
        assertFalse(restored.readyToCopy(1000))
    }
}
