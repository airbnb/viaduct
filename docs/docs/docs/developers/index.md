---
title: Developers
description: Developing tenant modules in Viaduct.
---

The Developers section covers the core concepts and tools you'll need when building your first Viaduct tenant. Here's what you need to know:

**Resolvers** are the heart of your Viaduct application—they're where you write the business logic that fetches and transforms data. You define fields in your GraphQL schema with the `@resolver` directive, and Viaduct generates abstract base classes that you implement. There are two types: node resolvers (for fetching objects by ID) and field resolvers (for computing field values).

**Generated Code** is your primary API for working with Viaduct. From your GraphQL schema, the framework generates GraphQL Representational Types (GRTs) with type-safe getters and builders for reading query results and constructing response objects. It also generates the resolver base classes you implement in each tenant module. See [Generated Code](generated_code/index.md) for the Kotlin and Java generation models.

**Global IDs** provide type-safe identification for Node objects. Instead of plain strings, Viaduct uses `GlobalID<T>` types that embed both the object type and its internal ID. Use the `@idOf` directive to declare when an `ID` field or argument references a specific type, and Viaduct will generate the appropriate `GlobalID` types in your resolver signatures.

**Pagination** uses the Relay Connection specification for cursor-based pagination. Define connection and edge types with `@connection` and `@edge` directives, then use builder utilities like `fromEdges()`, `fromSlice()`, or `fromList()` to construct paginated responses. Viaduct handles cursor encoding, pagination arguments, and `PageInfo` automatically.

**API Stability** Viaduct exposes public APIs that application code compiles against. To help adopters understand what is safe to depend on (and what is not), Viaduct classifies APIs into explicit stability levels using annotations.

**Scopes** let you expose different schema variants from the same codebase—think public vs. internal APIs, or feature-flagged schemas. Use the `@scope` directive to control which fields and types are visible in each variant. Either everything has a scope or nothing does; there's no default scope, ensuring explicit visibility control.

**Schema Reference** documents all the built-in directives (`@resolver`, `@backingData`, `@scope`, `@idOf`), types (like the `Node` interface), and scalars (`Date`, `DateTime`, `Long`, `BigDecimal`, `JSON`) that Viaduct provides out of the box. You'll use these constantly—especially `@resolver` for data fetching and `Node` for implementing globally identifiable entities.

Choose Kotlin or Java in the example tabs; your selection stays synchronized across the documentation. Start with [Getting Started](../../getting_started/index.md) to launch an application and write your first resolver.

Continue with the [Star Wars tutorial](../../getting_started/starwars/index.md) for a deeper worked example, then refer back to the Developers section as you build out your own tenant's schema and resolvers.
