package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WeatherNativeDeviceTest {
    private val g=InstrumentationRegistry.getInstrumentation().targetContext.graph
    @Before fun ready()=runBlocking {g.ready.await()}
    @Test fun actualNotificationAndReminderKeysPreventRepeatedNativeSideEffects()=runBlocking {
        val key="weather-fixture:"+UUID.randomUUID()
        suspend fun call(id:String,args:JsonObject)=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId=id,arguments=args),Origin.LOCAL_OWNER)
        val alert=buildJsonObject {put("title","SIConnect deduplication test");put("text","Disposable native notification");put("deduplicationKey",key)}
        var reminderId:String?=null
        val nm=g.context.getSystemService(android.app.NotificationManager::class.java)
        var tag:String?=null
        try {
            val one=call("notifications.post",alert);Assert.assertEquals(one.errorMessage,RequestState.SUCCEEDED,one.state)
            tag=one.result!!.output.jsonObject.getValue("notificationTag").jsonPrimitive.content
            val two=call("notifications.post",alert);Assert.assertEquals(RequestState.SUCCEEDED,two.state)
            Assert.assertEquals(one.requestId,two.result!!.output.jsonObject.getValue("originalRequestId").jsonPrimitive.content)
            Assert.assertEquals(1,nm.activeNotifications.count {it.tag==tag})
            Assert.assertEquals("IDEMPOTENCY_CONFLICT",call("notifications.post",JsonObject(alert+ ("text" to JsonPrimitive("Changed action")))).errorCode)
            val args=buildJsonObject {put("title","SIConnect reminder deduplication test");put("text","Disposable future schedule");put("fireAtMs",System.currentTimeMillis()+60000);put("deduplicationKey",key)}
            val first=call("reminders.create",args);Assert.assertEquals(RequestState.SUCCEEDED,first.state)
            reminderId=first.result!!.output.jsonObject.getValue("id").jsonPrimitive.content
            val duplicate=call("reminders.create",args);Assert.assertEquals(first.result!!.output,duplicate.result!!.output)
            Assert.assertEquals("IDEMPOTENCY_CONFLICT",call("reminders.create",JsonObject(args+("text" to JsonPrimitive("Changed action")))).errorCode)
        } finally {
            tag?.let {nm.cancel(it,0)}
            reminderId?.let {g.reminders.cancel(it);g.db.writableDatabase.delete("reminders","reminder_id=?",arrayOf(it))}
        }
    }
    @Test fun realForecastRunsTheInstalledDeterministicSkillWithoutChangingOwnerData()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val lat=args.getString("weatherTestLatitude")?.toDoubleOrNull();val lon=args.getString("weatherTestLongitude")?.toDoubleOrNull()
        Assume.assumeTrue("Supply an explicit public test location; owner defaults are never changed",lat!=null && lon!=null)
        val input=buildJsonObject {
            put("latitude",lat!!);put("longitude",lon!!);put("timezone","UTC");put("wakeTime","07:30");put("departureTime","09:00")
            put("notifyWinter",false);put("notifyCold",false);put("remindRain",false)
        }
        val id="weather-live-fixture:"+UUID.randomUUID()
        val run=g.runner.start(g.skills.get("weather.commute_check")!!,input,ExecutionContext(id,id,"local-owner",g.settings.deviceId),Origin.LOCAL_OWNER)
        Assert.assertEquals(run.error,SkillState.SUCCEEDED,run.state)
        val forecast=run.steps.single {it.id=="forecast"}.output.jsonObject
        Assert.assertEquals("Open-Meteo",forecast.getValue("provider").jsonPrimitive.content)
        Assert.assertEquals("COMPLETE",forecast.getValue("completeness").jsonPrimitive.content)
        Assert.assertTrue(run.steps.single {it.id=="evening"}.skipped)
        Assert.assertTrue(run.steps.single {it.id=="morning"}.skipped)
        Assert.assertEquals(run,g.runner.resume(id))
    }
}
