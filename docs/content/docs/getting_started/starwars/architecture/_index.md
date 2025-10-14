---
title: Architecture
description: A High-level architecture for the Viaduct Star Wars demo application.
weight: 1
---

## High-level overview

- **Runtime:** Kotlin on Spring Boot with Viaduct as the GraphQL runtime.
- **Tenancy model:** Multiple logical modules (scopes) with isolated schemas and resolvers.
- **Data layer:** In-memory data sets for demo purposes; replaceable with a persistent store.
- **Cross-cutting:** Batching, caching, and type-safe Global IDs to prevent N+1 and ensure stable references.

## Module layout

The demo is organized into three gradle modules:

- App module: The application entry point and Spring configuration. Hosts the Viaduct runtime and wires modules.

- `universe` : Schema and resolvers for the broader Star Wars domain: `Character`, `Film`, `Planet`, `Species`,
  `Starship`, and `Vehicle`.

- `filmography` : Focused schema and resolvers around characters and the films they appear in (character–film relations).

Each module is registered under its own scope and can evolve independently (schema, directives, policies).

## Schema design

- **Entities and relationships**
  The `universe` module contains schema models, core entities and their relations, such as character→films and
  character→starships.

- **Module isolation**
  The `filmography` module exposes a narrower surface area focused on character–film links, keeping
  consumer-facing contracts small and purpose-built.

## Limitations

For simplicity, the demo uses in-memory collections (for example, `StarWarsData`) with prewired relationships
(like character→film IDs). List fields accept optional `limit` arguments to cap result sizes. The data layer is
deliberately thin so you can swap it with a persistence layer (SQL/NoSQL) without changing the public schema.

This is a demo application. It uses in-memory data, no authentication/authorization, and simplified pagination.
These choices keep the architecture easy to understand while highlighting Viaduct’s core patterns.

{{< prevnext >}}
