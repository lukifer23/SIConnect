package dev.siconnect.app

import dev.siconnect.app.data.withShellPermission
import dev.siconnect.core.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ShellPermissionTest {
    @Test fun askingBeforeShellRemovesShellExceptionsAndPreservesUnrelatedGrants() {
        val app=StandingGrant("ui.click",constraints=mapOf("packageName" to JsonPrimitive("com.example.app")),backend=BackendKind.ACCESSIBILITY)
        val native=StandingGrant("calendar.update_event",actionHash="calendar-action",backend=BackendKind.NATIVE)
        val grant=AgentGrant("agent","Agent",scopes=listOf("shell.*","ui.*","calendar.*"),
            shellMode=ShellMode.BROAD,shellStandingCommands=listOf("id"),
            standing=listOf(app,StandingGrant("shell.execute",actionHash="shell-action",backend=BackendKind.ADB_SERVICE),native))
        val reduced=grant.withShellPermission(false)
        assertEquals(ShellMode.APPROVAL_REQUIRED,reduced.shellMode)
        assertTrue(reduced.shellStandingCommands.isEmpty())
        assertEquals(listOf(app,native),reduced.standing)
        assertEquals(grant.scopes,reduced.scopes)
        val shell=CapabilityDescriptor("shell.execute",title="Shell",description="test",inputSchema=objectSchema(),backend=BackendKind.ADB_SERVICE,risk=RiskClass.HIGH_IMPACT)
        assertEquals(PolicyOutcome.REQUIRE_APPROVAL,PolicyEngine().evaluate(reduced,shell,objectSchema(),
            RiskAssessment(RiskClass.HIGH_IMPACT,"Shell",shellCommand="id"),Availability.IMPLEMENTED,DeviceControls(),Origin.REMOTE,0).outcome)
    }

    @Test fun broadShellDoesNotChangeOtherAuthorityOrDuplicateResultScope() {
        val original=AgentGrant("agent","Agent",scopes=listOf("shell.*","requests.*"),denied=listOf("calendar.*"),
            standing=listOf(StandingGrant("ui.click")),expiresAtMs=1234)
        assertEquals(original.copy(shellMode=ShellMode.BROAD),original.withShellPermission(true).withShellPermission(true))
        assertEquals(listOf("shell.*","requests.*"),original.copy(scopes=listOf("shell.*")).withShellPermission(true).scopes)
    }
}
