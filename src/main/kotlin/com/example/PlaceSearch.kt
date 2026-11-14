package com.example

import kotlinx.serialization.Serializable

@Serializable
internal data class SearchPlacesArguments(val query: String, val latitude: Double, val longitude: Double)

@Serializable
internal data class PlaceCandidate(
    val name: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
)

@Serializable
internal data class PlaceSearchResult(
    val source: String,
    val search: SearchPlacesArguments,
    val candidates: List<PlaceCandidate>,
)

// Intentionally a fixture, not a geographic search. Echo inputs so this limitation is explicit.
internal fun searchPlaces(query: String, latitude: Double, longitude: Double): PlaceSearchResult {
    require(query.isNotBlank()) { "query must not be blank" }
    require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Invalid coordinates" }
    return PlaceSearchResult(
        source = "Local fake Budapest fixtures; not live search. Hours, travel times and availability are unknown.",
        search = SearchPlacesArguments(query, latitude, longitude),
        candidates = listOf(
            PlaceCandidate("Margaret Island", "A park with riverside paths in Budapest.", 47.5266, 19.0451),
            PlaceCandidate("Danube Promenade", "A scenic riverside walk in central Budapest.", 47.4950, 19.0480),
            PlaceCandidate("Central Market Hall", "A historic Budapest market with Hungarian food stalls.", 47.4870, 19.0585),
        ),
    )
}
