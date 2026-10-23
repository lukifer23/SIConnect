package dev.siconnect.app.automation

import dev.siconnect.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HealthScanTest {
    private fun warns(notify:Boolean=false,percent:Int=80,charging:Boolean=false,storage:Long=10_000_000_000,lowMemory:Boolean=false,thermal:Int=0):Boolean {
        val scope=mapOf("input" to buildJsonObject {put("notifyProblems",notify);put("minimumBatteryPercent",20)},"steps" to buildJsonObject {
            fun reading(id:String,output:JsonObject) {put(id,buildJsonObject {put("output",output)})}
            reading("battery",buildJsonObject {put("percent",percent);put("charging",charging)})
            reading("storage",buildJsonObject {put("availableBytes",storage)})
            reading("memory",buildJsonObject {put("lowMemory",lowMemory)})
            reading("thermal",buildJsonObject {put("status",thermal)})
        })
        return Conditions.eval(requireNotNull(AutomationTemplates.health().steps.last().`when`),scope)
    }
    @Test fun warningsRequireOptInAndActualThreshold() {
        assertFalse(warns(percent=5,storage=1,lowMemory=true,thermal=5))
        assertFalse(warns(notify=true))
        assertTrue(warns(notify=true,percent=20))
        assertFalse(warns(notify=true,percent=21))
        assertFalse(warns(notify=true,percent=5,charging=true))
        assertTrue(warns(notify=true,storage=2_147_483_647))
        assertFalse(warns(notify=true,storage=2_147_483_648))
        assertTrue(warns(notify=true,lowMemory=true))
        assertTrue(warns(notify=true,thermal=3))
    }
    @Test fun absentReadingsAndUnknownBatteryNeverTriggerWarnings() {
        assertFalse(warns(notify=true,percent=Int.MIN_VALUE))
        assertFalse(Conditions.eval(requireNotNull(AutomationTemplates.health().steps.last().`when`),mapOf("input" to buildJsonObject {put("notifyProblems",true)})))
        assertEquals(listOf("Battery: unavailable"),HealthScanSummary.lines(buildJsonObject {put("battery",buildJsonObject {put("percent",Int.MIN_VALUE)})}))
        assertTrue(HealthScanSummary.lines(JsonNull).isEmpty())
    }
    @Test fun defaultScanNeedsNoNotificationAccessAndKeepsLegacyOutputs() {
        val scan=AutomationTemplates.health()
        val inputs=AutomationTemplates.initialInputs(scan,"UTC")
        assertTrue(Schema.validate(scan.inputs,inputs).isEmpty())
        assertFalse("notifications.post" in AutomationTemplates.requestedCapabilities(scan,inputs))
        assertTrue("notifications.post" in AutomationTemplates.requestedCapabilities(scan,JsonObject(inputs+("notifyProblems" to JsonPrimitive(true)))))
        assertEquals(AutomationTemplates.legacyHealth().steps,scan.steps.take(3))
        AutomationTemplates.legacyHealth().output.forEach {(key,value)->assertEquals(value,scan.output[key])}
    }
}
