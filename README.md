# TravelRecommenderBackend

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:

* [Ktor Documentation](https://ktor.io/docs/home.html)
* [Ktor GitHub page](https://github.com/ktorio/ktor)
* [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). [Request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up).

## Features

Here's a list of features included in this project:

| Name                                                                                  | Description                                                                        |
|---------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| [Content Negotiation](https://start.ktor.io/p/io.ktor/server-content-negotiation)     | Provides automatic content conversion according to Content-Type and Accept headers |
| [kotlinx.serialization](https://start.ktor.io/p/io.ktor/server-kotlinx-serialization) | Handles JSON serialization using kotlinx.serialization library                     |

## Building & Running

This project uses Gradle 9.1.0 with Kotlin DSL and requires JDK 25.
Set `JAVA_HOME` to your JDK 25 installation and ensure `$JAVA_HOME/bin` is on `PATH`.
The Gradle Wrapper downloads Gradle automatically; no separate Gradle installation is needed.

To build or run the project, use one of the following tasks:

| Task             | Description       |
|------------------|-------------------|
| `./gradlew test`  | Run the tests     |
| `./gradlew build` | Build the project |
| `./gradlew run`   | Run the server    |

On Windows, use `gradlew.bat` instead of `./gradlew`.

Sources are in `src/main/kotlin`, resources in `src/main/resources`, and tests in
`src/test/kotlin`. Dependency and Kotlin plugin versions are defined in
`gradle/libs.versions.toml`.

The migration preserves the old build's resolved Kotlin 2.4.10, Ktor 3.5.2,
Logback 1.5.37, and JUnit 5.10.1 versions. The old catalog listed Kotlin 2.4.0,
but the Kotlin Toolchain actually compiled and resolved Kotlin 2.4.10.

If the server starts successfully, you'll see the following output:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

## IntelliJ IDEA

Open `settings.gradle.kts` as a project, or link it from the Gradle tool window
if the directory is already open. Select the Gradle Wrapper and set the Gradle
JVM to JDK 25, then reload the Gradle project. Gradle import configures the source
roots, Kotlin support, and Ktor dependencies; no manual source-root setup is needed.

## OpenAI recommendation tool loop

The existing `POST /recommendations` request and response JSON are unchanged.
Valid requests use the official OpenAI Java SDK async Responses API with
`gpt-6.1-sol`. Exactly one function tool, `search_places(query, latitude, longitude)`,
is exposed with automatic tool choice. Only API-returned function calls execute
the local Kotlin function. Each result is sent as `function_call_output` with
the original `call_id`; `previous_response_id` preserves conversation/reasoning
context. The tool and instructions are supplied on each turn. Final output uses
a strict JSON schema matching `RecommendationResponse`.

The search implementation returns three hardcoded Budapest fixtures regardless
of query/location, explicitly marked as fake. It does not check hours, travel
times or availability. This slice verifies tool orchestration, not search quality.

Set the key **only in the backend process environment**. In zsh, enter it without
putting the key in shell history:

```sh
read -rs 'OPENAI_API_KEY?OpenAI API key: '; echo
export OPENAI_API_KEY
./gradlew run
```

In IntelliJ, set `OPENAI_API_KEY` in the local run configuration's environment;
do not share/commit that configuration. Never place it in Android code.
No dotenv file is loaded. After use, `unset OPENAI_API_KEY` removes the variable
from the shell. The SDK is configured explicitly from this variable; no alternate
key or base URL environment variables are consumed.

In another terminal:

```sh
curl -i http://localhost:8080/recommendations \
  -H 'Content-Type: application/json' \
  -d '{
    "latitude": 47.4979,
    "longitude": 19.0402,
    "startDateTime": "2026-11-12T14:00:00+01:00",
    "availableMinutes": 180,
    "request": "Please use search_places to find a scenic easy riverside walk and Hungarian food near my coordinates. Base your recommendations on its candidates and disclose that these are demo results."
  }'
```

Tool choice remains automatic: this prompt encourages a real tool call but does
not force one in code. Look for these INFO log events:

- `OpenAI function_call response_id=... call_id=... name=search_places`:
  the API actually returned a structured function call.
- `OpenAI function_call_output prepared call_id=... source=local_fake`:
  its result is prepared for the next Responses request.
- `OpenAI final response_id=... tool_calls=N`: the loop reached a final answer.
  A value of zero means the model answered directly; that is not a tool-call test success.

Set breakpoints in `OpenAiRecommendations.recommend` at `call.arguments()`,
`searchPlaces(...)`, and `create(params.build())` to inspect the model's arguments
and the following request's matching call IDs and previous response ID.
Prompts, coordinates, API keys and raw SDK exception messages are not logged.

Requests are bounded to six Responses rounds with a 60-second SDK timeout per
round and SDK retries disabled. Coroutine cancellation cancels the pending future.
Invalid tool arguments are returned as tool errors so the model can correct them.
Missing configuration returns HTTP 503; upstream failures, refusals, incomplete
responses, invalid final output and exhausted rounds return sanitized HTTP 502.
Existing invalid request handling remains HTTP 400. There is no fallback to
hardcoded endpoint recommendations. Responses use `store=true` for server-side
continuation; this implementation does not support a zero-retention configuration.

Run `./gradlew test` and `./gradlew build`. Tests use scripted SDK responses and
injected endpoint handlers without credentials or calls to OpenAI. A successful
offline test does not establish account access to the model: use the manual
request and log sequence above to verify the live integration.
