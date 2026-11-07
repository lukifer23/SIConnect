package dev.siconnect.app

import dev.siconnect.app.data.AgentRecord
import dev.siconnect.app.data.DEVELOPMENT_AGENT_ID
import dev.siconnect.app.data.developmentAgentWithCredential
import dev.siconnect.core.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DevelopmentAgentCredentialTest {
    private val oldHash="a".repeat(64)
    private val newHash="b".repeat(64)
    private fun custom()=AgentGrant(DEVELOPMENT_AGENT_ID,"Owner-selected development identity",
        scopes=listOf("device.status","headless.*"),denied=listOf("apps.uninstall"),
        autoApproveUpTo=RiskClass.READ_ONLY,shellMode=ShellMode.STANDING_COMMANDS,shellStandingCommands=listOf("id"),
        standing=listOf(StandingGrant("headless.open",constraints=mapOf("packageName" to JsonPrimitive("com.google.android.apps.messaging")),
            maxRisk=RiskClass.HIGH_IMPACT,backend=BackendKind.ADB_SERVICE)),expiresAtMs=123_000,allowSharedScreen=false)

    @Test fun rotatingExplicitFullControlKeepsItsIndefiniteGrantAndExactCustomSnapshot() {
        val prior=custom()
        val full=prior.copy(scopes=listOf("*"),denied=emptyList(),autoApproveUpTo=RiskClass.CRITICAL,
            shellMode=ShellMode.BROAD,allowSharedScreen=true,expiresAtMs=null,fullControl=true)
        val saved=AgentRecord(full,oldHash,100,lastSeenAtMs=200,tokenHint="old hint",fullAccessPreviousGrant=prior)
        val rotated=developmentAgentWithCredential(saved,newHash,"new hint",nowMs=900_000)
        assertEquals(saved.copy(tokenSha256=newHash,tokenHint="new hint"),rotated)
        assertTrue(rotated.grant.fullControl)
        assertNull(rotated.grant.expiresAtMs)
        assertEquals(prior,rotated.fullAccessPreviousGrant)
        assertEquals(100L,rotated.createdAtMs)
        assertEquals(200L,rotated.lastSeenAtMs)
    }

    @Test fun rotatingAnExplicitlyRevokedOrExpiredCustomIdentityDoesNotReactivateIt() {
        val disabled=AgentRecord(custom().copy(revoked=true,expiresAtMs=1),oldHash,100,lastSeenAtMs=200,tokenHint="old hint")
        val rotated=developmentAgentWithCredential(disabled,newHash,"new hint",nowMs=900_000)
        assertEquals(disabled.copy(tokenSha256=newHash,tokenHint="new hint"),rotated)
        assertTrue(rotated.grant.revoked)
        assertEquals(1L,rotated.grant.expiresAtMs)
        assertFalse(rotated.grant.fullControl)
        assertNull(rotated.fullAccessPreviousGrant)
    }

    @Test fun firstCreationKeepsTheExistingScopedApprovalRequiredDefault() {
        val created=developmentAgentWithCredential(null,newHash,"new hint",nowMs=500)
        assertEquals(DEVELOPMENT_AGENT_ID,created.grant.agentId)
        assertEquals(500L,created.createdAtMs)
        assertEquals(newHash,created.tokenSha256)
        assertEquals("new hint",created.tokenHint)
        assertNull(created.lastSeenAtMs)
        assertNull(created.fullAccessPreviousGrant)
        assertFalse(created.grant.fullControl)
        assertFalse(created.grant.revoked)
        assertEquals(ShellMode.APPROVAL_REQUIRED,created.grant.shellMode)
        assertEquals(RiskClass.LOW_IMPACT,created.grant.autoApproveUpTo)
        assertTrue("shell.*" in created.grant.scopes)
        assertFalse("*" in created.grant.scopes)
    }

    @Test fun rotationRefusesAnExistingRecordBoundToAnotherAgent() {
        val wrong=AgentRecord(custom().copy(agentId="another-agent"),oldHash,100,tokenHint="old hint")
        try {developmentAgentWithCredential(wrong,newHash,"new hint",nowMs=500);fail("Mismatched development identity accepted")}
        catch(_:IllegalArgumentException) { }
    }
}
