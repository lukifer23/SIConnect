package dev.siconnect.app.automation

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HealthAlertPolicyTest {
    private val now=1_790_000_000_000L
    private fun readings(percent:Int=80,charging:Boolean=false,bytes:Long=10_000_000_000,low:Boolean=false,thermal:Int=0,repeat:Boolean=false)=buildJsonObject {
        put("minimumBatteryPercent",20);put("cooldownMinutes",60);put("repeatPersistent",repeat)
        put("battery",buildJsonObject {put("percent",percent);put("charging",charging)})
        put("storage",buildJsonObject {put("availableBytes",bytes)})
        put("memory",buildJsonObject {put("lowMemory",low)})
        put("thermal",buildJsonObject {put("status",thermal)})
    }
    @Test fun healthyAndInvalidReadingsDoNotWarn() {
        assertTrue(HealthAlertPolicy.evaluate(readings(),emptyMap(),now).notify.isEmpty())
        val bad=HealthAlertPolicy.evaluate(readings(percent=Int.MIN_VALUE,bytes=-1,thermal=99),emptyMap(),now)
        assertTrue(bad.notify.isEmpty());assertEquals(setOf("battery_low","storage_low","thermal_high"),bad.unavailable.toSet())
        val missing=HealthAlertPolicy.evaluate(buildJsonObject {put("minimumBatteryPercent",20);put("cooldownMinutes",60)},emptyMap(),now)
        assertEquals(4,missing.unavailable.size);assertTrue(missing.notify.isEmpty())
    }
    @Test fun everyThresholdUsesStableIdentityAndChargingSuppressesBattery() {
        val result=HealthAlertPolicy.evaluate(readings(percent=20,bytes=1,low=true,thermal=3),emptyMap(),now)
        assertEquals(HealthAlertPolicy.ids.toSet(),result.notify.keys)
        assertTrue(HealthAlertPolicy.evaluate(readings(percent=1,charging=true),emptyMap(),now).notify.isEmpty())
        assertFalse("battery_low" in HealthAlertPolicy.evaluate(readings(percent=21),emptyMap(),now).active)
    }
    @Test fun unchangedWarningsStayQuietAndOptionalRemindersRespectCooldown() {
        val first=HealthAlertPolicy.evaluate(readings(percent=20),emptyMap(),now)
        val early=HealthAlertPolicy.evaluate(readings(percent=20,repeat=true),first.states,now+1000)
        assertTrue(early.notify.isEmpty());assertEquals("COOLDOWN",early.suppressed["battery_low"])
        assertEquals(now+3_600_000,early.nextEligibleAtMs)
        assertTrue(HealthAlertPolicy.evaluate(readings(percent=20),first.states,now+3_600_001).notify.isEmpty())
        assertEquals("REMINDER",HealthAlertPolicy.evaluate(readings(percent=20,repeat=true),first.states,now+3_600_001).notify["battery_low"])
    }
    @Test fun worseningCriticalStateCanAlertSoonerAndCannotStaySuppressedForever() {
        val first=HealthAlertPolicy.evaluate(readings(thermal=3),emptyMap(),now)
        assertTrue(HealthAlertPolicy.evaluate(readings(thermal=4),first.states,now+30_000).notify.isEmpty())
        val worse=HealthAlertPolicy.evaluate(readings(thermal=4),first.states,now+60_000)
        assertEquals("WORSENED",worse.notify["thermal_high"])
        assertEquals("REMINDER",HealthAlertPolicy.evaluate(readings(thermal=4),worse.states,now+3_660_001).notify["thermal_high"])
    }
    @Test fun hysteresisAndReentryDoNotSpamAtThresholdBoundary() {
        val first=HealthAlertPolicy.evaluate(readings(percent=20,bytes=2_000_000_000,thermal=3),emptyMap(),now)
        val hovering=HealthAlertPolicy.evaluate(readings(percent=21,bytes=2_300_000_000,thermal=2),first.states,now+1000)
        assertEquals(setOf("battery_low","storage_low","thermal_high"),hovering.active.toSet());assertTrue(hovering.notify.isEmpty())
        val cleared=HealthAlertPolicy.evaluate(readings(percent=23,bytes=2_700_000_000,thermal=1),hovering.states,now+2000)
        assertEquals(3,cleared.cleared.size)
        assertTrue(HealthAlertPolicy.evaluate(readings(percent=20),cleared.states,now+3000).notify.isEmpty())
    }
    @Test fun missingReadingPreservesConditionAndClockRollbackDoesNotSuppressIndefinitely() {
        val first=HealthAlertPolicy.evaluate(readings(percent=20),emptyMap(),now)
        val unknown=HealthAlertPolicy.evaluate(readings(percent=Int.MIN_VALUE),first.states,now+1000)
        assertTrue("battery_low" in unknown.active);assertTrue(unknown.notify.isEmpty());assertTrue(unknown.cleared.isEmpty())
        assertFalse(HealthAlertPolicy.evaluate(readings(percent=20,repeat=true),first.states,now-86_400_000).notify.isEmpty())
    }
    @Test fun successfulSuppressionIsNotPresentedAsPostedNotification() {
        val output=buildJsonObject {
            put("warningState","SUCCEEDED");put("warning",buildJsonObject {put("postedObserved",false);put("healthAlert",buildJsonObject {put("reason","COOLDOWN");put("activeIssues",JsonArray(listOf(JsonPrimitive("battery_low"))))})})
        }
        val lines=HealthScanSummary.lines(output)
        assertTrue(lines.any {"cooldown" in it});assertFalse(lines.any {it=="Health warning notification posted"})
    }
}
