---
date: 2026-05-13
categories:
  - Releases
description: Release notes for Viaduct 1.0.0.
---

# Release 1.0.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v1.0.0).

<!-- more -->

## Breaking Changes

- Simplify GlobalIDCodec ([5e47178c](https://github.com/airbnb/viaduct/commit/5e47178c)) by @rstata

## Features

- Add new rule in `NamespaceTypeConstraintsRule` to prevent conflicting field directives on field returning namespace type. ([3274e8fa](https://github.com/airbnb/viaduct/commit/3274e8fa)) by @amity177
- Removes mockk from tenant api testFixtures ([defd0320](https://github.com/airbnb/viaduct/commit/defd0320)) by @kristileka
- Adds ExperimentalApi as an opt-in on Resolvers ([e38728fc](https://github.com/airbnb/viaduct/commit/e38728fc)) by @kristileka
- Promote `ErroneousFieldException` to `@StableApi` with Viaduct-owned `FieldError` type ([fde3c0b2](https://github.com/airbnb/viaduct/commit/fde3c0b2)) by @nmarsollier
- API annotations for the error types ([c0c00cff](https://github.com/airbnb/viaduct/commit/c0c00cff)) by @gokhan-ozgozen
- Introduce `@TypeInferenceApi` annotation for resolver base interfaces that exist solely to support Kotlin type inference in generated code ([ee554fea](https://github.com/airbnb/viaduct/commit/ee554fea)) by @kristileka
- KSP is now mandatory for plugins ([db9278a6](https://github.com/airbnb/viaduct/commit/db9278a6)) by @gokhan-ozgozen
- Verify that `requestContext` set on `ExecutionInput` is propagated through the Viaduct execution pipeline and is accessible in resolvers via `ctx.requestContext` ([cf614e25](https://github.com/airbnb/viaduct/commit/cf614e25)) by @kristileka
- All Viaduct creation functions now use configuration files ([ed487867](https://github.com/airbnb/viaduct/commit/ed487867)) by @gokhan-ozgozen
- Replace `.Builder(ctx)...build()` construction with the shorter `.of(ctx) { ... }` lambda DSL across the StarWars demo app ([674eb9dd](https://github.com/airbnb/viaduct/commit/674eb9dd)) by @kristileka
- Add typed resolver test infrastructure to `viaduct.api.testing` ([02a8193d](https://github.com/airbnb/viaduct/commit/02a8193d)) by @kristileka
- Add `InputValueBuilder<T>` contract and `of(context) { ... }` factory for input-like types ([3418e255](https://github.com/airbnb/viaduct/commit/3418e255)) by @rstata
- Introduce `rrp-server`, drop remoteresolvers dep from starwars ([ab0b8321](https://github.com/airbnb/viaduct/commit/ab0b8321)) by @cetinsahin
- File-based bootstrapping validations ([8dd20fc0](https://github.com/airbnb/viaduct/commit/8dd20fc0)) by @gokhan-ozgozen
- Add `tenantModuleBootstrapper` to `BasicViaductFactory.createFromResource` for per-tenant DI ([26a8e254](https://github.com/airbnb/viaduct/commit/26a8e254)) by @njlynch
- In-process Remote Resolver runtime and StarWars demo wiring ([4dc1b075](https://github.com/airbnb/viaduct/commit/4dc1b075)) by @cetinsahin
- Add `rootFieldRef` to resolver execution context ([16e360fc](https://github.com/airbnb/viaduct/commit/16e360fc)) by @gummybug
- Add batch field resolver support to the Viaduct Java API ([cb361833](https://github.com/airbnb/viaduct/commit/cb361833)) by @catacraciun
- Add Field/Mutation/Connection ResolverBases for type definition ([502994d1](https://github.com/airbnb/viaduct/commit/502994d1)) by @kristileka
- Detect `@TenantBootstrapper` at build time and populate `bootstrapClass` in `ExecutionRegistry` ([94df161e](https://github.com/airbnb/viaduct/commit/94df161e)) by @junjinp
- Add gRPC-based remote resolver execution module ([81012734](https://github.com/airbnb/viaduct/commit/81012734)) by @cetinsahin
- Convert `RootCompositeField` to `RootObjectField`, and add `@InternalApi` `pathFromQueryRoot` property ([2b12249c](https://github.com/airbnb/viaduct/commit/2b12249c)) by @gummybug

## Bug Fixes

- Remove junit from tenant-api testFixtures fat jar ([9b6c1543](https://github.com/airbnb/viaduct/commit/9b6c1543)) by @gokhan-ozgozen
- Populate locations in `ErroneousFieldException` error response ([3037e4bf](https://github.com/airbnb/viaduct/commit/3037e4bf)) by @nmarsollier
- Exclude api-bundled classes from runtime shadow jar ([c111d1c5](https://github.com/airbnb/viaduct/commit/c111d1c5)) by @geovannefduarte
- Add failure alerting to demoapps-nightly-check workflow ([#348](https://github.com/airbnb/viaduct/pull/348)) by @geovannefduarte
- Publish remoteresolvers to snapshots and drop unused public-module deps ([8f7ab249](https://github.com/airbnb/viaduct/commit/8f7ab249)) by @cetinsahin
- Remove `FRAMEWORK` attribution from `ObjectBase.get` ([cd93d0a2](https://github.com/airbnb/viaduct/commit/cd93d0a2)) by @gummybug
- Exclude `org/junit` from runtime shadow jar ([35e339ae](https://github.com/airbnb/viaduct/commit/35e339ae)) by @fireboy1919
- Align override nullability with graphql-java 26 signatures ([#343](https://github.com/airbnb/viaduct/pull/343)) by @geovannefduarte

## Documentation

- Second pass on reviewer feedback ([#355](https://github.com/airbnb/viaduct/pull/355)) by @ryantanner
- Incorporate reviewer feedback across getting-started docs ([#354](https://github.com/airbnb/viaduct/pull/354)) by @ryantanner
- Remove serve docs from navigation ([916c460e](https://github.com/airbnb/viaduct/commit/916c460e)) by @fireboy1919
- Auto-generate copyright year at docs build time ([dc909ecd](https://github.com/airbnb/viaduct/commit/dc909ecd)) by @geovannefduarte
- Move runbook content into CONTRIBUTING.md and extract demoapp guide ([d119eb2f](https://github.com/airbnb/viaduct/commit/d119eb2f)) by @gokhan-ozgozen
- Relocate OSS docs into `impldocs/` with spear-case naming ([021ed68b](https://github.com/airbnb/viaduct/commit/021ed68b)) by @geovannefduarte
- Rewrite resolver testing documentation to clearly separate API reference from examples, and fix codetag rendering issues ([e70a8c09](https://github.com/airbnb/viaduct/commit/e70a8c09)) by @kristileka
- Clarify in documentation that the `@Resolver` annotation is required on node resolvers ([ae63a17c](https://github.com/airbnb/viaduct/commit/ae63a17c)) by @gummybug
- Add doc linting to CI ([9752685e](https://github.com/airbnb/viaduct/commit/9752685e)) by @ryantanner

## Refactoring

- Remove MockK from engine runtime test fixtures ([27e2121d](https://github.com/airbnb/viaduct/commit/27e2121d)) by @geovannefduarte
- Simplify `BasicViaductFactory` and `ViaductBuilder` public APIs ([1b36cb9e](https://github.com/airbnb/viaduct/commit/1b36cb9e)) by @rstata
- Move resolver authoring APIs into `viaduct.api.resolver` ([9650a0ee](https://github.com/airbnb/viaduct/commit/9650a0ee)) by @rstata
- Remove unused `Struct` and `RecordOutput` marker interfaces ([4b6f7104](https://github.com/airbnb/viaduct/commit/4b6f7104)) by @rstata
- Move `OffsetCursor`/`OffsetLimit` into `viaduct.api.types` ([7b8594d7](https://github.com/airbnb/viaduct/commit/7b8594d7)) by @rstata
- Remove `DataFetchingEnvironment` from `ErrorReporter.Metadata` stable API ([cb2a29df](https://github.com/airbnb/viaduct/commit/cb2a29df)) by @fireboy1919
- Resolver base classes should be internal ([a4843258](https://github.com/airbnb/viaduct/commit/a4843258)) by @rstata
- Derive resolver type info from schema instead of generic positions ([cdc4e118](https://github.com/airbnb/viaduct/commit/cdc4e118)) by @rstata
- Rename `GuiceTenantCodeInjector` → `GuiceCodeInjector`, `NaiveTenantCodeInjectorFixtures` → `NaiveCodeInjectorFixtures` ([2c7c322e](https://github.com/airbnb/viaduct/commit/2c7c322e)) by @junjinp
- Remove `DataFetcherExceptionHandler` from `ViaductBuilder` stable API ([cc46b2d7](https://github.com/airbnb/viaduct/commit/cc46b2d7)) by @fireboy1919
- Move snipped/errors source to `common/viaduct/shared` ([d0759503](https://github.com/airbnb/viaduct/commit/d0759503)) by @fireboy1919
- Rename `ObjectBase.context`/`engineObject` to `__context`/`__engineObject`, remove helper methods ([081c7264](https://github.com/airbnb/viaduct/commit/081c7264)) by @vickeyyeh
- `@StableApi` sweep — trivial and small fixes ([9aec4343](https://github.com/airbnb/viaduct/commit/9aec4343)) by @fireboy1919
- Narrow project-health SLF4J exemption to logger extensions file ([a876f35f](https://github.com/airbnb/viaduct/commit/a876f35f)) by @nmarsollier
- Replace manual file traversal with `getSymbolsWithAnnotation` for resolver discovery ([db535edd](https://github.com/airbnb/viaduct/commit/db535edd)) by @nmarsollier
- Make GRT field getters non-suspend ([2f0e9e28](https://github.com/airbnb/viaduct/commit/2f0e9e28)) by @vickeyyeh
- Make `GlobalID` a final class with internal constructor ([6642ef50](https://github.com/airbnb/viaduct/commit/6642ef50)) by @fireboy1919
- Rename internal `tenantCodeInjector` params to `codeInjector` in bootstrapping classes ([6253cb25](https://github.com/airbnb/viaduct/commit/6253cb25)) by @junjinp
- Rename `instrumentSyncFetchSelection` → `instrumentReadSelection`; always install `InstrumentedEngineObjectData.Sync` ([20077e08](https://github.com/airbnb/viaduct/commit/20077e08)) by @vickeyyeh

## Chores

- Migrate BCV api dump reviewers from STRICT_REVIEWERS to MANDATORY_REVIEWERS ([81b167d2](https://github.com/airbnb/viaduct/commit/81b167d2)) by @nmarsollier
- Suppress module metadata and restore graphql-java in published POMs ([3a02e0e0](https://github.com/airbnb/viaduct/commit/3a02e0e0)) by @geovannefduarte
- Expose tenant module config assembler as Bazel action binary ([8dc849a5](https://github.com/airbnb/viaduct/commit/8dc849a5)) by @njlynch
- Fix remaining checkstyle, opt-in, and dependency warnings ([71022d99](https://github.com/airbnb/viaduct/commit/71022d99)) by @geovannefduarte
- Mark `TenantModule` as internal ([ea09a4a3](https://github.com/airbnb/viaduct/commit/ea09a4a3)) by @gokhan-ozgozen
- Suppress compiler warnings in tenant and javaapi modules ([b823d718](https://github.com/airbnb/viaduct/commit/b823d718)) by @geovannefduarte
- Suppress low-risk compiler warnings before 1.0 release ([cca3a70b](https://github.com/airbnb/viaduct/commit/cca3a70b)) by @geovannefduarte
- Suppress compiler warnings in engine runtime and wiring ([39798092](https://github.com/airbnb/viaduct/commit/39798092)) by @geovannefduarte
- Add strict reviewers to wiring and errors api dump files ([574c0d14](https://github.com/airbnb/viaduct/commit/574c0d14)) by @nmarsollier
- Remove qualified `CodeInjector` providers ([62a0e13a](https://github.com/airbnb/viaduct/commit/62a0e13a)) by @njlynch
- Fix all checkstyle violations in Java API module ([#341](https://github.com/airbnb/viaduct/pull/341)) by @geovannefduarte
- Add missing `@InternalApi` annotations to satisfy detekt ([#340](https://github.com/airbnb/viaduct/pull/340)) by @geovannefduarte

## Build System

- Add RC publication flow ([#352](https://github.com/airbnb/viaduct/pull/352)) by @rstata
- Drop version header from changelog ([#351](https://github.com/airbnb/viaduct/pull/351)) by @ryantanner
- Wait for new artifacts to appear in Maven Central / Plugin Portal ([#350](https://github.com/airbnb/viaduct/pull/350)) by @ryantanner
- Capitalize and dedupe release note entries ([#349](https://github.com/airbnb/viaduct/pull/349)) by @ryantanner
- Use Artifactory mirror for Gradle dependency resolution when available ([f917dcca](https://github.com/airbnb/viaduct/commit/f917dcca)) by @fireboy1919
- Remove `includeNamed` and restore publication group ([#344](https://github.com/airbnb/viaduct/pull/344)) by @rstata
