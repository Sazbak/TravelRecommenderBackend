package com.example

import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

@Serializable
data class RecommendationRequest(
    val latitude: Double,
    val longitude: Double,
    val startDateTime: String,
    val availableMinutes: Int,
    val request: String,
)

@Serializable
data class Recommendation(
    val name: String,
    val reason: String,
    val durationMinutes: Int,
)

@Serializable
data class RecommendationResponse(
    val recommendations: List<Recommendation>,
)

internal fun RecommendationRequest.validationError(): String? {
    if (latitude !in -90.0..90.0) return "latitude must be between -90 and 90"
    if (longitude !in -180.0..180.0) return "longitude must be between -180 and 180"
    try {
        OffsetDateTime.parse(startDateTime)
    } catch (_: DateTimeParseException) {
        return "startDateTime must be a valid ISO-8601 offset date-time"
    }
    if (availableMinutes <= 0) return "availableMinutes must be greater than 0"
    if (request.isBlank()) return "request must not be blank"
    return null
}
