---
title: Architecture
description: A High-level architecture for the Viaduct Star Wars demo application.
---


## High-level overview

- **Runtime:** Kotlin or Java on Micronaut with Viaduct as the GraphQL runtime.
- **Tenancy model:** Multiple tenant modules with separate schema and resolver ownership; scoped schemas control client visibility.
- **Data layer:** In-memory data sets for demo purposes; replaceable with a persistent store.
- **Cross-cutting:** Batching, caching, and type-safe Global IDs to prevent N+1 and ensure stable references.

## Module layout

The Java checkout uses `demoapps/starwars-java`; the Kotlin checkout uses `demoapps/starwars`. Both have the module layout below. Java sources live under `src/main/java`, and Java tenant modules apply `com.airbnb.viaduct.module-java-gradle-plugin`. The Gradle build files remain Kotlin DSL (`*.gradle.kts`) in either application.


The demo has an application project, two tenant modules, and a shared `common` library:

- App module: The application entry point and Micronaut configuration. Hosts the Viaduct runtime and wires modules.

- `universe` : Schema and resolvers for `Planet`, `Species`, `Starship`, and `Vehicle`.

- `filmography` : Schema and resolvers for `Character`, `Film`, and their relationships.

Both tenant modules contribute to the application's central schema. Scope declarations determine which fields are visible in each executable schema view; tenant ownership is separate from scope membership.

## Schema design

- **Entities and relationships** The `universe` module owns planets, species, starships, and vehicles. The `filmography` module owns characters, films, and fields that refer to entities in `universe`.

- **Module isolation** Each tenant owns its schema partition and resolver code. Their GraphQL relationships are composed through the central schema.

## Limitations

For simplicity, the demo uses in-memory collections (for example, `StarWarsData`) with prewired relationships (like character→film IDs). List fields accept optional `limit` arguments to cap result sizes. The data layer is deliberately thin so you can swap it with a persistence layer (SQL/NoSQL) without changing the public schema.

This is a demo application. It uses in-memory data, a simplified demo authorization check, and simplified pagination. These choices keep the architecture easy to understand while highlighting Viaduct’s core patterns.

> For a guided tour of the Viaduct architecture and how the runtime, build system, and tenant layers fit together, see the [Contributors Architecture overview](../../../docs/contributors/architecture/index.md).
