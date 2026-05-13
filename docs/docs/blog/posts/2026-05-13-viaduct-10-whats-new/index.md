---
date: 2026-05-13
authors:
  - ryantanner
categories:
  - Announcements
description: A more powerful engine and a simpler API for our data-oriented mesh.
---

# Viaduct 1.0: What's New

With the 1.0 release, [Viaduct](https://viaduct.airbnb.tech/about/), the open-source, GraphQL-based "[data-oriented service mesh](https://www.google.com/search?q=data-oriented+service+mesh&sourceid=chrome&ie=UTF-8&mstk=AUtExfD4UdfI_i2a9YSYW1E145Med1G7NTeL8xCFLCAs6QDkgEeO-obi2Lg4MGH-LuSc3LeXHwsnxGLV1DwfJghYtXG1KDIv0PqwPp8HwFRdPgZJdZgF3j9n-B_-rxOj5oE0oXr6ON2Jb8RoUAUPbidRJ9j6jlRksEXZLB-PzQFX8SlqlU9XwUF6jAfFQIOruEkJgXIoagspOGZlEpLRiB4jaCR9F0DpCQymJWN9iQtDU314XXswY4NXKv9euUTlETNd5-Kxfdft_1Geg4LtdkT26fj5&csui=3&ved=2ahUKEwjPyODHod-TAxXlHzQIHWeuJIIQgK4QegQIARAC)" developed by Airbnb, has seen significant progress across three areas since our [initial open-source launch in September 2025](https://medium.com/airbnb-engineering/viaduct-five-years-on-modernizing-the-data-oriented-service-mesh-e66397c9e9a9). Here's a rundown of what's new.

## API Enhancements

- **Stable public API**: 1.0 introduces an explicit API stability contract. Public symbols are annotated `@StableApi`, `@ExperimentalApi`, or `@InternalApi`, and binary compatibility for stable APIs is enforced in CI. You can upgrade within the 1.x line without expecting your resolvers to break.
- **Connections**: First-class support for [Relay-style pagination](https://relay.dev/) with `@connection` and `@edge` schema directives, a `ConnectionBuilder` API that handles cursor construction, slicing, and `PageInfo` population automatically, and code-generated connection types.
- **Namespace types**: The new `@namespaceType` directive lets you group related root fields under nested objects (`Query._factories.products.create`) without polluting the root `Query` and `Mutation` types. Schema validation enforces structural rules (e.g., exactly-one-parent) so the grouping stays coherent across modules.
- **Selective resolvers**: `@resolver(isSelective = …)` and `@resolver(isBatching = …)` give you direct control over how the engine schedules a resolver—whether it runs once per selection or batches across siblings—without re-architecting your code.
- **Subqueries and sub-mutations**: Resolvers can now re-enter the Viaduct execution pipeline via `ctx.query()` and `ctx.mutation()`, enabling composition patterns without external service calls.
- **Object mapping**: `EngineValueConv`, `GRTConv`, and `JsonDomain` provide type-safe mapping between raw engine values and GRT types, including JSON-encoded sources.
- **Improved error handling**: Errors from batch and non-batch resolvers are now handled uniformly; new `fetchOrNull` and `fetchOrDefault` utilities support graceful degradation when field access may throw.

## Operations

- **~10× throughput improvement** on large queries, achieved through execution engine refactoring and a synchronous-field fast path.
- **Enhanced observability**: `TaggedMetricInstrumentation` emits field-level latency and error-rate metrics automatically. A `ResolverInstrumentation` framework gives operators hooks to observe resolver execution—timing, errors, and object-data access—without modifying tenant code.
- **Bring-your-own injection framework**: The `TenantCodeInjector` SPI lets you wire Spring, Guice, Micronaut, or any other container into Viaduct's resolver instantiation.

## Developer Experience

- **Build-time tenant wiring**: A KSP-based pipeline now extracts resolver and module metadata at compile time and emits config files that drive runtime bootstrap. Resolver discovery no longer scans the classpath at startup, which means faster cold starts, deterministic wiring, and missing-resolver errors that surface at build time instead of in production.
- **Schema validation at build time**: The Gradle plugin's `validateViaductSchema` task fails fast on invalid schemas, and a CLI validator runs the same checks outside of Gradle for CI pipelines.
- **`scaffold` task**: `./gradlew scaffold` generates a complete, runnable Ktor-based project skeleton, which is the intended "getting started" path.
- **Built-in dev server**: `ViaductServer` starts a GraphQL endpoint, GraphiQL IDE, and health check, with zero web server setup required.
- **Java support**: A complete Java API, with annotations, resolver base classes, and a Java code generator, gives JVM teams that don't use Kotlin a first-class development path.
- **Agentic coding**: Viaduct's strongly typed, introspectable schema makes it well-suited for AI-driven coding workflows. Generated project skeletons embed `AGENTS.md` files describing Viaduct's resolver model, and we are building coding-agent support as a standalone capability, separate from our batteries-included work.

The full changelog is in the [1.0.0 release notes](../2026-05-13-release-1-0-0/index.md). For the broader context on what 1.0 means and why we built Viaduct, see the [Airbnb Engineering Blog announcement](https://medium.com/airbnb-engineering/viaduct-1-0-and-the-future-of-airbnbs-data-mesh-6bab4ec98b89).

