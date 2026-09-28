package com.example

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Hello, World!")
        }
        get("/json/kotlinx-serialization") {
            call.respond(mapOf("hello" to "world"))
        }
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
            call.respond(
                RecommendationResponse(
                    recommendations = listOf(
                        Recommendation(
                            name = "Example Park",
                            reason = "A scenic outdoor activity that fits within the available time.",
                            durationMinutes = 90,
                        ),
                        Recommendation(
                            name = "Example Local Restaurant",
                            reason = "A nearby place for local food after the outdoor activity.",
                            durationMinutes = 60,
                        ),
                    ),
                ),
            )
        }
    }
}
