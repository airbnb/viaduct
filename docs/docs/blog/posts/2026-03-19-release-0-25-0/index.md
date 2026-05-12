---
date: 2026-03-19
categories:
  - Releases
description: Release notes for Viaduct 0.25.0.
---

# Release 0.25.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.25.0).

<!-- more -->

## Breaking Changes

- Create errors library and rename exception classes ([e4777814](https://github.com/airbnb/viaduct/commit/e4777814)) by @fireboy1919
- Updated kotlin coroutines to version 1.8.1 ([3449bae7](https://github.com/airbnb/viaduct/commit/3449bae7)) by @viaduct-maintainers

## Features

- Add syncValueComputation field to InstrumentExecuteResolverParameters ([0a026966](https://github.com/airbnb/viaduct/commit/0a026966)) by @vickeyyeh
- Add `WithBeginNodeFetching` opt-in instrumentation hook to observe node resolver dispatch. New `InstrumentNodeFetchingParameters` provides the `DataFetchingEnvironment` and `ResolverMetadata` for the node resolver. Existing implementations are unaffected via a default no-op on `ViaductModernGJInstrumentation`. ([57348924](https://github.com/airbnb/viaduct/commit/57348924)) by @vickeyyeh
- Add copy-as-markdown dropdown with AI integration ([#288](https://github.com/airbnb/viaduct/pull/288)) by @ryantanner
- Expose Attribution in FieldExecutionScope ([a64e5c6e](https://github.com/airbnb/viaduct/commit/a64e5c6e)) by @jbellenger
- Add `fieldCoordinate: Coordinate?` to `InstrumentExecuteResolverParameters`, allowing instrumentation implementations to observe which GraphQL field coordinate is being resolved. ([db3a3042](https://github.com/airbnb/viaduct/commit/db3a3042)) by @vickeyyeh
- Add resolverType to ResolverMetadata ([1d7d9a75](https://github.com/airbnb/viaduct/commit/1d7d9a75)) by @vickeyyeh
- Adds `ConnectionBuilder` feature app tests and Tutorial 11 (Connections) to Viaduct OSS tenant. ([554cb193](https://github.com/airbnb/viaduct/commit/554cb193)) by @kristileka
- Adds `BackingDataFieldsRule` OSS schema validation and a real `@backingData` example to the StarWars demo. ([df40e5ef](https://github.com/airbnb/viaduct/commit/df40e5ef)) by @kristileka
- Add ctx.query / ctx.mutation subqueries tutorial ([7e474140](https://github.com/airbnb/viaduct/commit/7e474140)) by @fireboy1919
- Improved mechanism for release managers to validate release branches ([#301](https://github.com/airbnb/viaduct/pull/301)) by @rstata
- Improved scripting for demoapp publishing ([#300](https://github.com/airbnb/viaduct/pull/300)) by @rstata
- Add `tenantMetadata` field to `ResolverMetadata` and introduce `TenantModuleMetadata` data class; `TenantPackageFinder` now returns `Set<TenantPackageInfo>` (previously `Set<String>`) carrying per-module metadata; `TenantResolverClassFinder` gains a `tenantModuleMetadata()` method; all executor impls accept an optional `tenantMetadata: TenantModuleMetadata?` parameter ([f8a1b712](https://github.com/airbnb/viaduct/commit/f8a1b712)) by @vickeyyeh
- Add a test-java-feature-app Gradle plugin and use it to replicate the Kotlin ObjectFeatureAppTest in the Java runtime module. This validates that the Java codegen (JavaGRTsCodegen + JavaResolversCodegen) can auto-generate all GRT and resolver base classes from inline SDL, matching the same patterns tested in the Kotlin tenant runtime. ([db3845f9](https://github.com/airbnb/viaduct/commit/db3845f9)) by @catacraciun
- Add ViaductTenantNameContext to instrumentation API ([b75291e3](https://github.com/airbnb/viaduct/commit/b75291e3)) by @ryantanner

## Bug Fixes

- Deflake Arb.flatten test ([369890c5](https://github.com/airbnb/viaduct/commit/369890c5)) by @jbellenger
- Earlier @idOf directive validation ([69df39b1](https://github.com/airbnb/viaduct/commit/69df39b1)) by @jbellenger
- Fix deprecated buildDir and untyped outputs in ViaductFeatureAppPluginBase ([3b5812a5](https://github.com/airbnb/viaduct/commit/3b5812a5)) by @rstata
- Declare default schema as @InputFile and add @CacheableTask to test-support codegen tasks ([7f64c192](https://github.com/airbnb/viaduct/commit/7f64c192)) by @rstata
- Use @Classpath annotation for codegen task classpaths ([e3c85257](https://github.com/airbnb/viaduct/commit/e3c85257)) by @rstata
- Disallow reserved literals in GraphQL enum value generation ([d83890fc](https://github.com/airbnb/viaduct/commit/d83890fc)) by @jbellenger
- Aligns CLI flag name with Bazel rule by renaming the option from --binary_compilation_schema to --compilation_schema_binary, so the generator now receives the intended per-tenant compilation schema binary instead of silently falling back to the full schema. ([5f390c44](https://github.com/airbnb/viaduct/commit/5f390c44)) by @junjinp
- Use resolverName instead of resolverId in wrapResolveException ([dfd5d15c](https://github.com/airbnb/viaduct/commit/dfd5d15c)) by @fireboy1919
- Preserve author mappings, PR references, and dedup authors ([c2c70b34](https://github.com/airbnb/viaduct/commit/c2c70b34)) by @gokhan-ozgozen
- Fixes `ConnectionBuilder` codegen failures when a `@connection` type and its `@edge` type are assigned to different build shards during schema codegen. ([5f158a3b](https://github.com/airbnb/viaduct/commit/5f158a3b)) by @kristileka
- Replace deprecated URL(String) constructor with URI.toURL() ([1c235133](https://github.com/airbnb/viaduct/commit/1c235133)) by @fireboy1919
- Fix warnings in test and testFixtures: ([de73b5d9](https://github.com/airbnb/viaduct/commit/de73b5d9)) by @nmarsollier

## Documentation

- Surface implementation docs in OSS [AGENTS.md](http://agents.md/) files ([9e7748fd](https://github.com/airbnb/viaduct/commit/9e7748fd)) by @amity177
- Update required java version in docs ([#302](https://github.com/airbnb/viaduct/pull/302)) by @jtuchscherer
- Fix link to sonatype in release runbook ([#304](https://github.com/airbnb/viaduct/pull/304)) by @ryantanner
- Updated documentation for Selective Node Resolvers. ([2ad7d926](https://github.com/airbnb/viaduct/commit/2ad7d926)) by @pclowes

## Refactoring

- Move runtime infrastructure to viaduct.engine.runtime ([12985491](https://github.com/airbnb/viaduct/commit/12985491)) by @geovannefduarte
- Move engine SPI interfaces to `viaduct.engine.api.spi` ([87a99a19](https://github.com/airbnb/viaduct/commit/87a99a19)) by @geovannefduarte
- Mv ID helpers out of WrapUtils to viaduct.shared ([030a0263](https://github.com/airbnb/viaduct/commit/030a0263)) by @jbellenger
- Removes redundant access check execution code in `ResolverDataFetcher`, since access checks are executed by the execution strategy outside of data fetchers. ([cd56be64](https://github.com/airbnb/viaduct/commit/cd56be64)) by @gummybug

## Chores

- Remove empty shared/logging module ([7aeebce1](https://github.com/airbnb/viaduct/commit/7aeebce1)) by @rstata
- Add some helper methods, and fix Arb.flatten ([54abd085](https://github.com/airbnb/viaduct/commit/54abd085)) by @jbellenger
- Mv result IR generator to its own file ([0b2c944e](https://github.com/airbnb/viaduct/commit/0b2c944e)) by @jbellenger
- Filter NullLiteral values in ViaductSchema toDirectiveForTypeDefinition ([66244e66](https://github.com/airbnb/viaduct/commit/66244e66)) by @njlynch

## Build System

- Clean up root build configuration and make publishing explicit ([7e8db944](https://github.com/airbnb/viaduct/commit/7e8db944)) by @rstata
