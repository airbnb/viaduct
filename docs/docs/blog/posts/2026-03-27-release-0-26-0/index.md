---
date: 2026-03-27
categories:
  - Releases
description: Release notes for Viaduct 0.26.0.
---

# Release 0.26.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.26.0).

<!-- more -->

## Features

- Add error attribution to the object-mapping library ([8e75d566](https://github.com/airbnb/viaduct/commit/8e75d566)) by @fireboy1919
- Add a data class to represent the execution registry bootstrap redesign process. This feature reads tenant API definitions from a file-driven schema registry. ([12788541](https://github.com/airbnb/viaduct/commit/12788541)) by @nmarsollier
- Adds `KotlinObjectContractTest` — a contract test that verifies the Kotlin tenant runtime correctly supports basic object resolution patterns (shorthand/fragment `@Resolver`, object builders, list resolvers, resolvers with arguments). The test extends `ObjectContractTest`, which defines the SDL, so the Kotlin test only provides resolver implementations. ([3ba352bf](https://github.com/airbnb/viaduct/commit/3ba352bf)) by @catacraciun
- Fill framework wrapping holes in `ObjectBase.Builder` and `ViaductObjectBuilder` ([28fa30f0](https://github.com/airbnb/viaduct/commit/28fa30f0)) by @fireboy1919
- ObjectContractTest - Java implementation ([82977527](https://github.com/airbnb/viaduct/commit/82977527)) by @catacraciun
- Adds connection resolver unit test coverage to the Viaduct data.example module and extends ResolverTestBase to support ConnectionFieldExecutionContext, enabling unit testing of connection resolvers. ([d2192163](https://github.com/airbnb/viaduct/commit/d2192163)) by @kristileka

## Bug Fixes

- Publish `com.airbnb.viaduct:build-shared` to Maven Central during releases ([2d7e13d1](https://github.com/airbnb/viaduct/commit/2d7e13d1)) by @rstata
- `ExecutionParameters` now carries attribution as a direct field so `forObjectTraversal` preserves the correct attribution regardless of the `localContext` passed. ([f8349e58](https://github.com/airbnb/viaduct/commit/f8349e58)) by @vickeyyeh
- Update `errors.api` dump to reflect removed classes ([95c51c0b](https://github.com/airbnb/viaduct/commit/95c51c0b)) by @vickeyyeh
- Use `TaskProvider` in `dependsOn`, defer provider resolution, and validate the codegen classpath ([65542f4b](https://github.com/airbnb/viaduct/commit/65542f4b)) by @rstata
- Add API compatibility for errors; make `FieldFetchingException` a pass-through ([f4a9fcf7](https://github.com/airbnb/viaduct/commit/f4a9fcf7)) by @fireboy1919
- Clean included-build root project build dirs in `orchestrationCleanAll` ([e7cbc0ea](https://github.com/airbnb/viaduct/commit/e7cbc0ea)) by @rstata
- Pass through all `PassthroughException` instances in `handleTenantAPIErrors` ([8087d854](https://github.com/airbnb/viaduct/commit/8087d854)) by @fireboy1919
- Add `DataFetcherEnvironment` to access checker instrumentation ([f62334dd](https://github.com/airbnb/viaduct/commit/f62334dd)) by @ryantanner
- Fix a bug in `fromList` connection overflow by promoting the arithmetic calculation to `Long`. ([9e2de4f0](https://github.com/airbnb/viaduct/commit/9e2de4f0)) by @kristileka
- `FieldExecutionHelpers` was constructing `ExecutionObservabilityContext` without `attribution`, causing `attribution` to always be `null` in the DFE's local context. Fixed by passing `parameters.queryPlan.attribution` when building the context. ([09993cc5](https://github.com/airbnb/viaduct/commit/09993cc5)) by @vickeyyeh

## Documentation

- Fix typo `creat` -> `create` in `oss/AGENTS.md` ([a6c05cf4](https://github.com/airbnb/viaduct/commit/a6c05cf4)) by @amity177
- Add `impldocs/modern-access-check.md` covering the MAC engine architecture — `CheckerExecutorFactory` SPI, QueryPlan RSS embedding, the OER dual-slot pattern, and how checker results flow through completion. ([4920de79](https://github.com/airbnb/viaduct/commit/4920de79)) by @amity177

## Refactoring

- Move core source dirs under `core/` to align with the build structure ([4eaec42c](https://github.com/airbnb/viaduct/commit/4eaec42c)) by @rstata
- Move `ViaductDataFetchingEnvironment` to `viaduct.engine.runtime.dfe` ([e88f6fa2](https://github.com/airbnb/viaduct/commit/e88f6fa2)) by @geovannefduarte
- Standardize Gradle project names to match Maven artifact IDs ([3e0b9fb8](https://github.com/airbnb/viaduct/commit/3e0b9fb8)) by @rstata
- Consolidate orphaned Gradle projects into the core included build, and standardize the `build-logic` artifact group ([5d6d5f7b](https://github.com/airbnb/viaduct/commit/5d6d5f7b)) by @rstata
