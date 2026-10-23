package dev.siconnect.app.automation

import dev.siconnect.core.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundExecutionTest {
    private fun status(mode:InteractionMode,available:Availability=Availability.IMPLEMENTED)=CapabilityStatus(CapabilityDescriptor(
        "fixture.operation",title="Fixture",description="Fixture",inputSchema=objectSchema(),backend=BackendKind.NATIVE,risk=RiskClass.READ_ONLY,interaction=mode),available)
    private val definition=SkillDefinition("fixture.workflow",1,"Fixture",steps=listOf(SkillStep("test","fixture.operation")))
    @Test fun screenAndPossibleScreenAreDistinct() {
        assertEquals(BackgroundExecution.Kind.REQUIRES_FOREGROUND_SCREEN,BackgroundExecution.classify(definition,listOf(status(InteractionMode.SCREEN))))
        assertEquals(BackgroundExecution.Kind.MAY_REQUIRE_SCREEN,BackgroundExecution.classify(definition,listOf(status(InteractionMode.MAY_OPEN_SCREEN))))
    }
    @Test fun unavailableBackgroundIsNotPresentedAsReady() {
        assertEquals(BackgroundExecution.Kind.BACKGROUND_ONLY,BackgroundExecution.classify(definition,listOf(status(InteractionMode.BACKGROUND))))
        assertEquals(BackgroundExecution.Kind.PERMISSION_OR_BACKEND_DEPENDENT,BackgroundExecution.classify(definition,listOf(status(InteractionMode.BACKGROUND,Availability.PERMISSION_REQUIRED))))
    }
    @Test fun unknownAndMissingMetadataStayUnknown() {
        assertEquals(BackgroundExecution.Kind.UNKNOWN,BackgroundExecution.classify(definition,emptyList()))
        assertEquals(BackgroundExecution.Kind.UNKNOWN,BackgroundExecution.classify(definition,listOf(status(InteractionMode.UNSPECIFIED))))
    }
}
