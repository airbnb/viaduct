---
date: 2026-05-01
categories:
  - Releases
description: Release notes for Viaduct 0.31.0.
---

# Release 0.31.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.31.0).

<!-- more -->

## Features

- Adds Tests for Rules in schema ([f21e97d57](https://github.com/airbnb/viaduct/commit/f21e97d57)) by @kristileka
- Adds a new schema validation rule `NoCrossModuleInputExtensionsRule` that prevents one tenant module from using `extend enum` or `extend input` to extend an input type defined in a different module partition. ([6bbbe3d10](https://github.com/airbnb/viaduct/commit/6bbbe3d10)) by @kristileka
- Extend KSP registry extractor to emit field resolvers and type-safe assembly ([79d2ff014](https://github.com/airbnb/viaduct/commit/79d2ff014)) by @nmarsollier
- NodeResolver contract tests using file based bootstrapping - directly working off of KSP's output. ([13c76f59b](https://github.com/airbnb/viaduct/commit/13c76f59b)) by @gokhan-ozgozen
- Adds engine support for resolving root field references ([d00e30dd2](https://github.com/airbnb/viaduct/commit/d00e30dd2)) by @gummybug
- Adds RootFieldReference, which represents a lazily-resolvable reference to a composite-typed root field. ([4d2145cf3](https://github.com/airbnb/viaduct/commit/4d2145cf3)) by @gummybug
- Add a directive banlist ([d8e747770](https://github.com/airbnb/viaduct/commit/d8e747770)) by @jbellenger
- Adds LazyAbstractData to support lazy resolution of interface and union typed fields ([664bb47f4](https://github.com/airbnb/viaduct/commit/664bb47f4)) by @gummybug
- Enrich ResolverParams.Node with ExecutionRegistry-compatible fields ([a1ec4ac31](https://github.com/airbnb/viaduct/commit/a1ec4ac31)) by @nmarsollier
- Attribute Java GRT accessor errors to framework ([d505c6f98](https://github.com/airbnb/viaduct/commit/d505c6f98)) by @catacraciun
- Add tenant codegen KSP pass for emitting intermediate resolver descriptor JSON files. ([fe162daf8](https://github.com/airbnb/viaduct/commit/fe162daf8)) by @nmarsollier

## Bug Fixes

- Fix value generation for pathological oneof graphs ([e96dd432b](https://github.com/airbnb/viaduct/commit/e96dd432b)) by @jbellenger
- Handle introspection fields (e.g. `__typename`) in `SyncEngineObjectDataFactory` ([97aae5c3d](https://github.com/airbnb/viaduct/commit/97aae5c3d)) by @vickeyyeh

## Documentation

- Update code examples from deprecated objectValue/queryValue to getObjectValue()/getQueryValue() ([fe62e06de](https://github.com/airbnb/viaduct/commit/fe62e06de)) by @vickeyyeh

## Refactoring

- Replace deprecated ctx.objectValue/queryValue with getObjectValue()/getQueryValue() ([de799f8d2](https://github.com/airbnb/viaduct/commit/de799f8d2)) by @vickeyyeh
- Change @Variables annotation from CSV string to vararg string array ([87ac42d96](https://github.com/airbnb/viaduct/commit/87ac42d96)) by @geovannefduarte
- Removes ResolverTesters from viaduct and makes ResolverTestBase Experimental. ([674d6b40f](https://github.com/airbnb/viaduct/commit/674d6b40f)) by @kristileka
- Switch ctx.query()/mutation() to resolveSelectionSetSync ([308e88567](https://github.com/airbnb/viaduct/commit/308e88567)) by @vickeyyeh
- Migrate ctx.objectValue/queryValue to ctx.getObjectValue()/getQueryValue() in resolvers and tests ([7d772a4bb](https://github.com/airbnb/viaduct/commit/7d772a4bb)) by @vickeyyeh

## Chores

- Recognize @namespaceType alongside @singleton in schema tooling ([da957df2b](https://github.com/airbnb/viaduct/commit/da957df2b)) by @amity177
- Deprecate ctx.objectValue and ctx.queryValue in favor of getObjectValue() and getQueryValue() ([d74b16826](https://github.com/airbnb/viaduct/commit/d74b16826)) by @vickeyyeh

## Continuous Integration

- Add e2e-snapshot-test.yml orchestration workflow ([5e025b624](https://github.com/airbnb/viaduct/commit/5e025b624)) by @rstata

## Build System

- Speed up CI by compiling test classes in build job and extracting dokka ([#330](https://github.com/airbnb/viaduct/pull/330)) by @rstata
- Extract coverage into parallel job and limit to single matrix cell ([#329](https://github.com/airbnb/viaduct/pull/329)) by @rstata
- Add unbumpSnapshotVersion Gradle task ([5728cda3a](https://github.com/airbnb/viaduct/commit/5728cda3a)) by @rstata
- Add e2e snapshot test runbook and fix setup-gradle warning ([#328](https://github.com/airbnb/viaduct/pull/328)) by @rstata
- Eliminate includeNamed via sub-group Maven coordinates ([#327](https://github.com/airbnb/viaduct/pull/327)) by @rstata
- Fix release notes file upload and refactor versioning tasks ([db27c48e5](https://github.com/airbnb/viaduct/commit/db27c48e5)) by @rstata
- Create buildtime fat jar and eliminate build-shared publication ([dbda5e876](https://github.com/airbnb/viaduct/commit/dbda5e876)) by @rstata
- Copy Jacoco aggregation tasks into the core build ([4d4a68640](https://github.com/airbnb/viaduct/commit/4d4a68640)) by @rstata
- Make core and gradle-plugins runnable as standalone builds ([3b454a017](https://github.com/airbnb/viaduct/commit/3b454a017)) by @rstata
- Replace StringTemplate with Kotlin string templates and remove dead deps ([b16429589](https://github.com/airbnb/viaduct/commit/b16429589)) by @rstata
- Add CI watchdog to alert on workflow startup failures ([#325](https://github.com/airbnb/viaduct/pull/325)) by @geovannefduarte
- Add detektCleanup task for targeted warning enforcement ([710d65930](https://github.com/airbnb/viaduct/commit/710d65930)) by @geovannefduarte
- Narrow Viaduct static analysis to handwritten Kotlin sources ([962abbc16](https://github.com/airbnb/viaduct/commit/962abbc16)) by @rstata
- Remove scaffold task from application Gradle plugin ([42ef4ffed](https://github.com/airbnb/viaduct/commit/42ef4ffed)) by @rstata
- Remove redundant demoapp dependencies ([0db1b9d16](https://github.com/airbnb/viaduct/commit/0db1b9d16)) by @rstata
- Fix detekt cleanup warnings across Viaduct OSS modules ([0f7e5182a](https://github.com/airbnb/viaduct/commit/0f7e5182a)) by @geovannefduarte
