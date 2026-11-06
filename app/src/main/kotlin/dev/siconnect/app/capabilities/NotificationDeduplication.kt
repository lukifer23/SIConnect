package dev.siconnect.app.capabilities

import dev.siconnect.core.*
import kotlinx.serialization.json.*

/** A compacted result still owns its key; absence of a payload never permits another post. */
internal fun priorNotificationOutcome(originalId: String, record: RequestRecord?, args: JsonObject): JsonObject {
    if (record != null && record.argsHash != Canonical.actionHash(record.capabilityId, record.capabilityVersion, args))
        throw CapabilityException("IDEMPOTENCY_CONFLICT", "Alert key belongs to different arguments; inspect its original request", noSideEffects = true)
    if (record?.state != RequestState.SUCCEEDED)
        throw CapabilityException("ORIGINAL_OUTCOME_REQUIRED", "Alert key already claimed by $originalId. Retrieve its outcome; do not repost", noSideEffects = true)
    val out = record.result?.output as? JsonObject
        ?: throw CapabilityException("ORIGINAL_OUTCOME_REQUIRED", "Original alert $originalId succeeded; its result payload is unavailable. Do not repost", noSideEffects = true)
    return buildJsonObject { out.forEach { (k,v) -> put(k,v) }; put("originalRequestId",originalId); put("deduplicated",true) }
}
