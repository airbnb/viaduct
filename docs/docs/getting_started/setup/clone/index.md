---
title: Clone a Starter Application
description: Get going by cloning the CLI starter, the simplest Viaduct starter application.
---


This guide walks you through getting started with Viaduct by cloning the **CLI starter** — the smallest starter application. We use it because it's quick to clone, has no web framework to set up, and exercises the same core Viaduct APIs you'd use in a full server.

## Run the CLI starter

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

## Other starter applications

Besides the CLI starter, [github.com/viaduct-dev](https://github.com/viaduct-dev) hosts several framework-specific starters:

- [`cli-starter`](https://github.com/viaduct-dev/cli-starter) — the smallest one; covered above.
- [`ktor-starter`](https://github.com/viaduct-dev/ktor-starter) — a Ktor-based HTTP server.
- [`jetty-starter`](https://github.com/viaduct-dev/jetty-starter) — a Jetty-based HTTP server.
- [`micronaut-starter`](https://github.com/viaduct-dev/micronaut-starter) — a Micronaut-based HTTP server.
- [`starwars`](https://github.com/viaduct-dev/starwars) — a fuller demonstration application built on Spring Boot, exercising most Viaduct features. The [Star Wars tutorial](../../starwars/index.md) walks through it.

Pick the one that matches your target framework. Each repo's README has its own setup steps.

## What's Next

Continue to [Touring the Application](../../tour/index.md) to understand the structure of a Viaduct application.
