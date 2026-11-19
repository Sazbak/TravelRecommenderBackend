package com.example

import com.openai.core.http.AsyncStreamResponse
import com.openai.models.responses.ResponseStreamEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.util.Optional

/** Bound buffering and close the SDK HTTP stream when the downstream request is cancelled. */
internal fun responseEvents(open: () -> AsyncStreamResponse<ResponseStreamEvent>): Flow<ResponseStreamEvent> =
    callbackFlow {
        val stream = open()
        try {
            stream.subscribe(object : AsyncStreamResponse.Handler<ResponseStreamEvent> {
                override fun onNext(value: ResponseStreamEvent) {
                    trySendBlocking(value).getOrThrow()
                }

                override fun onComplete(error: Optional<Throwable>) {
                    close(error.orElse(null))
                }
            })
            awaitClose { }
        } finally {
            stream.close()
        }
    }.buffer(1)
