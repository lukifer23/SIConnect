package dev.siconnect.core

import kotlinx.serialization.json.*
import kotlin.test.*

/** Pure authorization rules; physical execution is covered against real apps on the device. */
class HeadlessPolicyTest {
    private val policy=PolicyEngine()
    private val owner=AgentGrant("local-owner","Owner",scopes=listOf("*"),autoApproveUpTo=RiskClass.LOW_IMPACT)
    private fun descriptor(id:String,risk:RiskClass=RiskClass.HIGH_IMPACT,backend:BackendKind=BackendKind.ADB_SERVICE)=
        CapabilityDescriptor(id,title=id,description="Private display operation",inputSchema=objectSchema(),backend=backend,risk=risk,interaction=InteractionMode.ISOLATED_SCREEN)
    @Test fun localOwnerCanCloseResourcesAfterDisablingPrivilegeWithoutReenablingActions() {
        val disabled=DeviceControls(privilegedEnabled=false)
        val cleanup=descriptor("headless.stop_all",RiskClass.LOW_IMPACT)
        assertEquals(PolicyOutcome.ALLOW,policy.evaluate(owner,cleanup,buildJsonObject {},RiskAssessment(RiskClass.LOW_IMPACT,"Close"),Availability.IMPLEMENTED,disabled,Origin.LOCAL_OWNER,1).outcome)
        assertEquals(PolicyOutcome.UNAVAILABLE,policy.evaluate(owner,cleanup,buildJsonObject {},RiskAssessment(RiskClass.LOW_IMPACT,"Close"),Availability.IMPLEMENTED,disabled,Origin.REMOTE,1).outcome)
        assertEquals(PolicyOutcome.UNAVAILABLE,policy.evaluate(owner,descriptor("headless.click"),buildJsonObject {},RiskAssessment(RiskClass.HIGH_IMPACT,"Click"),Availability.IMPLEMENTED,disabled,Origin.LOCAL_OWNER,1).outcome)
    }
    @Test fun privateStandingPermissionBindsPackageAndSelectedBackend() {
        val args=buildJsonObject {put("packageName","org.example.app")}
        val grant=AgentGrant("agent","Agent",scopes=listOf("headless.*"),standing=listOf(StandingGrant("headless.click",args,maxRisk=RiskClass.HIGH_IMPACT,backend=BackendKind.ADB_SERVICE)))
        fun outcome(a:JsonObject,backend:BackendKind=BackendKind.ADB_SERVICE)=policy.evaluate(grant,descriptor("headless.click",backend=backend),a,RiskAssessment(RiskClass.HIGH_IMPACT,"Click"),Availability.IMPLEMENTED,DeviceControls(),Origin.REMOTE,1).outcome
        assertEquals(PolicyOutcome.ALLOW,outcome(args))
        assertEquals(PolicyOutcome.REQUIRE_APPROVAL,outcome(buildJsonObject {put("packageName","org.other.app")}))
        assertEquals(PolicyOutcome.REQUIRE_APPROVAL,outcome(args,BackendKind.SHIZUKU))
    }
    @Test fun ownerOnlyRequiresBothTrustedOriginAndOwnerPrincipalDespiteBroadAuthority() {
        val cap=descriptor("system.restore_bridge",RiskClass.LOW_IMPACT).copy(ownerOnly=true,interaction=InteractionMode.BACKGROUND)
        val broad=owner.copy(autoApproveUpTo=RiskClass.HIGH_IMPACT,shellMode=ShellMode.BROAD,
            standing=listOf(StandingGrant(cap.id,maxRisk=RiskClass.HIGH_IMPACT,backend=BackendKind.ADB_SERVICE)))
        fun result(agent:AgentGrant,origin:Origin)=policy.evaluate(agent,cap,buildJsonObject {},RiskAssessment(RiskClass.LOW_IMPACT,"Restore"),Availability.IMPLEMENTED,DeviceControls(),origin,1)
        assertEquals(PolicyOutcome.ALLOW,result(broad,Origin.LOCAL_OWNER).outcome)
        for(origin in Origin.entries.filter {it!=Origin.LOCAL_OWNER}) assertEquals("owner_only",result(broad,origin).rule)
        assertEquals("owner_only",result(broad.copy(agentId="remote"),Origin.LOCAL_OWNER).rule)
    }
    @Test fun ownerOnlyDoesNotBypassScopeRevocationOrDisabledPrivilege() {
        val cap=descriptor("system.restore_bridge",RiskClass.LOW_IMPACT).copy(ownerOnly=true,interaction=InteractionMode.BACKGROUND)
        fun result(agent:AgentGrant,controls:DeviceControls=DeviceControls())=policy.evaluate(agent,cap,buildJsonObject {},RiskAssessment(RiskClass.LOW_IMPACT,"Restore"),Availability.IMPLEMENTED,controls,Origin.LOCAL_OWNER,1)
        assertEquals("agent_scope",result(owner.copy(scopes=listOf("device.*"))).rule)
        assertEquals("agent_revoked",result(owner.copy(revoked=true)).rule)
        assertEquals(PolicyOutcome.UNAVAILABLE,result(owner,DeviceControls(privilegedEnabled=false)).outcome)
        assertEquals(PolicyOutcome.ALLOW,policy.evaluate(owner.copy(agentId="remote"),cap.copy(ownerOnly=false),buildJsonObject {},RiskAssessment(RiskClass.LOW_IMPACT,"Restore"),Availability.IMPLEMENTED,DeviceControls(),Origin.REMOTE,1).outcome)
    }
    @Test fun noSharedScreenChoiceFailsClosedEvenWithBroadScopeAndShellAuthority() {
        val grant=owner.copy(allowSharedScreen=false,shellMode=ShellMode.BROAD,autoApproveUpTo=RiskClass.HIGH_IMPACT)
        for(mode in InteractionMode.entries) {
            val d=descriptor("headless.policy").copy(interaction=mode)
            val result=policy.evaluate(grant,d,buildJsonObject {},RiskAssessment(RiskClass.HIGH_IMPACT,"Operation"),Availability.IMPLEMENTED,DeviceControls(),Origin.REMOTE,1)
            assertEquals(if(mode in setOf(InteractionMode.BACKGROUND,InteractionMode.ISOLATED_SCREEN)) PolicyOutcome.ALLOW else PolicyOutcome.DENY,result.outcome)
        }
        val shell=descriptor("shell.execute").copy(interaction=InteractionMode.MAY_OPEN_SCREEN)
        assertEquals("shared_screen_disabled",policy.evaluate(grant,shell,buildJsonObject {},RiskAssessment(RiskClass.HIGH_IMPACT,"Shell",shellCommand="id"),Availability.IMPLEMENTED,DeviceControls(),Origin.REMOTE,1).rule)
    }
}
