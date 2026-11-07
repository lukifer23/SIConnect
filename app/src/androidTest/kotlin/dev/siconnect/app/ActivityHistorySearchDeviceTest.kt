package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.SIConnectDb
import dev.siconnect.app.data.SqlRequestStore
import dev.siconnect.core.RequestRecord
import dev.siconnect.core.RequestState
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real disposable SQLite; never changes the owner's history or retention setting. */
class ActivityHistorySearchDeviceTest {
    private fun isolated(block:(SIConnectDb,SqlRequestStore)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="activity-search-${UUID.randomUUID()}.db"
        try {SIConnectDb(context,name).use {block(it,SqlRequestStore(it))}}
        finally {context.deleteDatabase(name)}
    }
    private fun record(id:String,agent:String="agent-a",capability:String="device.status",state:RequestState=RequestState.SUCCEEDED)=
        RequestRecord(id,id,agent,"disposable-device",capability,1,JsonObject(emptyMap()),"hash-$id",id,"REMOTE",state,
            createdAtMs=1,updatedAtMs=2,expiresAtMs=3)

    @Test fun searchRunsBeforePageLimitAndIncludesCompactedMetadataWithoutDecodingPayloads()=isolated {db,store->
        repeat(105) {assertTrue(store.insert(record("z-$it")))}
        assertTrue(store.insert(record("a-old-result",capability="legacy.probe")))
        assertEquals(1,store.prune(3,1))
        assertFalse(store.historyPage(100).any {it.requestId=="a-old-result"})
        // A history projection must remain usable even if an unrelated payload needs diagnosis.
        db.writableDatabase.execSQL("UPDATE requests SET json='unreadable-payload'")
        db.writableDatabase.execSQL("UPDATE request_tombstones SET json='unreadable-payload'")
        val result=store.historyPage(limit=1,search="old-result").single()
        assertEquals("a-old-result",result.requestId);assertTrue(result.payloadExpired)
        assertEquals(setOf("device.status","legacy.probe"),store.historyCapabilityIds())
        assertEquals("a-old-result",store.historyPage(search="Friendly probe",matchingCapabilities=setOf("legacy.probe")).single().requestId)
    }

    @Test fun literalSearchFriendlyMetadataFiltersAndTieCursorsComposeInBothDirections()=isolated {_,store->
        val ids=listOf("literal%_one","literal%_two","ordinary","quote' OR 1=1 --")
        ids.forEach {assertTrue(store.insert(record(it,capability="custom.action",state=RequestState.FAILED)))}
        assertTrue(store.insert(record("different-agent",agent="agent-b",state=RequestState.FAILED)))
        assertTrue(store.insert(record("completed",state=RequestState.SUCCEEDED)))
        assertEquals(ids.take(2).toSet(),store.historyPage(search="%_").map {it.requestId}.toSet())
        assertEquals(listOf(ids.last()),store.historyPage(search="' OR 1=1 --").map {it.requestId})
        for(newest in listOf(true,false)) {
            val first=store.historyPage(2,agentId="agent-a",states=setOf(RequestState.FAILED),newest=newest,
                search="Friendly bot",matchingAgents=setOf("agent-a"))
            val second=store.historyPage(2,cursor=first.last(),agentId="agent-a",states=setOf(RequestState.FAILED),newest=newest,
                search="Friendly bot",matchingAgents=setOf("agent-a"))
            assertEquals(ids.sorted().let {if(newest) it.reversed() else it},(first+second).map {it.requestId})
        }
        assertEquals(setOf("different-agent")+ids,store.historyPage(search="Blocked",matchingStates=setOf(RequestState.FAILED)).map {it.requestId}.toSet())
        assertTrue(store.historyPage(search="Friendly bot",matchingAgents=setOf("agent-a"),agentId="agent-b").isEmpty())
        assertEquals(6,store.historyPage(search=" ").size)
    }
}
