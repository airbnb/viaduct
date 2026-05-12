---
date: 2026-05-08
categories:
  - Releases
description: Release notes for Viaduct 0.32.0.
---

# Release 0.32.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.32.0).

<!-- more -->

## Breaking Changes

- Rename TenantModuleBootstrapper to LegacyTenantModuleBootstrapper; add new TenantModuleBootstrapper and CodeInjector SPIs ([4a1ca34c](https://github.com/airbnb/viaduct/commit/4a1ca34c)) by @njlynch
- Remove deprecated objectValue/queryValue properties from FieldExecutionContext ([20cb4687](https://github.com/airbnb/viaduct/commit/20cb4687)) by @vickeyyeh

## Features

- Make ViaductException implement TenantException ([be1ee91e](https://github.com/airbnb/viaduct/commit/be1ee91e)) by @fireboy1919
- Strict missing-resolver validation at startup ([3b4c18b7](https://github.com/airbnb/viaduct/commit/3b4c18b7)) by @geovannefduarte
- File-driven bootstrapping for demoapps. ([309a3a6c](https://github.com/airbnb/viaduct/commit/309a3a6c)) by @gokhan-ozgozen
- Add executionPath to InstrumentExecuteResolverParameters ([8e30b745](https://github.com/airbnb/viaduct/commit/8e30b745)) by @vickeyyeh
- Validate object types in GRT builder list elements ([fcaedccf](https://github.com/airbnb/viaduct/commit/fcaedccf)) by @fireboy1919
- Removes setter from objects if field is defined on an extend ([f5a9e496](https://github.com/airbnb/viaduct/commit/f5a9e496)) by @kristileka
- Add KSP-based module config file generation ([55ee6216](https://github.com/airbnb/viaduct/commit/55ee6216)) by @rstata
- File based bootstrapping API surface changes. ([f2a933f0](https://github.com/airbnb/viaduct/commit/f2a933f0)) by @gokhan-ozgozen
- Add `getXxxOrNull` generated accessors on modern GRTs that return `null` instead of throwing on policy-check failures or unset fields. `CancellationException` is still rethrown. ([68fc7ead](https://github.com/airbnb/viaduct/commit/68fc7ead)) by @alexanderuv
- Add Java API spring-starter demo app and fix Java GRT/resolver-bases codegen ([bbf96ad3](https://github.com/airbnb/viaduct/commit/bbf96ad3)) by @catacraciun

- Support root field references resolving to null ([0c8747b6](https://github.com/airbnb/viaduct/commit/0c8747b6)) by @gummybug
- Adds two new OSS schema validation rules to `DefaultSchemaValidator` and the CI-time `ViaductServiceValidation`: ([ff567078](https://github.com/airbnb/viaduct/commit/ff567078)) by @kristileka
- `ExecutorFactory` and its implementation. Additional KSP fields to support schema-less bootstrapping. ([0982b51e](https://github.com/airbnb/viaduct/commit/0982b51e)) by @gokhan-ozgozen
- Require @Resolver annotation on node resolvers ([602bd569](https://github.com/airbnb/viaduct/commit/602bd569)) by @geovannefduarte
- Populate providedVariables from @Variables nested class annotation ([c6d65ea9](https://github.com/airbnb/viaduct/commit/c6d65ea9)) by @nmarsollier

## Bug Fixes

- Exclude org/junit from runtime shadow jar ([6e246cbb](https://github.com/airbnb/viaduct/commit/6e246cbb)) by @fireboy1919
- Fix VariablesProvider and @Variables detection in file-based bootstrap ([6cae886d](https://github.com/airbnb/viaduct/commit/6cae886d)) by @nmarsollier
- Fix alert delivery condition in post-alerts.yml ([#335](https://github.com/airbnb/viaduct/pull/335)) by @geovannefduarte

## Documentation

- Fix filename in README directory tree ([#342](https://github.com/airbnb/viaduct/pull/342)) by @rstata
- Add object-lifecycles.md to impldocs ([cfcd582d](https://github.com/airbnb/viaduct/commit/cfcd582d)) by @rstata
- Re-organizae website nav bar ([d0108947](https://github.com/airbnb/viaduct/commit/d0108947)) by @ryantanner
- Fix @backingData directive example to use Film.castData ([#332](https://github.com/airbnb/viaduct/pull/332)) by @geovannefduarte

## Refactoring

- Clean up unused variables, parameters and lint violations ([8157cabf](https://github.com/airbnb/viaduct/commit/8157cabf)) by @geovannefduarte
- Apply error boundary pattern to ObjectBase.get/fetch ([481d0f05](https://github.com/airbnb/viaduct/commit/481d0f05)) by @fireboy1919
- Move FragmentResolutionError types from OSS to common/viaduct ([e83accbf](https://github.com/airbnb/viaduct/commit/e83accbf)) by @fireboy1919
- Migrate OverlayEngineObjectData and Builder to EngineObjectData.Sync ([192c1a1d](https://github.com/airbnb/viaduct/commit/192c1a1d)) by @vickeyyeh
- Normalize attribution annotations to unified @Attribution model ([ae53fc4a](https://github.com/airbnb/viaduct/commit/ae53fc4a)) by @rstata
- Remove support for abstract-typed root field references in the engine ([8acc56c4](https://github.com/airbnb/viaduct/commit/8acc56c4)) by @gummybug
- Remove deprecated objectValue/queryValue test assertions ([06d377b0](https://github.com/airbnb/viaduct/commit/06d377b0)) by @vickeyyeh
- Remove snipped/errors from ViaductDataFetcherExceptionHandler ([1ce749a9](https://github.com/airbnb/viaduct/commit/1ce749a9)) by @fireboy1919
- Normalize executor error boundaries and convert tenant API validation errors ([df40fb2d](https://github.com/airbnb/viaduct/commit/df40fb2d)) by @fireboy1919
- Replace snipped exception types with IllegalStateException in Fragment.kt ([9145d4b6](https://github.com/airbnb/viaduct/commit/9145d4b6)) by @fireboy1919

## Chores

- Remove NoCustomDirectivesRule ([7f05ad53](https://github.com/airbnb/viaduct/commit/7f05ad53)) by @gummybug
- Revert "[viaduct] [oss] Strict missing-resolver validation... ([665ae1c0](https://github.com/airbnb/viaduct/commit/665ae1c0)) by @amity177
- Upgrade graphql-java 25.0 -&gt; 26.0 (take 2) ([629cb93a](https://github.com/airbnb/viaduct/commit/629cb93a)) by @jbellenger
- Adopt FastSchemaGenerator in several locations to enhance performance ([c0497671](https://github.com/airbnb/viaduct/commit/c0497671)) by @njlynch
- Add Java Gradle plugins mirroring Kotl... ([e6c2d733](https://github.com/airbnb/viaduct/commit/e6c2d733)) by @catacraciun

## Build System

- Remove includeNamed and restore publication group ([#344](https://github.com/airbnb/viaduct/pull/344)) by @rstata
- Restructure Java API publications and add spring-starter to CI ([#338](https://github.com/airbnb/viaduct/pull/338)) by @rstata
- Make dokka job non-blocking for overall CI status ([#337](https://github.com/airbnb/viaduct/pull/337)) by @geovannefduarte
