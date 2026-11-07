package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.*
import dev.siconnect.app.termux.TermuxJournal
import dev.siconnect.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real isolated core and callback databases; no Termux execution or owner retention changes. */
class TermuxRetentionIntegrationDeviceTest {
    @Test fun optInExpiryCompactsKnownCompletionWhileProtectingLateUncertainEvidence() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val stem="termux-retention-${UUID.randomUUID()}"
        val core=SIConnectDb(context,"$stem-core.db")
        val journal=TermuxJournal(context,"$stem-callback.db")
        val now=System.currentTimeMillis();val old=now-10*86400000L
        val requests=SqlRequestStore(core)
        var policy=RetentionPolicy()
        val completed=buildJsonObject {
            put("stdout","private callback output");put("stderr","");put("exitCode",0)
            put("executionErrorCode",-1);put("commandSucceeded",true)
        }
        try {
            for((index,state) in listOf(RequestState.UNKNOWN_OUTCOME,RequestState.SUCCEEDED).withIndex()) {
                val id="execution-$index"
                assertTrue(requests.insert(RequestRecord(id,id,"test-agent","test-device","termux.execute",1,
                    buildJsonObject {put("executable","/disposable-fixture")},"hash-$id",id,"REMOTE",state,
                    createdAtMs=old,updatedAtMs=old,expiresAtMs=old+1000,operationFingerprint="fingerprint-$id")))
                journal.prepare(id,"test-agent","nonce-$id",12345,118,1000,old,old+1000,1024)
                journal.markDispatch(id,old+1)
                assertTrue(journal.receive(id,"nonce-$id",completed,old+2+index))
            }
            val retention=StorageRetention(core,{policy},{policy=it}) {cutoff,limit->
                journal.compactPayloads(cutoff,limit) {id->
                    requests.get(id)?.state?.let {it.isTerminal && it!=RequestState.UNKNOWN_OUTCOME}==true
                }
            }
            assertEquals(0,retention.maintain(now,1).total)
            assertNotNull(journal.entry("execution-1")!!.result!!["stdout"])
            retention.configure(RetentionPolicy(7))
            // A protected oldest callback must not starve a newer eligible completion.
            val expired=(1..4).sumOf {retention.maintain(now,1).termuxExecutions}
            assertEquals(1,expired)
            assertEquals(true,journal.entry("execution-1")!!.result!!["payloadExpired"]?.jsonPrimitive?.booleanOrNull)
            assertNull(journal.entry("execution-1")!!.result!!["stdout"])
            assertTrue(requests.get("execution-1")!!.payloadExpired)
            assertFalse(requests.get("execution-0")!!.payloadExpired)
            assertEquals("private callback output",journal.entry("execution-0")!!.result!!["stdout"]?.jsonPrimitive?.content)
            retention.configure(RetentionPolicy())
            assertEquals(0,retention.maintain(now+100*86400000L,1).total)
            assertNotNull(journal.entry("execution-0")!!.result!!["stdout"])
        } finally {
            journal.close();core.close();context.deleteDatabase("$stem-core.db");context.deleteDatabase("$stem-callback.db")
        }
    }
}
