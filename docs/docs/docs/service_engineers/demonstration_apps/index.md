---
title: Demonstration Apps
description: Description of a few sample Viaduct applications demonstrating different configuration patterns
---

We have published a number of small applications that demonstrate different configurations of Viaduct.

* [`cli_starter`](https://github.com/viaduct-dev/cli_starter): bare-bones, "Hello, World!" application. A single Gradle project with a {{ kdoc("viaduct.service.api.Viaduct") }} configuration and no containing web server.

* [`jetty-starter`](https://github.com/viaduct-dev/jetty-starter): another bare-bones, "Hello, World!" application together with the GraphiQL GraphQL browser. A two-project Gradle configuration for {{ kdoc("viaduct.service.api.Viaduct") }} embedded in Jetty.

* [`ktor-starter`](https://github.com/viaduct-dev/ktor-starter): similar to `jetty-starter` but embeds into a Ktor web server.

* [`micronaut-starter`](https://github.com/viaduct-dev/micronaut-starter): also "Hello, World!" embedded in Micronaut and using Micronaut's dependency-injection framework.

* [`starwars`](https://github.com/viaduct-dev/starwars): more complete Kotlin demonstration app -- see [Star Wars tutorial](../../../getting_started/starwars/index.md) for a tour of this application.

* [`starwars-java`](https://github.com/airbnb/viaduct/tree/main/demoapps/starwars-java): Java resolvers in a Micronaut service, with two tenant modules, GraphiQL, batching, mutations and pagination. It lives in the main Viaduct repository and currently uses snapshot artifacts. Follow the Java tabs in [Getting Started](../../../getting_started/index.md) for the JDK and repository setup. The Star Wars tutorial uses matching Kotlin/Java tabs for its examples.
