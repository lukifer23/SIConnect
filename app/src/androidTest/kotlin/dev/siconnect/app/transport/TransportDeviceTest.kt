package dev.siconnect.app.transport

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.siconnect.app.graph
import dev.siconnect.app.backend.*
import dev.siconnect.app.data.*
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.UUID

/** Real Binder pipes and SQLite, with bounded process output and disposable test identity. */
@RunWith(AndroidJUnit4::class)
class TransportDeviceTest {
    private val g=InstrumentationRegistry.getInstrumentation().targetContext.graph
    @Test fun maximumBinaryStreamsCrossBinderWithoutOversizeTransaction()=runBlocking {
        g.ready.await();withTimeout(10000) {while(!g.privileged.available()) delay(50)}
        val result=g.privileged.execute(ShellRequest("envelope:"+UUID.randomUUID(),command="head -c 131072 /dev/zero; head -c 131072 /dev/zero >&2",timeoutMs=15000,maxOutputBytes=131072))
        assertEquals(0,result.exitCode);assertNull(result.error)
        assertEquals(131072,result.stdoutBytesCaptured);assertEquals(131072,result.stderrBytesCaptured)
        assertEquals(131072L,result.stdoutBytesObserved);assertEquals(131072L,result.stderrBytesObserved)
        assertEquals(131072,Base64.getDecoder().decode(result.stdoutBase64).size)
        assertEquals(131072,Base64.getDecoder().decode(result.stderrBase64).size)
        assertTrue(result.stdoutTextTruncated);assertTrue(result.stderrTextTruncated)
        assertTrue(AppJson.encodeToString(ShellResult.serializer(),result).toByteArray().size<=512*1024)
        val binary=g.privileged.execute(ShellRequest("envelope:"+UUID.randomUUID(),command="printf '\\000\\377'",outputEncoding="base64"))
        assertEquals("",binary.stdout);assertEquals("AP8=",binary.stdoutBase64);assertFalse(binary.stdoutUtf8Valid)
    }
    @Test fun storedResultChunksPreserveOriginalBytesAndRejectAnotherAgent()=runBlocking {
        g.ready.await()
        val original=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="device.status"),Origin.LOCAL_OWNER)
        val expected=AppJson.encodeToString(RequestRecord.serializer(),original).toByteArray()
        val chunk=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="requests.output",arguments=buildJsonObject {put("requestId",original.requestId);put("limit",32)}),Origin.LOCAL_OWNER)
        assertEquals(RequestState.SUCCEEDED,chunk.state)
        val output=chunk.result!!.output.jsonObject
        assertEquals(expected.size,output.getValue("totalBytes").jsonPrimitive.int)
        assertArrayEquals(expected.copyOfRange(0,32),Base64.getDecoder().decode(output.getValue("data").jsonPrimitive.content))
        val agent="transport-fixture:"+UUID.randomUUID()
        try {
            g.agents.put(AgentRecord(AgentGrant(agent,"Disposable output test",scopes=listOf("requests.*")),"",System.currentTimeMillis(),tokenHint="test only"))
            val denied=g.engine.submit(InvocationRequest(agentId=agent,capabilityId="requests.output",arguments=buildJsonObject {put("requestId",original.requestId)}))
            assertEquals(RequestState.FAILED,denied.state);assertEquals("POLICY_DENIED",denied.errorCode)
        } finally {g.agents.delete(agent)}
    }
}
