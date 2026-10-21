package dev.siconnect.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.app.notifications.NotificationCaptureService
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

/** Opt-in actual Android RemoteInput test against the separate disposable fixture APK, never a contact. */
@RunWith(AndroidJUnit4::class)
class NotificationActionDeviceTest {
    @Test fun separateFixtureReceivesOneRemoteInputAndRejectsAuthenticationAndStaleReferences()=runBlocking {
        assumeTrue("Explicit disposable fixture test opt-in is required",
            InstrumentationRegistry.getArguments().getString("allowNotificationFixture")=="true")
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph;g.ready.await()
        assumeTrue("Do not disturb another agent's screen lease",g.ui.status()==null)
        val service=withTimeoutOrNull(15_000) {while(NotificationCaptureService.instance==null) delay(50);NotificationCaptureService.instance}
        assumeTrue("Owner must first enable SIConnect notification-listener access",service!=null)
        val listener=requireNotNull(service)
        val notification=listener.metadata().singleOrNull {it.packageName=="dev.siconnect.notificationfixture" && it.tag=="siconnect-disposable-notification"}
        assumeTrue("Install/grant/launch the separate disposable fixture before running this test",notification!=null)
        val fixture=requireNotNull(notification)
        val agent="notification-fixture:${UUID.randomUUID()}"
        g.agents.put(AgentRecord(AgentGrant(agent,"Disposable notification action test",scopes=listOf("notifications.actions","notifications.invoke_action","notifications.reply_supported"),
            autoApproveUpTo=RiskClass.HIGH_IMPACT),"",System.currentTimeMillis(),tokenHint="test-only identity; no credential"))
        fun receipt():Pair<Int,String> = requireNotNull(g.context.contentResolver.query(
            Uri.parse("content://dev.siconnect.notificationfixture.receipts/receipt"),null,null,null,null)).use {cursor ->
            assertTrue(cursor.moveToFirst());cursor.getInt(cursor.getColumnIndexOrThrow("count")) to cursor.getString(cursor.getColumnIndexOrThrow("digest"))
        }
        suspend fun call(capability:String,args:JsonObject)=g.engine.submit(InvocationRequest(agentId=agent,capabilityId=capability,arguments=args),Origin.REMOTE)
        val base=buildJsonObject {put("key",fixture.key);put("packageName",fixture.packageName)}
        try {
            // Wait for post callback before discovering a version used by this test.
            delay(200)
            val discovered=call("notifications.actions",base)
            assertEquals(RequestState.SUCCEEDED,discovered.state)
            val metadata=discovered.result!!.output.jsonObject
            assertFalse(metadata.getValue("bodiesIncluded").jsonPrimitive.boolean)
            val actions=metadata.getValue("actions").jsonArray.map {it.jsonObject}
            assertTrue(actions.none {"label" in it})
            val reply=actions.single {it.getValue("replySupported").jsonPrimitive.boolean}
            val auth=actions.single {it.getValue("authenticationRequired").jsonPrimitive.boolean}
            fun reference(action:JsonObject,replyText:String?=null)=buildJsonObject {
                base.forEach {(k,v)->put(k,v)};put("notificationVersion",metadata.getValue("notificationVersion"));put("actionId",action.getValue("actionId"))
                replyText?.let {put("text",it);put("resultKey","fixture_text")}
            }
            val before=receipt().first
            val refused=call("notifications.invoke_action",reference(auth))
            assertEquals("OWNER_AUTHENTICATION_REQUIRED",refused.errorCode)
            assertEquals(before,receipt().first)
            val text="Disposable RemoteInput ${UUID.randomUUID()}"
            val request=InvocationRequest(requestId="remote-input-fixture:${UUID.randomUUID()}",agentId=agent,
                capabilityId="notifications.reply_supported",arguments=reference(reply,text))
            val sent=g.engine.submit(request,Origin.REMOTE)
            assertEquals(RequestState.SUCCEEDED,sent.state)
            assertEquals("DISPATCHED",sent.result!!.output.jsonObject.getValue("state").jsonPrimitive.content)
            assertEquals(VerificationStatus.NOT_VERIFIED,sent.result!!.verification)
            val expected=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {"%02x".format(it)}
            withTimeout(10_000) {while(receipt()!=Pair(before+1,expected)) delay(50)}
            assertEquals(sent.requestId,g.engine.submit(request,Origin.REMOTE).requestId)
            delay(200);assertEquals(before+1,receipt().first)
            withTimeout(10_000) {
                while(listener.actionSnapshot(fixture.key,fixture.packageName).version==metadata.getValue("notificationVersion").jsonPrimitive.content) delay(50)
            }
            val stale=call("notifications.reply_supported",reference(reply,"Never dispatch this stale fixture"))
            assertEquals("NOTIFICATION_CHANGED",stale.errorCode)
            assertEquals(before+1,receipt().first)
            assertTrue(g.events.query(requestId=sent.requestId).any {it.eventType=="execution.finished"})
            assertTrue(g.events.query(requestId=sent.requestId).none {it.detail?.contains(text)==true})
            assertNull(g.ui.status())
        } finally {
            listener.cancelNotification(fixture.key)
            g.agents.delete(agent)
        }
    }
}
