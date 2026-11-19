package com.example

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Application.configureRouting(recommend: (RecommendationRequest) -> Flow<Recommendation>) {
    routing {
        post("/recommendations") {
            val request = try {
                call.receive<RecommendationRequest>()
            } catch (_: BadRequestException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid recommendation request JSON"))
                return@post
            }
            val error = request.validationError()
            if (error != null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to error))
                return@post
            }
            coroutineScope {
                // Prefetch only one record so failures before output retain meaningful HTTP statuses.
                val records = Channel<Result<Recommendation>>(Channel.RENDEZVOUS)
                val producer = launch {
                    try {
                        recommend(request).collect { records.send(Result.success(it)) }
                    } catch (e: CancellationException) {
                        records.close(e)
                        throw e
                    } catch (e: Exception) {
                        records.send(Result.failure(e))
                    } finally {
                        records.close()
                    }
                }
                try {
                    val firstResult = records.receiveCatching()
                    firstResult.exceptionOrNull()?.let { throw it }
                    val first = firstResult.getOrNull()
                    val failure = first?.exceptionOrNull()
                    if (failure != null) {
                        if (failure is RecommendationsUnavailable) {
                            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Recommendations are not configured"))
                        } else {
                            log.warn("Recommendation generation failed: {}", failure.javaClass.simpleName)
                            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Recommendation generation failed"))
                        }
                        return@coroutineScope
                    }
                    val writerFinished = CompletableDeferred<Unit>()
                    call.respondTextWriter(contentType = ContentType.parse("application/x-ndjson")) {
                        suspend fun writeRecord(record: Result<Recommendation>): Boolean {
                            val error = record.exceptionOrNull()
                            if (error != null) {
                                log.warn("Recommendation stream failed: {}", error.javaClass.simpleName)
                                write(Json.encodeToString(mapOf("error" to "Recommendation generation failed")))
                            } else {
                                write(Json.encodeToString(record.getOrThrow()))
                            }
                            write("\n")
                            flush()
                            return error == null
                        }
                        try {
                            if (first != null) writeRecord(first)
                            for (record in records) {
                                if (!writeRecord(record)) return@respondTextWriter
                            }
                            write("{\"done\":true}\n")
                            flush()
                        } finally {
                            writerFinished.complete(Unit)
                        }
                    }
                    // Ktor may return from respond before the asynchronous writer has finished.
                    writerFinished.await()
                } finally {
                    records.cancel()
                    producer.cancel()
                }
            }
        }
    }
}
