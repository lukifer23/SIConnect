package dev.siconnect.app.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.WebSocket
import java.util.UUID

/** Replays durable identities in bounded pages. A new socket starts at the beginning. */
internal class RevocationReplay {
    @Volatile var enabled = false
    private val lock = Mutex()
    private var acknowledged = 0L

    suspend fun drain(socket: WebSocket, receipts: GatewayReceipts, load: (Long) -> List<Pair<Long, String>>) {
        if (!enabled) return
        lock.withLock {
            try {
                while (true) {
                    val page = load(acknowledged)
                    if (page.isEmpty()) return
                    val id = UUID.randomUUID().toString()
                    receipts.awaitReceipt("revocations:$id") {
                        socket.send(buildJsonObject {
                            put("type", "revocations"); put("id", id)
                            put("agents", JsonArray(page.map { JsonPrimitive(it.second) }))
                        }.toString())
                    }
                    acknowledged = page.last().first
                }
            } catch (_: TimeoutCancellationException) { socket.cancel() }
              catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { socket.cancel() }
        }
    }
}
