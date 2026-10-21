package dev.siconnect.app.capabilities

import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.data.AppJson
import dev.siconnect.core.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Base64

/** Bounded retrieval over the existing authenticated engine, never a second output endpoint. */
class RequestOutputCapability(private val g:RuntimeGraph):Capability {
    override val descriptor=CapabilityDescriptor("requests.output",title="Read a bounded stored request record chunk",
        description="Retrieve a stored record as UTF-8 JSON bytes encoded in base64. Use the original request ID after a lost or oversized result; never resubmit an uncertain mutation. Only your own records are accessible. Reassemble bytes at their offsets and verify sha256; restart from offset zero if the record changes while running.",
        backend=BackendKind.NATIVE,risk=RiskClass.READ_ONLY,interaction=InteractionMode.BACKGROUND,
        inputSchema=objectSchema {
            string("requestId","Original request ID",true,maxLength=200)
            integer("offset","Byte offset, defaults to zero",min=0,max=Int.MAX_VALUE.toLong())
            integer("limit","Maximum bytes in this chunk; defaults to 16384",min=1,max=32768)
        })
    override fun availability()=Availability.IMPLEMENTED
    override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {
        val record=g.requests.get(args.str("requestId")) ?: throw CapabilityException("NOT_FOUND","Unknown request")
        if(record.agentId!=ctx.agentId) throw CapabilityException("POLICY_DENIED","Request belongs to another agent")
        val bytes=AppJson.encodeToString(RequestRecord.serializer(),record).toByteArray(Charsets.UTF_8)
        val offset=args.long("offset",0).toInt();val limit=args.long("limit",16384).toInt()
        if(offset>bytes.size) throw CapabilityException("INVALID_OFFSET","Offset exceeds stored record length")
        val end=minOf(bytes.size.toLong(),offset.toLong()+limit).toInt()
        return CapabilityResult(buildJsonObject {
            put("requestId",record.requestId);put("state",record.state.name);put("updatedAtMs",record.updatedAtMs)
            put("encoding","BASE64_UTF8_JSON");put("offset",offset);put("totalBytes",bytes.size)
            put("data",Base64.getEncoder().encodeToString(bytes.copyOfRange(offset,end)))
            put("sha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)})
            put("complete",end==bytes.size);if(end<bytes.size) put("nextOffset",end)
        },BackendKind.NATIVE,"uid=${android.os.Process.myUid()}")
    }
}
