package dev.siconnect.app.transport

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.WebSocket
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

class RevocationReplayTest {
    @Test fun failedReceiptReplaysFromLastAcknowledgedPage() = runTest {
        val replay = RevocationReplay().apply { enabled = true }
        val receipts = GatewayReceipts()
        val socket = Mockito.mock(WebSocket::class.java)
        val ids = (1L..503L).map { it to "old-agent-$it" }
        val sent = mutableListOf<List<String>>()
        var loseReceipt = true
        Mockito.`when`(socket.send(anyString())).thenAnswer {
            val frame = Json.parseToJsonElement(it.getArgument<String>(0)).jsonObject
            sent.add(frame.getValue("agents").jsonArray.map { entry -> entry.jsonPrimitive.content })
            if (sent.size != 2 || !loseReceipt) {
                receipts.acknowledge("revocations:" + frame.getValue("id").jsonPrimitive.content)
            }
            true
        }
        fun load(after: Long) = ids.filter { it.first > after }.take(64)
        replay.drain(socket, receipts, ::load)
        Mockito.verify(socket).cancel()
        loseReceipt = false
        replay.drain(socket, receipts, ::load)
        assertEquals(sent[1], sent[2])
        assertEquals(ids.map { it.second }, (sent.take(1) + sent.drop(2)).flatten())
        val total = sent.size
        replay.drain(socket, receipts, ::load)
        assertEquals(total, sent.size)
    }
}
