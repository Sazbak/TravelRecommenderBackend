package com.example

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecommendationTest {
    private val expectedResponse = RecommendationResponse(listOf(
        Recommendation("Example Park", "A scenic outdoor activity that fits within the available time.", 90),
        Recommendation("Example Local Restaurant", "A nearby place for local food after the outdoor activity.", 60),
    ))

    private val validRequest = RecommendationRequest(
        latitude = 47.4979,
        longitude = 19.0402,
        startDateTime = "2026-09-28T14:00:00+02:00",
        availableMinutes = 180,
        request = "I want something scenic and outdoors, but not too strenuous. I'd also like to eat some good local food afterwards.",
    )

    @Test
    fun `valid request returns one recommendation per NDJSON line`() = testApplication {
        application { rootModule { expectedResponse.recommendations.asFlow() } }
        val response = client.post("/recommendations") {
            contentType(ContentType.Application.Json)
            accept(ContentType.parse("application/x-ndjson"))
            setBody(Json.encodeToString(validRequest))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.parse("application/x-ndjson"), response.contentType()?.withoutParameters())
        val body = response.bodyAsText()
        assertTrue(body.endsWith("\n"))
        val lines = body.removeSuffix("{\"done\":true}\n").removeSuffix("\n").split('\n')
        assertEquals(expectedResponse.recommendations.size, lines.size)
        assertEquals(expectedResponse.recommendations, lines.map { Json.decodeFromString<Recommendation>(it) })
    }

    @Test
    fun `empty recommendations return only the completion marker`() = testApplication {
        application { rootModule { emptyList<Recommendation>().asFlow() } }
        val response = client.post("/recommendations") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validRequest))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.parse("application/x-ndjson"), response.contentType()?.withoutParameters())
        assertEquals("{\"done\":true}\n", response.bodyAsText())
    }

    @Test
    fun `newlines and special characters stay within one JSON record`() = testApplication {
        val recommendation = Recommendation("Park \"A\"", "First line\nSecond line\r\nTab\tBackslash \\ Budapest: árvíz", 30)
        application { rootModule { listOf(recommendation).asFlow() } }
        val response = client.post("/recommendations") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validRequest))
        }
        val body = response.bodyAsText()
        assertEquals(2, body.count { it == '\n' })
        assertTrue(body.endsWith("\n"))
        assertEquals(recommendation, Json.decodeFromString<Recommendation>(body.substringBefore('\n')))
    }

    @Test
    fun `failure after first recommendation emits sanitized error without completion marker`() = testApplication {
        application {
            rootModule {
                flow {
                    emit(expectedResponse.recommendations.first())
                    error("private upstream details")
                }
            }
        }
        val response = client.post("/recommendations") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validRequest))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            Json.encodeToString(expectedResponse.recommendations.first()) +
                "\n{\"error\":\"Recommendation generation failed\"}\n",
            response.bodyAsText(),
        )
    }

    @Test
    fun `invalid latitude returns bad request`() {
        assertBadRequests(listOf(-90.1, 90.1).map { validRequest.copy(latitude = it) })
    }

    @Test
    fun `invalid longitude returns bad request`() {
        assertBadRequests(listOf(-180.1, 180.1).map { validRequest.copy(longitude = it) })
    }

    @Test
    fun `invalid start date time returns bad request`() {
        assertBadRequests(
            listOf("invalid", "2026-09-28T14:00:00", "2026-02-30T14:00:00+02:00")
                .map { validRequest.copy(startDateTime = it) },
        )
    }

    @Test
    fun `nonpositive available minutes returns bad request`() {
        assertBadRequests(listOf(0, -1).map { validRequest.copy(availableMinutes = it) })
    }

    @Test
    fun `blank request returns bad request`() {
        assertBadRequests(listOf("", " \t\n").map { validRequest.copy(request = it) })
    }

    @Test
    fun `coordinate boundaries and positive duration are accepted`() = testApplication {
        application { rootModule { expectedResponse.recommendations.asFlow() } }
        for (latitude in listOf(-90.0, 90.0)) {
            for (longitude in listOf(-180.0, 180.0)) {
                val response = client.post("/recommendations") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        Json.encodeToString(
                            validRequest.copy(
                                latitude = latitude,
                                longitude = longitude,
                                availableMinutes = 1,
                                startDateTime = "2026-09-28T12:00:00Z",
                            ),
                        ),
                    )
                }
                assertEquals(HttpStatusCode.OK, response.status)
            }
        }
    }

    @Test
    fun `malformed JSON and missing fields return bad request`() = testApplication {
        application { rootModule { expectedResponse.recommendations.asFlow() } }
        for (body in listOf("{", "{}")) {
            val response = client.post("/recommendations") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `upstream failure returns sanitized bad gateway`() = testApplication {
        application { rootModule { error("private upstream details") } }
        val response = client.post("/recommendations") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validRequest))
        }
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("""{"error":"Recommendation generation failed"}""", response.bodyAsText())
    }

    @Test
    fun `missing configuration returns service unavailable`() = testApplication {
        application { rootModule { throw RecommendationsUnavailable() } }
        val response = client.post("/recommendations") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validRequest))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    private fun assertBadRequests(requests: List<RecommendationRequest>) = testApplication {
        application { rootModule { throw AssertionError("Invalid input must not reach the service") } }
        for (request in requests) {
            val response = client.post("/recommendations") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(request))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "Request: $request")
        }
    }
}
