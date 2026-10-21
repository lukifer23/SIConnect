package dev.siconnect.app.transport

import dev.siconnect.app.data.AppJson
import dev.siconnect.core.RequestRecord
import kotlinx.serialization.json.*

/** Phone SQLite retains the original result; oversized records are retrieved in authorized chunks. */
internal object ResultEnvelope {
    const val MAX_BYTES=1_048_576
    fun result(correlation:String,record:RequestRecord):String {
        val result=buildJsonObject {
            put("type","result");put("id",correlation)
            put("record",AppJson.encodeToJsonElement(RequestRecord.serializer(),record))
        }.toString()
        if(result.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) return result
        return buildJsonObject {
            put("type","error");put("id",correlation);put("code","RESULT_TOO_LARGE")
            put("requestId",record.requestId);put("executionState",record.state.name)
            put("recovery",buildJsonObject {
                put("tool","requests_output");put("arguments",buildJsonObject {put("requestId",record.requestId);put("offset",0)})
                put("doNotResubmitMutation",true)
            })
        }.toString()
    }
}
