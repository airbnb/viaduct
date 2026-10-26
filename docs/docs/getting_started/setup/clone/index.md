---
title: Clone a Starter Application
description: Clone a Kotlin starter or the Java Star Wars application.
---


Choose a language below to clone and run an existing Viaduct application. The Kotlin path uses the small CLI starter. The Java path uses the complete Micronaut Star Wars demo in the main Viaduct repository.

## Run the starter

=== "Kotlin"

    Make a local clone of the [CLI starter](https://github.com/viaduct-dev/cli-starter):

    ```shell
    git clone https://github.com/viaduct-dev/cli-starter.git
    ```

    `cd` into the clone and verify your environment:

    ```shell
    ./gradlew test
    ```

    Gradle should report that the build was successful.

    Although Viaduct is typically hosted in a web server, the CLI starter calls it directly from the application's `main` function so the example stays small. Run a query through Gradle:

    ```shell
    ./gradlew -q run --args="'{ greeting }'"
    ```

    The starter ships with a tiny schema:

    ```graphql
    type Query {
       greeting: String @resolver
       author: String @resolver
    }
    ```

    Any query that's valid against this schema can be passed via `--args`.

=== "Java"

    Install JDK 21+ and clone the main repository at the 3.0.0 release:

    ```shell
    git clone --branch v3.0.0 https://github.com/airbnb/viaduct.git
    cd viaduct/demoapps/starwars-java
    ```

    Verify the application before starting the server:

    ```shell
    ./gradlew test
    ./gradlew run
    ```

    On Windows, run these commands in Git Bash.

    Open [GraphiQL](http://localhost:8080/graphiql) and execute:

    ```graphql
    query {
      allCharacters(limit: 1) {
        name
        displayName
      }
    }
    ```

    The response includes Luke Skywalker. The server exposes `/graphql` for HTTP requests and `/graphiql` for the browser. Stop it with `Ctrl+C`.

## Other starter applications

Besides the CLI starter, [github.com/viaduct-dev](https://github.com/viaduct-dev) hosts several framework-specific starters:

- [`cli-starter`](https://github.com/viaduct-dev/cli-starter) — the smallest one; covered above.
- [`ktor-starter`](https://github.com/viaduct-dev/ktor-starter) — a Ktor-based HTTP server.
- [`jetty-starter`](https://github.com/viaduct-dev/jetty-starter) — a Jetty-based HTTP server.
- [`micronaut-starter`](https://github.com/viaduct-dev/micronaut-starter) — a Micronaut-based HTTP server.
- [`starwars`](https://github.com/viaduct-dev/starwars) — a fuller demonstration application built on Micronaut, exercising most Viaduct features. The [Star Wars tutorial](../../starwars/index.md) walks through it.

For Java, continue with `demoapps/starwars-java` in the main repository; the standalone starter repositories listed above use Kotlin.

Pick the one that matches your target framework. Each repo's README has its own setup steps.

## What's Next

Continue to [Touring the Application](../../tour/index.md) to understand the structure of a Viaduct application.
