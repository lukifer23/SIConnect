package dev.siconnect.app.automation

import dev.siconnect.core.*

internal object BackgroundExecution {
    enum class Kind { BACKGROUND_ONLY, REQUIRES_FOREGROUND_SCREEN, MAY_REQUIRE_SCREEN, PERMISSION_OR_BACKEND_DEPENDENT, UNKNOWN }
    fun classify(definition:SkillDefinition,catalog:List<CapabilityStatus>):Kind {
        val statuses=definition.steps.map {step->catalog.firstOrNull {it.descriptor.id==step.capability}}
        val modes=statuses.map {it?.descriptor?.interaction}
        return when {
            InteractionMode.SCREEN in modes->Kind.REQUIRES_FOREGROUND_SCREEN
            InteractionMode.MAY_OPEN_SCREEN in modes->Kind.MAY_REQUIRE_SCREEN
            modes.isEmpty() || modes.any {it==null || it==InteractionMode.UNSPECIFIED}->Kind.UNKNOWN
            statuses.any {it?.availability?.isExecutable!=true}->Kind.PERMISSION_OR_BACKEND_DEPENDENT
            else->Kind.BACKGROUND_ONLY
        }
    }
    fun description(definition:SkillDefinition,catalog:List<CapabilityStatus>):String=when(classify(definition,catalog)) {
        Kind.BACKGROUND_ONLY->"Runs without opening the screen"
        Kind.REQUIRES_FOREGROUND_SCREEN->"Requires the phone's screen"
        Kind.MAY_REQUIRE_SCREEN->"May need the screen, depending on the action"
        Kind.PERMISSION_OR_BACKEND_DEPENDENT->"Background execution needs permission or backend attention"
        Kind.UNKNOWN->"Screen requirements are not specified by this workflow"
    }
}
