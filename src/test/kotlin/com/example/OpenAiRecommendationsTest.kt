package com.example

import com.openai.core.JsonValue
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseStreamEvent
import com.openai.models.responses.ResponseCompletedEvent
import com.openai.models.responses.ResponseTextDeltaEvent
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.encodeToString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class OpenAiRecommendationsTest {
    private val request = RecommendationRequest(47.4979, 19.0402, "2026-11-11T14:00:00+01:00", 180, "Find a scenic walk and local food")
    private val finalJson = """{"recommendations":[{"name":"Danube Promenade","reason":"Local fixture scenic walk","durationMinutes":45}]}"""
    private val arguments = """{"query":"scenic walk","latitude":47.4979,"longitude":19.0402}"""

    private fun response(id: String, output: List<Map<String, Any>>, status: String = "completed"): Response =
        JsonValue.from(mapOf("id" to id, "status" to status, "output" to output)).convert(Response::class.java)!!

    private fun call(id: String, args: String = arguments, name: String = "search_places") = mapOf<String, Any>(
        "type" to "function_call", "id" to "fc_$id", "call_id" to id, "name" to name, "arguments" to args,
    )

    private fun finalResponse(text: String = finalJson) = response("resp_final", listOf(mapOf(
        "type" to "message", "id" to "msg_1", "role" to "assistant", "status" to "completed",
        "content" to listOf(mapOf("type" to "output_text", "text" to text, "annotations" to emptyList<Any>())),
    )))

    private fun delta(text: String): ResponseStreamEvent = ResponseStreamEvent.ofOutputTextDelta(
        ResponseTextDeltaEvent.builder().delta(text).itemId("msg_1")
            .outputIndex(0).contentIndex(0).sequenceNumber(0).logprobs(emptyList()).build(),
    )

    private fun events(reply: Response): Flow<ResponseStreamEvent> = flow {
        reply.output().filter { it.isMessage() }.flatMap { it.asMessage().content() }
            .filter { it.isOutputText() }.forEach { part ->
                // Exercise arbitrary chunk boundaries, including inside JSON strings.
                part.asOutputText().text().chunked(3).forEach { emit(delta(it)) }
            }
        emit(ResponseStreamEvent.ofCompleted(ResponseCompletedEvent.builder().response(reply).sequenceNumber(1).build()))
    }

    @Test
    fun `HTTP delivers first recommendation while OpenAI is still generating`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val service = OpenAiRecommendations {
            flow {
                // Withhold both the array terminator and response.completed until the client reads a line.
                emit(delta(finalJson.removeSuffix("]}")))
                release.await()
                emit(delta("]}"))
                emit(ResponseStreamEvent.ofCompleted(
                    ResponseCompletedEvent.builder().response(finalResponse()).sequenceNumber(1).build(),
                ))
            }
        }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { rootModule(service::recommend) }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            HttpClient.newHttpClient().use { client ->
                val httpRequest = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/recommendations"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(request))).build()
                val response = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .get(5, TimeUnit.SECONDS)
                assertEquals(200, response.statusCode())
                response.body().bufferedReader().use { reader ->
                    val first = CompletableFuture.supplyAsync { reader.readLine() }.get(5, TimeUnit.SECONDS)
                    assertEquals("Danube Promenade", Json.decodeFromString<Recommendation>(first).name)
                    assertFalse(release.isCompleted)
                    release.complete(Unit)
                    assertEquals("{\"done\":true}", reader.readLine())
                    assertNull(reader.readLine())
                }
            }
        } finally {
            release.complete(Unit)
            server.stop(0, 1000)
        }
    }

    @Test
    fun `escaped strings survive single character deltas`() = runBlocking {
        val expected = Recommendation("Árvíz \"park\"", "Braces {} [] and slash \\ with newline\nsecond line", 30)
        val text = Json.encodeToString(RecommendationResponse(listOf(expected)))
        val service = OpenAiRecommendations {
            flow {
                text.forEach { emit(delta(it.toString())) }
                emit(ResponseStreamEvent.ofCompleted(
                    ResponseCompletedEvent.builder().response(finalResponse(text)).sequenceNumber(1).build(),
                ))
            }
        }
        assertEquals(listOf(expected), service.recommend(request).toList())
    }

    @Test
    fun `missing completion and malformed tails fail after partial output`() = runBlocking {
        for (tail in listOf("", ",]}", "]}garbage")) {
            val received = mutableListOf<Recommendation>()
            val service = OpenAiRecommendations { flow { emit(delta(finalJson.removeSuffix("]}") + tail)) } }
            assertFails { service.recommend(request).collect { received += it } }
            assertEquals(1, received.size)
        }
    }

    @Test
    fun `cumulative duration is checked before emitting the next recommendation`() = runBlocking {
        val item = finalJson.substringAfter('[').substringBeforeLast(']')
        val text = "{\"recommendations\":[$item,$item]}"
        val received = mutableListOf<Recommendation>()
        val service = OpenAiRecommendations { events(finalResponse(text)) }
        assertFails { service.recommend(request.copy(availableMinutes = 60)).collect { received += it } }
        assertEquals(1, received.size)
    }

    @Test
    fun `tool calls are answered with matching ids and continued context`() = runBlocking {
        val requests = mutableListOf<ResponseCreateParams>()
        val replies = ArrayDeque(listOf(
            response("resp_1", listOf(call("a"), call("b"))),
            response("resp_2", listOf(call("c"))),
            finalResponse(),
        ))
        val service = OpenAiRecommendations {
            requests += it
            events(replies.removeFirst())
        }
        assertEquals("Danube Promenade", service.recommend(request).toList().single().name)
        assertEquals(3, requests.size)
        assertEquals(request, Json.decodeFromString<RecommendationRequest>(requests[0].input().get().asText()))
        assertEquals("gpt-6.1-sol", requests[0].model().get().asString())
        requests.forEach {
            assertEquals(1, it.tools().get().size)
            assertEquals("search_places", it.tools().get().single().asFunction().name())
            assertEquals("auto", it.toolChoice().get().asOptions().toString())
        }
        assertEquals("resp_1", requests[1].previousResponseId().get())
        assertEquals("resp_2", requests[2].previousResponseId().get())
        val outputs = requests[1].input().get().asResponse().map { it.asFunctionCallOutput() }
        assertEquals(listOf("a", "b"), outputs.map { it.callId().get() })
        val fixture = Json.decodeFromString<PlaceSearchResult>(outputs.first().output().asString())
        assertEquals("scenic walk", fixture.search.query)
        assertEquals(47.4979, fixture.search.latitude)
        assertEquals(3, fixture.candidates.size)
    }

    @Test
    fun `direct final output does not invent a tool call`() = runBlocking {
        var count = 0
        val service = OpenAiRecommendations { count++; events(finalResponse()) }
        assertEquals(1, service.recommend(request).toList().size)
        assertEquals(1, count)
    }

    @Test
    fun `invalid tool arguments return an error to the model`() = runBlocking {
        for (args in listOf("{", """{"query":"","latitude":91,"longitude":19}""")) {
            val requests = mutableListOf<ResponseCreateParams>()
            val service = OpenAiRecommendations {
                requests += it
                events(if (requests.size == 1) response("r", listOf(call("bad", args))) else finalResponse())
            }
            service.recommend(request).toList()
            val output = requests.last().input().get().asResponse().single().asFunctionCallOutput()
            assertEquals("bad", output.callId().get())
            assertTrue(output.output().asString().contains("Invalid arguments"))
        }
    }

    @Test
    fun `unknown tools incomplete responses and invalid final output fail`() = runBlocking {
        for (reply in listOf(
            response("r", listOf(call("x", name = "unknown"))),
            response("r", emptyList(), "incomplete"),
            response("r", emptyList()),
            finalResponse("not json"),
            finalResponse(finalJson.replace("45", "181")),
        )) {
            val service = OpenAiRecommendations { events(reply) }
            assertFails { service.recommend(request).toList() }
        }
    }

    @Test
    fun `tool loop is bounded`() = runBlocking {
        var count = 0
        val service = OpenAiRecommendations {
            count++
            events(response("r$count", listOf(call("c$count"))))
        }
        assertFailsWith<IllegalStateException> { service.recommend(request).toList() }
        assertEquals(OpenAiRecommendations.MAX_ROUNDS, count)
    }

    @Test
    fun `cancellation stops upstream collection`() = runBlocking {
        val closed = CompletableDeferred<Unit>()
        val service = OpenAiRecommendations {
            flow {
                try { awaitCancellation() } finally { closed.complete(Unit) }
            }
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { service.recommend(request).toList() }
        job.cancelAndJoin()
        assertTrue(closed.isCompleted)
    }

    @Test
    fun `upstream failure is propagated`() = runBlocking {
        val service = OpenAiRecommendations {
            flow { throw IllegalStateException("upstream failed") }
        }
        assertFailsWith<IllegalStateException> { service.recommend(request).toList() }
        Unit
    }
}
