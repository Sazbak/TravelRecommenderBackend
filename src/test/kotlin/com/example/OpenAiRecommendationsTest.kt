package com.example

import com.openai.core.JsonValue
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.util.concurrent.CompletableFuture
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
            CompletableFuture.completedFuture(replies.removeFirst())
        }
        assertEquals("Danube Promenade", service.recommend(request).recommendations.single().name)
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
        val service = OpenAiRecommendations { count++; CompletableFuture.completedFuture(finalResponse()) }
        assertEquals(1, service.recommend(request).recommendations.size)
        assertEquals(1, count)
    }

    @Test
    fun `invalid tool arguments return an error to the model`() = runBlocking {
        for (args in listOf("{", """{"query":"","latitude":91,"longitude":19}""")) {
            val requests = mutableListOf<ResponseCreateParams>()
            val service = OpenAiRecommendations {
                requests += it
                CompletableFuture.completedFuture(if (requests.size == 1) response("r", listOf(call("bad", args))) else finalResponse())
            }
            service.recommend(request)
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
            val service = OpenAiRecommendations { CompletableFuture.completedFuture(reply) }
            assertFails { service.recommend(request) }
        }
    }

    @Test
    fun `tool loop is bounded`() = runBlocking {
        var count = 0
        val service = OpenAiRecommendations {
            count++
            CompletableFuture.completedFuture(response("r$count", listOf(call("c$count"))))
        }
        assertFailsWith<IllegalStateException> { service.recommend(request) }
        assertEquals(OpenAiRecommendations.MAX_ROUNDS, count)
    }

    @Test
    fun `cancellation cancels pending SDK future`() = runBlocking {
        val pending = CompletableFuture<Response>()
        val service = OpenAiRecommendations { pending }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { service.recommend(request) }
        job.cancelAndJoin()
        assertTrue(pending.isCancelled)
    }

    @Test
    fun `upstream failure is propagated`() = runBlocking {
        val service = OpenAiRecommendations {
            CompletableFuture.failedFuture<Response>(IllegalStateException("upstream failed"))
        }
        assertFailsWith<IllegalStateException> { service.recommend(request) }
        Unit
    }
}
