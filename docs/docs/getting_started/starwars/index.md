---
title: Star Wars Demo Application
description: Explore an advanced Viaduct application.
---

## Overview
This application implements a comprehensive GraphQL API for the Star Wars universe, demonstrating how Viaduct handles complex data relationships, advanced resolver patterns, and sophisticated schema design.

## Requirements

- JDK 17+ for Kotlin, or JDK 21+ for the Java demo, and `git` (see [Project Setup](../setup/index.md)).
- A clone of the demo for your chosen language, using the commands below.
- Linux, Mac, or Windows

## What you'll find

Both language versions showcase the following patterns. Select a language tab on any page; the selection carries across this tutorial:

- **[Global IDs](core/global_id.md)** — Viaduct's identity model and how `Node` types are referenced.
- **[Node Resolvers](core/node_resolvers.md)** — direct object resolution by Global ID.
- **[Field Resolvers](core/field_resolvers.md)** — field-level computation and lightweight lookups.
- **[Batch Resolvers](core/batch_resolvers.md)** — efficient bulk data loading.
- **[Resolver Integration Patterns](core/resolver_integrations.md)** — how the layers compose at execution time.
- **[Mutations](mutations/index.md)** — modifying data through GraphQL mutations.
- **[Variables](variables/index.md)** — dynamic variable provision.
- **[Backing Data](directives/backing_data.md)** — sharing pre-fetched data between sibling resolvers via `@backingData`.

## Getting the Star Wars application

=== "Kotlin"

    ```shell
    git clone https://github.com/viaduct-dev/starwars.git
    cd starwars
    ```

=== "Java"

    The Java demo is part of the main Viaduct repository. It uses Micronaut and the Java tenant API.

    ```shell
    git clone https://github.com/airbnb/viaduct.git
    cd viaduct/demoapps/starwars-java
    ```


## Running the application

Follow the instructions in the repository's README to build and run the application:

=== "Kotlin"

    ```shell
    ./gradlew test
    ./gradlew run
    ```

=== "Java"

    The demo currently uses `2.1.0-SNAPSHOT`. The environment flag enables the snapshot repository for both plugins and dependencies. On Windows, use Git Bash for these commands.

    ```shell
    USE_VIADUCT_SNAPSHOT_REPO=true ./gradlew test
    USE_VIADUCT_SNAPSHOT_REPO=true ./gradlew run
    ```

    Open [GraphiQL](http://localhost:8080/graphiql) and try:

    ```graphql
    query {
      allCharacters(limit: 1) { name displayName }
    }
    ```

    The first character is Luke Skywalker. Stop the server with `Ctrl+C`.


After exploring the Star Wars application, you'll have a solid understanding of how to build production-ready GraphQL applications with Viaduct.

## What's Next

Continue to [Core Concepts](core/index.md) to walk through Viaduct's foundational building blocks as they appear in this demo.

## Related resources

- [Viaduct Documentation](../../index.md)
- [GitHub Repository](https://github.com/viaduct-dev)


