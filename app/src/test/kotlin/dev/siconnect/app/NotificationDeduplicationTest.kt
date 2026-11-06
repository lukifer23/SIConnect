package dev.siconnect.app

import dev.siconnect.app.capabilities.priorNotificationOutcome
import dev.siconnect.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NotificationDeduplicationTest {
    private val args=buildJsonObject {put("title","Fixture");put("text","Private");put("deduplicationKey","key")}
    private val record=RequestRecord("original","trace","agent","device","notifications.post",1,args,
        Canonical.actionHash("notifications.post",1,args),origin="REMOTE",state=RequestState.SUCCEEDED,
        createdAtMs=1,updatedAtMs=2,expiresAtMs=3,result=CapabilityResult(buildJsonObject {put("postedObserved",true)},BackendKind.NATIVE))
    @Test fun completedPayloadReturnsTheOriginalObservedResult() {
        val out=priorNotificationOutcome(record.requestId,record,args)
        assertTrue(out.getValue("deduplicated").jsonPrimitive.boolean)
        assertEquals(record.requestId,out.getValue("originalRequestId").jsonPrimitive.content)
    }
    @Test fun compactedMatchingPayloadRequiresOriginalOutcomeInsteadOfConflictingOrReposting() {
        val compact=record.copy(arguments=JsonObject(emptyMap()),result=null,payloadExpired=true)
        try {priorNotificationOutcome(record.requestId,compact,args);fail("Expired result cannot authorize posting")}
        catch(e:CapabilityException) {assertEquals("ORIGINAL_OUTCOME_REQUIRED",e.code);assertTrue(e.noSideEffects)}
        try {priorNotificationOutcome(record.requestId,compact,buildJsonObject {put("title","different")});fail("Changed arguments must conflict")}
        catch(e:CapabilityException) {assertEquals("IDEMPOTENCY_CONFLICT",e.code)}
    }
    @Test fun missingOrUncertainOriginalNeverAuthorizesPosting() {
        for(original in listOf(null,record.copy(state=RequestState.UNKNOWN_OUTCOME))) {
            try {priorNotificationOutcome(record.requestId,original,args);fail("Must retain original ownership")}
            catch(e:CapabilityException) {assertEquals("ORIGINAL_OUTCOME_REQUIRED",e.code)}
        }
    }
}
