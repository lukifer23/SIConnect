package dev.siconnect.core

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class AgentDiscoveryTest {
    private val timer = CapabilityDescriptor("clock.timer_start", title = "Start timer", description = "Timer",
        inputSchema = JsonObject(emptyMap()), backend = BackendKind.NATIVE, risk = RiskClass.MODERATE_IMPACT,
        interaction = InteractionMode.MAY_OPEN_SCREEN, hasBackgroundRoute = true)
    private val grant = AgentGrant("agent", "Agent", scopes = listOf("clock.*"), allowSharedScreen = false)

    @Test fun advertisedScopeHonorsDenialsAndOnlyOffersAvailableInteractionRoutes() {
        assertTrue(grant.discovers(timer))
        assertFalse(grant.copy(scopes = listOf("device.*")).discovers(timer))
        assertFalse(grant.copy(denied = listOf(timer.id)).discovers(timer))
        assertFalse(grant.copy(revoked = true).discovers(timer))
        assertFalse(grant.discovers(timer.copy(ownerOnly = true)))
        assertFalse(grant.discovers(timer.copy(hasBackgroundRoute = false)))
        assertFalse(grant.discovers(timer.copy(interaction = InteractionMode.SCREEN)))
        assertTrue(grant.copy(allowSharedScreen = true).discovers(timer.copy(interaction = InteractionMode.SCREEN)))
    }
}
