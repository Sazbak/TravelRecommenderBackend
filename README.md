# ktor-sample

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
