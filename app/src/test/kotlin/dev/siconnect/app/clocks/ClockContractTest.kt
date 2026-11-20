package dev.siconnect.app.clocks

import dev.siconnect.core.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ClockContractTest {
    private val timer="01234567-89ab-cdef-0123-456789abcdef"
    @Test fun ownershipRequiresExactSamsungPrefixAndDelimiterRatherThanSubstring() {
        val label=ClockContract.publishedLabel("Disposable",timer)
        assertTrue(ClockContract.matchesLabel(ClockContract.SAMSUNG_CLOCK,label,emptyList(),"$label / 12:42 AM"))
        assertFalse(ClockContract.matchesLabel(ClockContract.SAMSUNG_CLOCK,label,listOf(label),null))
        assertFalse(ClockContract.matchesLabel(ClockContract.SAMSUNG_CLOCK,label,emptyList(),"Other $label / 12:42 AM"))
        assertFalse(ClockContract.matchesLabel(ClockContract.SAMSUNG_CLOCK,label,emptyList(),"$label-other / 12:42 AM"))
        assertFalse(ClockContract.matchesLabel(ClockContract.SAMSUNG_CLOCK,label,emptyList(),"$label / "))
        assertTrue(ClockContract.matchesLabel("other.clock",label,listOf(label),null))
        assertFalse(ClockContract.matchesLabel("other.clock",label,listOf("Other $label"),null))
    }
    @Test fun timerLabelsPreserveLiteralUserTextAndRemainBoundToOneTimer() {
        val label=ClockContract.publishedLabel("  Tea & coffee (8 min)  ",timer)
        assertEquals("Tea & coffee (8 min) · SIConnect:$timer",label)
        assertEquals("SIConnect:$timer",ClockContract.publishedLabel("",timer))
        ClockContract.validateTimer(ClockContract.SAMSUNG_CLOCK,480,label)
    }
    @Test fun cancelNeverInfersPauseDeleteOrUnknownAppTextAsStop() {
        assertTrue(ClockContract.cancelMeaning(ClockContract.SAMSUNG_CLOCK,0,"Cancel"))
        assertTrue(ClockContract.cancelMeaning("other.clock",15,""))
        assertFalse(ClockContract.cancelMeaning(ClockContract.SAMSUNG_CLOCK,0,"Pause"))
        assertFalse(ClockContract.cancelMeaning(ClockContract.SAMSUNG_CLOCK,0,"Delete all"))
        assertFalse(ClockContract.cancelMeaning("other.clock",0,"Cancel"))
    }
    @Test fun invalidDurationPackageAndMultilineLabelAreRejectedBeforeIntent() {
        for(seconds in listOf(0,86401)) try {ClockContract.validateTimer(ClockContract.SAMSUNG_CLOCK,seconds,"label");fail("Invalid duration admitted")}
            catch(_:IllegalArgumentException) { }
        try {ClockContract.validateTimer("clock;touch file",60,"label");fail("Invalid package admitted")}
        catch(_:IllegalArgumentException) { }
        try {ClockContract.publishedLabel("line\nline",timer);fail("Invalid label admitted")}
        catch(_:IllegalArgumentException) { }
    }
    @Test fun nativeTimerRequiresScopeAndSharedScreenWithPrivilegedControlOff() {
        val descriptor=CapabilityDescriptor("clock.timer_start",title="Timer",description="",inputSchema=objectSchema(),
            backend=BackendKind.NATIVE,risk=RiskClass.MODERATE_IMPACT,interaction=InteractionMode.MAY_OPEN_SCREEN)
        val grant=AgentGrant("agent","agent",scopes=listOf("clock.*"),autoApproveUpTo=RiskClass.MODERATE_IMPACT)
        fun decision(agent:AgentGrant=grant,controls:DeviceControls=DeviceControls(),foregroundReview:Boolean=false,background:Boolean=false)=PolicyEngine().evaluate(agent,descriptor,JsonObject(emptyMap()),
            RiskAssessment(descriptor.risk,"Timer",requiresForegroundReview=foregroundReview,nativeBackgroundOnly=background),Availability.IMPLEMENTED,controls,Origin.REMOTE,100)
        assertEquals(PolicyOutcome.ALLOW,decision().outcome)
        assertEquals("foreground_review",decision(grant.copy(fullControl=true),foregroundReview=true).rule)
        assertEquals(PolicyOutcome.DENY,decision(grant.copy(scopes=emptyList()),foregroundReview=true).outcome)
        assertEquals(PolicyOutcome.DENY,decision(grant.copy(scopes=listOf("device.*"))).outcome)
        assertEquals(PolicyOutcome.DENY,decision(grant.copy(allowSharedScreen=false)).outcome)
        assertEquals(PolicyOutcome.ALLOW,decision(grant.copy(allowSharedScreen=false),background=true).outcome)
        assertEquals(PolicyOutcome.DENY,decision(grant.copy(revoked=true)).outcome)
        assertEquals(PolicyOutcome.DENY,decision(controls=DeviceControls(remoteExecutionEnabled=false)).outcome)
        assertEquals(PolicyOutcome.ALLOW,decision(controls=DeviceControls(privilegedEnabled=false)).outcome)
    }
}
