---
title: Developers
description: Developing tenant modules in Viaduct.
---

The Developers section covers the core concepts and tools you'll need when building your first Viaduct tenant. Here's what you need to know:

**Resolvers** are the heart of your Viaduct application—they're where you write the business logic that fetches and transforms data. You define fields in your GraphQL schema with the `@resolver` directive, and Viaduct generates abstract base classes that you implement. There are two types: node resolvers (for fetching objects by ID) and field resolvers (for computing field values). Understanding *responsibility sets*—the fields each resolver is responsible for, including nested fields—is key to structuring your code efficiently.

**Generated Code** is your primary API for working with Viaduct. The framework generates GraphQL Representational Types (GRTs) for every type in your schema—these are Kotlin classes with type-safe getters and builders that let you read query results and construct response objects. The generated code uses a "compilation schema" approach, meaning each tenant module only generates types for the schema elements it actually uses, keeping builds fast and scalable.

**Global IDs** provide type-safe identification for Node objects. Instead of plain strings, Viaduct uses `GlobalID<T>` types that embed both the object type and its internal ID. Use the `@idOf` directive to declare when an ID field references a specific type, and Viaduct will generate the appropriate `GlobalID` types in your resolver signatures.

**Pagination** uses the Relay Connection specification for cursor-based pagination. Define connection and edge types with `@connection` and `@edge` directives, then use builder utilities like `fromEdges()`, `fromSlice()`, or `fromList()` to construct paginated responses. Viaduct handles cursor encoding, pagination arguments, and `PageInfo` automatically.

**Scopes** let you expose different schema variants from the same codebase—think public vs. internal APIs, or feature-flagged schemas. Use the `@scope` directive to control which fields and types are visible in each variant. Either everything has a scope or nothing does; there's no default scope, ensuring explicit visibility control.

**Schema Reference** documents all the built-in directives (`@resolver`, `@backingData`, `@scope`, `@idOf`), types (like the `Node` interface), and scalars (`Date`, `DateTime`, `Long`, `BigDecimal`, `JSON`) that Viaduct provides out of the box. You'll use these constantly—especially `@resolver` for data fetching and `Node` for implementing globally identifiable entities.

**Development Server** (`serve`) gives you a fast iteration loop with GraphiQL and auto-reloading. Run `./gradlew --continuous serve` to start developing, and changes to your schema or resolvers will automatically rebuild and restart the server. For dependency injection, create a `@ViaductServerConfiguration` class that provides your configured Viaduct instance; otherwise, the server runs in a default mode that only supports resolvers with zero-argument constructors.

Start with the [Star Wars tutorial](../../getting_started/index.md) in Getting Started to see these concepts in action, then refer back to the Developers section as you build out your own tenant's schema and resolvers.
