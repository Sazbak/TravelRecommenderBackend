package com.example

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RecommendationTest {
    private val validRequest = RecommendationRequest(
        latitude = 47.4979,
        longitude = 19.0402,
        startDateTime = "2026-09-28T14:00:00+02:00",
        availableMinutes = 180,
        request = "I want something scenic and outdoors, but not too strenuous. I'd also like to eat some good local food afterwards.",
    )

    @Test
    fun `valid request returns expected recommendations`() = testApplication {
        application { rootModule() }
        val response = client.post("/recommendations") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validRequest))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        val body = Json.decodeFromString<RecommendationResponse>(response.bodyAsText())
        assertEquals(
            listOf(
                Recommendation("Example Park", "A scenic outdoor activity that fits within the available time.", 90),
                Recommendation("Example Local Restaurant", "A nearby place for local food after the outdoor activity.", 60),
            ),
            body.recommendations,
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
        application { rootModule() }
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
        application { rootModule() }
        for (body in listOf("{", "{}")) {
            val response = client.post("/recommendations") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    private fun assertBadRequests(requests: List<RecommendationRequest>) = testApplication {
        application { rootModule() }
        for (request in requests) {
            val response = client.post("/recommendations") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(request))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "Request: $request")
        }
    }
}
