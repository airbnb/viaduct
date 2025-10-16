---
title: "Integration Testing"
description: "Write resolver unit tests and HTTP integration tests using Viaduct."
weight: 50
---

The Star Wars demo shows how to build a Viaduct tenant. This page teaches **how to test** it—both through
end-to-end HTTP calls and with fast, focused resolver unit tests.

- If you want a gentle overview of what gets tested and why, see *Testing Overview*.
- If you want to jump straight to code, start with *Use `DefaultAbstractResolverTestBase`*.

## What you will learn

- How to run **integration tests** against `/graphql` with `@SpringBootTest` and `TestRestTemplate`.
- How to write **unit tests for resolvers** using `DefaultAbstractResolverTestBase`.
- How to test **field, node, query,** and **batch** resolvers.
- How to pass **arguments**, build **GlobalID** values, and (optionally) control **selection sets**.

## Integration tests (HTTP)

Integration tests exercise the full stack by sending GraphQL over HTTP to a real Spring Boot server.

{{< codetag path="demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/ResolverIntegrationTest.kt" tag="resolver_base_test">}}

Integration tests are useful for:
- Verifying end-to-end behavior of the GraphQL API.
- Catching issues with dependency injection, application startup, and configuration.
- Testing real HTTP request/response cycles.
- Smoke tests before a release.

## Use `DefaultAbstractResolverTestBase` (unit testing resolvers)

`DefaultAbstractResolverTestBase` is the **fast path** to exercise resolvers in isolation. It:
- Builds an `ExecutionContext` and resolver‑specific context objects for you.
- Lets you call resolver `resolve()`/`batchResolve()` without HTTP, DI frameworks, or Spring.
- Provides helpers to create `GlobalID`s and to run **field**, **node**, and **batch** resolvers.

Create a test class that **extends** the base and **overrides** `getSchema()`:

{{< codetag path="demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/CharacterResolverUnitTests.kt" tag="character_resolver_unit_tests">}}

Resources are loaded from the given directory. The regex should match all your `*.graphqls` files.

### Key helpers you can find

- `context: ExecutionContext`
  Use it to build GRT objects via generated `*.Builder(context)` and to create `GlobalID`s with
  `context.globalIDFor(Type.Reflection, "internal-id")`.

- `runFieldResolver(resolver, objectValue, queryValue, arguments)`
  Executes a **field resolver** and returns its value.

- `runFieldBatchResolver(resolver, objectValues, queryValues)`
  Executes a **batch field resolver** and returns a list of `FieldValue<T>`.

- `runNodeResolver(resolver, id)` / `runNodeBatchResolver(resolver, ids)`
  Executes a **node resolver** (single or batch) for one or more `GlobalID`s.

Optionally, all runners accept `selections` and `contextQueryValues` when you need to customize
the selection set or seed the context with root query results.

---

## Field resolver

Example: test a simple field resolver that formats a character’s display name.

{{< codetag path="demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/CharacterResolverUnitTests.kt" tag="character_resolver_unit_tests" count="32">}}

Notes:
- `objectValue` represents the object returned by the resolver’s **required selection set**.
- If your resolver takes arguments, build them with the generated `..._Arguments.Builder(context)`.

---

## Query resolver

Example: test with `limit` arguments.

{{< codetag path="demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/QueryResolverUnitTests.kt" tag="test_limit_example">}}

Notes:
- For **query resolvers**, you usually omit `objectValue` and `queryValue`—the runner supplies defaults.

---

## Node resolver

Example: resolve a `Film` node by `GlobalID`.

{{< codetag path="demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/QueryResolverUnitTests.kt" tag="test_node_resolver_example">}}


Tips:
- In this demo, internal ids are small strings (e.g., `"4"`). Your app will use your own id scheme.

## Batch resolver

Example: batch node resolver returning multiple starships.

{{< codetag path="demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/CharacterResolverUnitTests.kt" tag="character_node_resolver_multiple_ids">}}

For **batch field resolvers**, use `runFieldBatchResolver(resolver, objectValues = ..., queryValues = ...)`.

## Header examples

This code shows how to test a field resolver that depends on header values.

{{< codetag path="demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/ExtrasScopeTest.kt" tag="header_execution_example">}}

## Advanced: controlling selections and context queries (optional)

Most resolvers do not need this. When they do:

- **Selections**: pass a `SelectionSet` to the runner if the resolver branches on selected subfields.
- **Context query values**: seed the root query cache with specific results using `contextQueryValues`.
  This is rare; prefer straightforward inputs when possible.

## When to choose unit vs integration tests

- Choose **unit** tests with `DefaultAbstractResolverTestBase` for:
  - Resolver behavior, argument handling, mapping logic, and edge cases.
  - Fast feedback during development (no HTTP, DB, or Spring).

- Choose **integration** tests for:
  - Wiring across modules, startup behavior, and JSON marshalling.
  - Request filters and application security.

In practice, keep most tests **unit‑level**, plus a few end‑to‑end HTTP tests as smoke tests.

## Troubleshooting

- **`IllegalArgumentException` about query/object sizes**
  When using `runFieldBatchResolver`, make sure `objectValues.size == queryValues.size`.

- **Missing schema types**
  Ensure `getSchema()` loads all `*.graphqls` files. The regex `".*\.graphqls"` is a safe default.

- **GlobalID building**
  Use `context.globalIDFor(Type.Reflection, "<your-internal-id>")`. Avoid constructing IDs by hand.

## Full examples in the repo

See these demo tests in `demoapps/starwars/src/test/kotlin/viaduct/demoapp/starwars`:

- <a href="https://github.com/airbnb/viaduct/blob/main/demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/CharacterResolverUnitTests.kt" target="_blank" rel="noopener noreferrer">CharacterResolverUnitTests.kt</a>
- <a href="https://github.com/airbnb/viaduct/blob/main/demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/FilmResolverUnitTests.kt" target="_blank" rel="noopener noreferrer">FilmResolverUnitTests.kt</a>
- <a href="https://github.com/airbnb/viaduct/blob/main/demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/QueryResolverUnitTests.kt" target="_blank" rel="noopener noreferrer">QueryResolverUnitTests.kt</a>
- <a href="https://github.com/airbnb/viaduct/blob/main/demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/StarshipResolversUnitTests.kt" target="_blank" rel="noopener noreferrer">StarshipResolversUnitTests.kt</a>
- <a href="https://github.com/airbnb/viaduct/blob/main/demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/ResolverIntegrationTest.kt" target="_blank" rel="noopener noreferrer">ResolverIntegrationTest.kt</a>
- <a href="https://github.com/airbnb/viaduct/blob/main/demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/BatchResolverIntegrationTest.kt" target="_blank" rel="noopener noreferrer">BatchResolverIntegrationTest.kt</a>

They are small, focused, and make good templates for new tests.

## Summary

- Use **integration tests** to verify the end‑to‑end server.
- Use **`DefaultAbstractResolverTestBase`** to test resolvers fast and in isolation.
- Prefer small, targeted tests with clear inputs/outputs and minimal mocking.
