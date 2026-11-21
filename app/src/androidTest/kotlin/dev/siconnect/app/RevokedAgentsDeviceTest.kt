package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.*
import dev.siconnect.core.AgentGrant
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RevokedAgentsDeviceTest {
    @Test fun onlyLatestFiveHundredRevokedRemoteIdsAreAnnounced() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "revoked-agents-${UUID.randomUUID()}.db"
        try {
            SIConnectDb(context, file).use { db ->
                val store = AgentStore(db)
                fun save(id: String, age: Long, revoked: Boolean) {
                    store.put(AgentRecord(AgentGrant(id, id, revoked = revoked), id, age, tokenHint = "fixture"))
                }
                db.writableDatabase.writeTransaction {
                    for (i in 0..501) save("remote-$i", i.toLong(), true)
                    save("active", 510, false)
                    save("local-owner", 511, true)
                }
                assertEquals((2..501).map { "remote-$it" }, store.revokedRemoteIds())
                save("active", 510, true)
                assertEquals("active", store.revokedRemoteIds().last())
                // Newly revoking an identity created before the newest 500 must reach the Worker.
                save("old-account", -1, true)
                val pages = generateSequence(store.revocationsAfter(0)) { page ->
                    page.lastOrNull()?.let { store.revocationsAfter(it.first) }
                }.takeWhile { it.isNotEmpty() }.toList()
                assertTrue(pages.all { it.size <= 64 })
                assertEquals((0..501).map { "remote-$it" } + listOf("active", "old-account"), pages.flatten().map { it.second })
                val cursor = pages.last().last().first
                save("old-account", -1, true)
                assertTrue(store.revocationsAfter(cursor).isEmpty())
                store.delete("active")
                assertEquals((2..501).map { "remote-$it" }, store.revokedRemoteIds())
            }
        } finally { context.deleteDatabase(file) }
    }
}
