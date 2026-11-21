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
                store.delete("active")
                assertEquals((2..501).map { "remote-$it" }, store.revokedRemoteIds())
            }
        } finally { context.deleteDatabase(file) }
    }
}
