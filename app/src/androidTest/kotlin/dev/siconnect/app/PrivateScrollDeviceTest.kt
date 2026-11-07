package dev.siconnect.app

import android.app.KeyguardManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.backend.ShellRequest
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real Android ScrollView on an isolated display, using the separate disposable app. */
class PrivateScrollDeviceTest {
    @Test fun semanticAndTouchScrollChangeRealContentWithoutMovingPhysicalFocus() = runBlocking {
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph
        g.ready.await()
        withTimeout(15_000) {while(!g.privileged.available()) delay(50)}
        val pkg="dev.siconnect.notificationfixture"
        g.context.packageManager.getApplicationInfo(pkg,0)
        assertFalse("Private rendering requires an already-unlocked device",g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        suspend fun physicalFocus():Map<Int,String> {
            val physical=g.context.getSystemService(android.hardware.display.DisplayManager::class.java).displays.filter {it.isInternal}.map {it.displayId}.toSet()
            val result=g.privileged.execute(ShellRequest("scroll-focus-${UUID.randomUUID()}",argv=listOf("dumpsys","window","displays"),maxOutputBytes=131072))
            assertEquals(0,result.exitCode);assertFalse(result.stdoutTruncated)
            var display=-1
            val focused=linkedMapOf<Int,String>()
            for(line in result.stdout.lineSequence()) {
                Regex("Display: mDisplayId=(\\d+)").find(line)?.let {display=it.groupValues[1].toInt()}
                if(display in physical && "mCurrentFocus=" in line) focused[display]=line.trim()
            }
            assertTrue(focused.isNotEmpty());return focused
        }
        val originalFocus=physicalFocus()
        val id="private-scroll-test-${UUID.randomUUID()}"
        g.agents.put(AgentRecord(AgentGrant(id,"Disposable real private scroll acceptance",scopes=listOf("headless.*"),autoApproveUpTo=RiskClass.HIGH_IMPACT),"",System.currentTimeMillis(),tokenHint="device test"))
        suspend fun call(cap:String,args:JsonObject=buildJsonObject {}):JsonObject {
            val r=g.engine.submit(InvocationRequest(agentId=id,capabilityId="headless.$cap",arguments=args),Origin.REMOTE)
            assertEquals("$cap: ${r.errorCode}: ${r.errorMessage}",RequestState.SUCCEEDED,r.state)
            return requireNotNull(r.result).output.jsonObject
        }
        var session:String?=null
        fun bound(extra:JsonObject=buildJsonObject {})=buildJsonObject {
            put("sessionId",requireNotNull(session));put("packageName",pkg);extra.forEach {(k,v)->put(k,v)}
        }
        suspend fun inspect():JsonObject=withTimeout(10_000) {
            while(true) {
                val r=g.engine.submit(InvocationRequest(agentId=id,capabilityId="headless.inspect",arguments=bound()),Origin.REMOTE)
                if(r.state==RequestState.SUCCEEDED) return@withTimeout requireNotNull(r.result).output.jsonObject
                assertTrue(r.errorCode in setOf("WORKSPACE_WINDOW_UNAVAILABLE","WORKSPACE_TARGET_CHANGED"));delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("No fixture window")
        }
        fun rows(snapshot:JsonObject)=snapshot.getValue("nodes").jsonArray.map {it.jsonObject}
        fun visibleRows(snapshot:JsonObject)=rows(snapshot).filter {it["text"]?.jsonPrimitive?.content.orEmpty().startsWith("Disposable fixture row ")}
            .map {it["text"] to it["bounds"]}
        try {
            val opened=call("open",buildJsonObject {put("packageName",pkg);put("uri","https://siconnect-fixture.invalid/semantic");put("ttlMs",120_000)})
            session=opened.getValue("sessionId").jsonPrimitive.content
            assertTrue(opened.getValue("displayId").jsonPrimitive.int>0)
            var snapshot=inspect()
            for(method in listOf("SEMANTIC","PRIVATE_TOUCH")) {
                val before=visibleRows(snapshot)
                assertTrue("Fixture must expose actual visible rows",before.isNotEmpty())
                val node=rows(snapshot).single {it["description"]?.jsonPrimitive?.content=="Fixture scroll region"}
                val result=call("scroll",bound(buildJsonObject {
                    put("snapshotId",snapshot.getValue("snapshotId"));put("nodeRef",node.getValue("nodeRef"))
                    put("direction","FORWARD");put("inputMethod",method)
                }))
                assertEquals(true,result["actionAccepted"]?.jsonPrimitive?.booleanOrNull)
                snapshot=withTimeout(5000) {
                    var fresh=inspect()
                    while(visibleRows(fresh)==before) {delay(100);fresh=inspect()}
                    fresh
                }
                assertNotEquals("$method must change actual row content/bounds",before,visibleRows(snapshot))
                assertEquals("$method changed physical focus",originalFocus,physicalFocus())
            }
            val capture=call("capture",bound())
            val bytes=android.util.Base64.decode(capture.getValue("data").jsonPrimitive.content,android.util.Base64.DEFAULT)
            assertEquals(capture.getValue("bytes").jsonPrimitive.int,bytes.size)
            assertTrue(bytes.size in 1..128*1024)
            val frame=requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            try {
                assertEquals(capture.getValue("imageWidth").jsonPrimitive.int,frame.width)
                assertEquals(capture.getValue("imageHeight").jsonPrimitive.int,frame.height)
                assertTrue(frame.width<=capture.getValue("originalWidth").jsonPrimitive.int)
                assertTrue(frame.height<=capture.getValue("originalHeight").jsonPrimitive.int)
            } finally {frame.recycle()}
            assertEquals("Private capture changed physical focus",originalFocus,physicalFocus())
        } finally {
            try {
                // Discover only this disposable identity's actual resources, including
                // an open whose reply may have been lost. Never close other agents.
                val owned=call("status").getValue("sessions").jsonArray.map {it.jsonObject}
                for(workspace in owned) {
                    assertEquals(pkg,workspace.getValue("packageName").jsonPrimitive.content)
                    val displayId=workspace.getValue("displayId").jsonPrimitive.int
                    call("close",buildJsonObject {
                        put("sessionId",workspace.getValue("sessionId"));put("packageName",pkg)
                    })
                    withTimeout(10_000) {
                        val displays=g.context.getSystemService(android.hardware.display.DisplayManager::class.java)
                        while(displays.getDisplay(displayId)?.isValid==true) delay(50)
                    }
                }
                assertTrue(call("status").getValue("sessions").jsonArray.isEmpty())
                g.agents.delete(id)
            } catch(error:Throwable) {
                // Keep ownership/history if cleanup cannot be proven.
                g.revokeAgentFromOwner(id)
                throw error
            }
        }
    }
}
