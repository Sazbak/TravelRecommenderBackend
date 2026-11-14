package com.example

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting(recommend: suspend (RecommendationRequest) -> RecommendationResponse) {
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
            val result = try {
                recommend(request)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: RecommendationsUnavailable) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Recommendations are not configured"))
                return@post
            } catch (e: Exception) {
                // Do not log SDK exception messages: they may include request/response content.
                log.warn("Recommendation generation failed: {}", e.javaClass.simpleName)
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Recommendation generation failed"))
                return@post
            }
            call.respond(result)
        }
    }
}
