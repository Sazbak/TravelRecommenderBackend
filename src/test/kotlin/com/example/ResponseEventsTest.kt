package com.example

import com.openai.core.http.AsyncStreamResponse
import com.openai.models.responses.ResponseStreamEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.test.*

class ResponseEventsTest {
    private class FakeStream : AsyncStreamResponse<ResponseStreamEvent> {
        val subscribed = CompletableDeferred<Unit>()
        var closed = false
        lateinit var handler: AsyncStreamResponse.Handler<ResponseStreamEvent>
        override fun subscribe(handler: AsyncStreamResponse.Handler<ResponseStreamEvent>): AsyncStreamResponse<ResponseStreamEvent> {
            this.handler = handler
            subscribed.complete(Unit)
            return this
        }
        override fun subscribe(handler: AsyncStreamResponse.Handler<ResponseStreamEvent>, executor: Executor) = subscribe(handler)
        override fun onCompleteFuture() = CompletableFuture<Void?>()
        override fun close() { closed = true }
    }

    @Test
    fun `cancellation closes the SDK stream even while waiting for an event`() = runBlocking {
        val stream = FakeStream()
        val job = launch { responseEvents { stream }.collect() }
        withTimeout(5000) { stream.subscribed.await() }
        job.cancelAndJoin()
        assertTrue(stream.closed)
    }

    @Test
    fun `SDK failure propagates and releases the stream`() = runBlocking {
        val stream = FakeStream()
        val failure = IllegalStateException("upstream failure")
        val sender = launch {
            stream.subscribed.await()
            stream.handler.onComplete(Optional.of(failure))
        }
        val actual = assertFailsWith<IllegalStateException> {
            withTimeout(5000) { responseEvents { stream }.collect() }
        }
        assertEquals(failure.message, actual.message)
        sender.join()
        assertTrue(stream.closed)
    }
}
