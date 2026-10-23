package dev.siconnect.app

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.*
import dev.siconnect.app.automation.*
import dev.siconnect.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Controlled readings stay in this disposable fixture DB. Real posts are clearly labelled tests. */
class HealthAlertDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun readings()=buildJsonObject {
        put("minimumBatteryPercent",20);put("cooldownMinutes",60);put("repeatPersistent",false)
        put("battery",buildJsonObject {put("percent",10);put("charging",false)})
    }
    @Test fun actualNativeReadingsUseProductionCooldownWithExplicitTestThreshold()=runBlocking {
        val g=context.graph;g.ready.await()
        val policy=Settings.sha256Hex("health-v1:local-owner:99")
        val tag="siconnect-health:$policy";val w=g.db.writableDatabase
        assertEquals("Refuse to overwrite existing owner alert state",0,android.database.DatabaseUtils.queryNumEntries(w,"health_alert_state","agent_id=? AND policy_id=?",arrayOf("local-owner",policy)))
        val postedRequests=mutableSetOf<String>()
        try {
            val measured=mutableMapOf<String,JsonElement>()
            for(name in listOf("battery","storage","memory","thermal")) {
                val r=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="device.$name"),Origin.LOCAL_OWNER)
                assertEquals(r.errorMessage,RequestState.SUCCEEDED,r.state);measured[name]=r.result!!.output
            }
            val battery=measured.getValue("battery").jsonObject
            assertTrue("Test requires an actual valid battery reading",battery.getValue("percent").jsonPrimitive.int in 0..98)
            assertFalse("Test requires the phone to be unplugged; never fake its charging state",battery.getValue("charging").jsonPrimitive.boolean)
            val health=buildJsonObject {put("minimumBatteryPercent",99);put("cooldownMinutes",60);put("repeatPersistent",false);measured.forEach {(k,v)->put(k,v)}}
            suspend fun post():JsonObject {
                val request=InvocationRequest(agentId="local-owner",capabilityId="notifications.post",arguments=buildJsonObject {
                    put("title","SIConnect disposable notification test")
                    put("text","Delivery test using a deliberately high test threshold and real readings. This is not a device health warning.")
                    put("healthCheck",health)
                });postedRequests+=request.requestId
                val result=g.engine.submit(request,Origin.LOCAL_OWNER)
                assertEquals(result.errorMessage,RequestState.SUCCEEDED,result.state)
                return result.result!!.output.jsonObject
            }
            assertTrue(post().getValue("postedObserved").jsonPrimitive.boolean)
            val repeat=post();assertFalse(repeat.getValue("postedObserved").jsonPrimitive.boolean)
            assertEquals("COOLDOWN",repeat.getValue("healthAlert").jsonObject.getValue("reason").jsonPrimitive.content)
            assertEquals(1,context.getSystemService(NotificationManager::class.java).activeNotifications.count {it.tag==tag})
        } finally {
            context.getSystemService(NotificationManager::class.java).cancel(tag,0)
            val ledger=w.query("health_alert_state",arrayOf("json"),"agent_id=? AND policy_id=?",arrayOf("local-owner",policy),null,null,null,"1").use {if(it.moveToFirst()) AppJson.decodeFromString(HealthAlertLedger.serializer(),it.getString(0)) else null}
            if(ledger!=null && ledger.requestId in postedRequests) w.delete("health_alert_state","agent_id=? AND policy_id=?",arrayOf("local-owner",policy))
        }
    }
    @Test fun installedCatalogDescriptionsFollowActualMetadata()=runBlocking {
        val g=context.graph;g.ready.await();val catalog=g.registry.statuses()
        catalog.forEach {status->
            val definition=SkillDefinition("fixture.catalog",1,"Fixture",steps=listOf(SkillStep("check",status.descriptor.id)))
            val kind=BackgroundExecution.classify(definition,catalog)
            when(status.descriptor.interaction) {
                InteractionMode.SCREEN->assertEquals(BackgroundExecution.Kind.REQUIRES_FOREGROUND_SCREEN,kind)
                InteractionMode.MAY_OPEN_SCREEN->assertEquals(BackgroundExecution.Kind.MAY_REQUIRE_SCREEN,kind)
                InteractionMode.UNSPECIFIED->assertEquals(BackgroundExecution.Kind.UNKNOWN,kind)
                InteractionMode.BACKGROUND->assertEquals(if(status.availability.isExecutable) BackgroundExecution.Kind.BACKGROUND_ONLY else BackgroundExecution.Kind.PERMISSION_OR_BACKEND_DEPENDENT,kind)
            }
        }
    }
    @Test fun persistedCooldownPostsOneRealTestNotificationAndIsolatesIdentities()=runBlocking {
        val g=context.graph;g.ready.await()
        val name="health-alert-${UUID.randomUUID()}.db";val postedTags=mutableSetOf<String>()
        val nm=context.getSystemService(NotificationManager::class.java)
        assertTrue("Requires owner-enabled notifications",nm.areNotificationsEnabled())
        try {SIConnectDb(context,name).use {db->
            suspend fun post(tag:String):JsonObject {
                val r=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="notifications.post",arguments=buildJsonObject {
                    put("title","SIConnect disposable notification test")
                    put("text","Delivery test with controlled fixture readings. This is not a device health warning.")
                    put("deduplicationKey",tag)
                }),Origin.LOCAL_OWNER)
                assertEquals(r.errorMessage,RequestState.SUCCEEDED,r.state)
                val output=r.result!!.output.jsonObject
                assertTrue(output.getValue("postedObserved").jsonPrimitive.boolean)
                postedTags+=output.getValue("notificationTag").jsonPrimitive.content
                return output
            }
            val first=HealthAlertStore(db).dispatch("fixture-a","fixture-one",readings()) {tag->post(name+tag)}
            val firstTag=first.getValue("notificationTag").jsonPrimitive.content
            assertTrue(nm.activeNotifications.any {it.tag==firstTag})
            val second=HealthAlertStore(db).dispatch("fixture-a","fixture-two",readings()) {error("Persisted cooldown did not suppress the second post")}
            assertFalse(second.getValue("postedObserved").jsonPrimitive.boolean)
            assertEquals("COOLDOWN",second.getValue("healthAlert").jsonObject.getValue("reason").jsonPrimitive.content)
            val other=HealthAlertStore(db).dispatch("fixture-b","fixture-three",readings()) {tag->post(name+tag)}
            assertNotEquals(firstTag,other.getValue("notificationTag").jsonPrimitive.content)
            nm.cancel(firstTag,0);nm.cancel(other.getValue("notificationTag").jsonPrimitive.content,0)
            assertEquals("ok",db.readableDatabase.rawQuery("PRAGMA quick_check",null).use {it.moveToFirst();it.getString(0)})
        }} finally {
            postedTags.forEach {nm.cancel(it,0)}
            context.deleteDatabase(name)
        }
    }
    @Test fun confirmedPrePostDenialDoesNotConsumeCooldown()=runBlocking {
        val name="health-denial-${UUID.randomUUID()}.db"
        try {SIConnectDb(context,name).use {db->
            try {HealthAlertStore(db).dispatch("fixture","denied",readings()) {throw CapabilityException("PERMISSION_REQUIRED","Fixture denial",noSideEffects=true)};fail("Denial ignored")}
            catch(e:CapabilityException) {assertEquals("PERMISSION_REQUIRED",e.code)}
            var entered=false
            try {HealthAlertStore(db).dispatch("fixture","next",readings()) {entered=true;throw CapabilityException("PERMISSION_REQUIRED","Fixture denial",noSideEffects=true)}}
            catch(_:CapabilityException) {}
            assertTrue("A definite denial must allow a future newly authorized observation",entered)
        }} finally {context.deleteDatabase(name)}
    }
    @Test fun versionNineUpgradePreservesRowsAndCreatesBoundedAlertState() {
        val name="health-migration-${UUID.randomUUID()}.db"
        try {
            SIConnectDb(context,name).use {db->db.writableDatabase.apply {
                execSQL("INSERT INTO agents VALUES('fixture','hash',1,NULL,'preserved')")
                execSQL("DROP TABLE health_alert_state");version=9
            }}
            repeat(2) {SIConnectDb(context,name).use {db->
                assertEquals(SIConnectDb.VERSION,db.readableDatabase.version)
                assertEquals("preserved",db.readableDatabase.rawQuery("SELECT json FROM agents WHERE agent_id='fixture'",null).use {it.moveToFirst();it.getString(0)})
                assertEquals(0,android.database.DatabaseUtils.queryNumEntries(db.readableDatabase,"health_alert_state"))
            }}
        } finally {context.deleteDatabase(name)}
    }
    @Test fun interruptedUpgradeRollsBackBeforeSuccessfulRetry() {
        val name="health-interrupted-${UUID.randomUUID()}.db"
        try {
            SIConnectDb(context,name).use {db->db.writableDatabase.apply {execSQL("DROP TABLE health_alert_state");version=9}}
            val interrupted=object:android.database.sqlite.SQLiteOpenHelper(context,name,null,10) {
                override fun onCreate(db:android.database.sqlite.SQLiteDatabase)=error("Existing fixture required")
                override fun onUpgrade(db:android.database.sqlite.SQLiteDatabase,old:Int,new:Int) {
                    db.execSQL("CREATE TABLE interrupted_fixture (id INTEGER)")
                    throw android.database.sqlite.SQLiteException("Disposable interrupted migration")
                }
            }
            try {interrupted.writableDatabase;fail("Interrupted migration accepted")} catch(_:android.database.sqlite.SQLiteException) {} finally {interrupted.close()}
            SIConnectDb(context,name).use {db->
                assertEquals(10,db.readableDatabase.version)
                assertEquals(0,db.readableDatabase.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='interrupted_fixture'",null).use {it.moveToFirst();it.getInt(0)})
                assertEquals(0,android.database.DatabaseUtils.queryNumEntries(db.readableDatabase,"health_alert_state"))
            }
        } finally {context.deleteDatabase(name)}
    }
}
