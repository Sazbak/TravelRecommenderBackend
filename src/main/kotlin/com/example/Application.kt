package com.example

import com.openai.client.okhttp.OpenAIOkHttpClientAsync
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.log
import java.time.Duration

fun Application.rootModule() {
    val key = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
    if (key == null) {
        log.warn("OPENAI_API_KEY is not set; valid recommendation requests will return 503")
        rootModule { throw RecommendationsUnavailable() }
        return
    }
    val client = OpenAIOkHttpClientAsync.builder()
        .apiKey(key)
        .timeout(Duration.ofSeconds(60))
        .maxRetries(0)
        .build()
    monitor.subscribe(ApplicationStopped) { client.close() }
    val service = OpenAiRecommendations { client.responses().create(it) }
    rootModule(service::recommend)
}

internal fun Application.rootModule(recommend: suspend (RecommendationRequest) -> RecommendationResponse) {
    configureSerialization()
    configureRouting(recommend)
}
