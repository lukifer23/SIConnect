package dev.siconnect.app.automation

import dev.siconnect.core.*

internal object BackgroundExecution {
    fun description(definition:SkillDefinition,catalog:List<CapabilityStatus>):String {
        val modes=definition.steps.map {step->catalog.firstOrNull {it.descriptor.id==step.capability}?.descriptor?.interaction}
        return when {
            modes.any {it==InteractionMode.SCREEN || it==InteractionMode.MAY_OPEN_SCREEN}->"Uses the phone's screen"
            modes.isNotEmpty() && modes.all {it==InteractionMode.BACKGROUND}->"Runs without opening the screen"
            else->"Background behavior depends on the workflow"
        }
    }
}
