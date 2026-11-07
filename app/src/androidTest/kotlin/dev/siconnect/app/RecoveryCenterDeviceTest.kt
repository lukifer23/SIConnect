package dev.siconnect.app

import android.app.NotificationManager
import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.app.notifications.NotificationCaptureService
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Actual registry/engine, saved Android access and native listener. Never grants permissions. */
class RecoveryCenterDeviceTest {
    private val g get()=InstrumentationRegistry.getInstrumentation().targetContext.graph
    @Test fun recoveryStatusReportsActualBindingsWithoutCredentialsOrMessageContent()=runBlocking {
        g.ready.await()
        val record=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="system.recovery_status"),Origin.LOCAL_OWNER)
        assertEquals(RequestState.SUCCEEDED,record.state)
        val value=requireNotNull(record.result).output.jsonObject
        assertEquals(g.gateway.state.value.name,value.getValue("gatewayState").jsonPrimitive.content)
        assertEquals(g.settings.gateway.value.enabled,value.getValue("gatewayEnabled").jsonPrimitive.boolean)
        assertEquals(NotificationCaptureService.connectionState.value,value.getValue("notificationServiceConnected").jsonPrimitive.boolean)
        assertEquals(g.startup.stages.value.size,value.getValue("startupStages").jsonArray.size)
        assertFalse(value.containsKey("token"));assertFalse(value.containsKey("gatewayUrl"));assertFalse(value.containsKey("notifications"))
    }
    @Test fun fullRemoteGrantCannotRetryOwnerStartupOrRebindOwnerListener()=runBlocking {
        g.ready.await();val id="recovery-policy-${UUID.randomUUID()}"
        g.agents.put(AgentRecord(AgentGrant(id,"Disposable recovery policy",scopes=listOf("*"),fullControl=true,
            autoApproveUpTo=RiskClass.CRITICAL,shellMode=ShellMode.BROAD,allowSharedScreen=true),"",System.currentTimeMillis(),tokenHint="Recovery policy test only"))
        try {
            val stages=g.startup.stages.value
            for(capability in listOf("system.retry_startup","system.rebind_notifications")) {
                val record=g.engine.submit(InvocationRequest(agentId=id,capabilityId=capability),Origin.REMOTE)
                assertEquals(RequestState.DENIED,record.state)
            }
            assertEquals(stages,g.startup.stages.value)
        } finally {g.agents.delete(id)}
    }
    @Test fun successfulOwnerRetryDoesNotRerunEssentialStagesOrRepeatTheSameRequest()=runBlocking {
        g.ready.await();assumeTrue("This case requires all real startup tasks already ready",g.startup.stages.value.all {it.state=="READY"})
        val stages=g.startup.stages.value
        val invocation=InvocationRequest(agentId="local-owner",capabilityId="system.retry_startup")
        val first=g.engine.submit(invocation,Origin.LOCAL_OWNER)
        assertEquals(RequestState.SUCCEEDED,first.state)
        assertTrue(first.result!!.output.jsonObject.getValue("retriedStages").jsonArray.isEmpty())
        assertEquals(first,g.engine.submit(invocation,Origin.LOCAL_OWNER));assertEquals(stages,g.startup.stages.value)
        val executions=g.db.readableDatabase.rawQuery("SELECT count(*) FROM request_transitions WHERE request_id=? AND to_state='EXECUTING'",arrayOf(invocation.requestId))
            .use {it.moveToFirst();it.getInt(0)}
        assertEquals(1,executions)
    }
    @Test fun ownerCanRequestNativeReconnectOfAlreadyGrantedOwnListener()=runBlocking {
        assumeTrue("Coordinated listener reconnect opt-in required",InstrumentationRegistry.getArguments().getString("allowRecoveryRebind")=="true")
        g.ready.await();val manager=g.context.getSystemService(NotificationManager::class.java)
        val component=ComponentName(g.context,NotificationCaptureService::class.java)
        assumeTrue("Owner must already grant notification access",manager.isNotificationListenerAccessGranted(component))
        val record=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="system.rebind_notifications"),Origin.LOCAL_OWNER)
        assertEquals(RequestState.SUCCEEDED,record.state)
        assertTrue(record.result!!.output.jsonObject.getValue("rebindRequested").jsonPrimitive.boolean)
        withTimeout(10000) {while(!NotificationCaptureService.connectionState.value) delay(100)}
        assertTrue(manager.isNotificationListenerAccessGranted(component))
    }
}
