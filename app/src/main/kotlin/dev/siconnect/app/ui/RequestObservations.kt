package dev.siconnect.app.ui

import dev.siconnect.core.RequestRecord
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** Subscribe before the initial SQLite read. A terminal update during that read stays queued. */
internal fun SharedFlow<RequestRecord>.refreshesFor(requestId:String)=channelFlow<Unit> {
    val observer=launch(start=CoroutineStart.UNDISPATCHED) {collect {if(it.requestId==requestId) trySend(Unit)}}
    trySend(Unit)
    awaitClose {observer.cancel()}
}.buffer(Channel.CONFLATED)
