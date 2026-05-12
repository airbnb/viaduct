---
date: 2026-04-10
categories:
  - Releases
description: Release notes for Viaduct 0.28.0.
---

# Release 0.28.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.28.0).

<!-- more -->

## Breaking Changes

- Wire isSelective through field resolver runtime ([9bec732b](https://github.com/airbnb/viaduct/commit/9bec732b)) by @jbellenger
- Wiring for selective field resolvers ([46ba6a90](https://github.com/airbnb/viaduct/commit/46ba6a90)) by @jbellenger
- EngineSelectionSet equality ([a2dfcee3](https://github.com/airbnb/viaduct/commit/a2dfcee3)) by @jbellenger
- Move node resolver selectivity into `@resolver(isSelective)` ([d4f5a889](https://github.com/airbnb/viaduct/commit/d4f5a889)) by @jbellenger

## Features

- Add ViaductReverseSchema for inbound schema navigation ([890cf634](https://github.com/airbnb/viaduct/commit/890cf634)) by @rstata
- Add selections to ObjectEngineResultKey ([93a770d7](https://github.com/airbnb/viaduct/commit/93a770d7)) by @jbellenger
- Add ProxyResolverFactory SPI for resolver wrapping ([4ca8ca89](https://github.com/airbnb/viaduct/commit/4ca8ca89)) by @cetinsahin
- Adds validation for @namespaceType types and fields ([181b0541](https://github.com/airbnb/viaduct/commit/181b0541)) by @gummybug
- Adds @namespaceType ([2b9df458](https://github.com/airbnb/viaduct/commit/2b9df458)) by @gummybug
- Add ctx.query()/ctx.mutation() to Java Tenant API with codegen fixes ([0c85171d](https://github.com/airbnb/viaduct/commit/0c85171d)) by @catacraciun
- Migrates 17 Viaduct FeatureAppTests into the ContractTest pattern, enabling cross-language verification of GraphQL resolver behavior. ([351b8b90](https://github.com/airbnb/viaduct/commit/351b8b90)) by @catacraciun

## Bug Fixes

- Corrects a package prefix collision in `ClassGraphScanner` where `com.example.foo` would incorrectly match `com.example.foobar` due to a plain `startsWith(pkg)` check. ([f2d1df88](https://github.com/airbnb/viaduct/commit/f2d1df88)) by @catacraciun

## Performance Improvements

- Batch-await cell slot Values in SyncEngineObjectDataFactory ([3b73309a](https://github.com/airbnb/viaduct/commit/3b73309a)) by @vickeyyeh

## Documentation

- Clarifies the resolver annotation docs ([95cd739b](https://github.com/airbnb/viaduct/commit/95cd739b)) by @gummybug

## Testing

- Align contract test packages and remove duplicate standalones ([30b329e4](https://github.com/airbnb/viaduct/commit/30b329e4)) by @rstata
- Move each feature-app test contract into its own package ([0cc6f3d0](https://github.com/airbnb/viaduct/commit/0cc6f3d0)) by @rstata
- Remove 4 legacy Java FeatureApp tests that are now fully covered by their ContractTest equivalents ([554673e8](https://github.com/airbnb/viaduct/commit/554673e8)) by @catacraciun

## Refactoring

- Refactor ParsedSelections ([08520288](https://github.com/airbnb/viaduct/commit/08520288)) by @gummybug
- Move Fields object from Reflection to GRT class level ([d65836d7](https://github.com/airbnb/viaduct/commit/d65836d7)) by @amity177
- Rename error handler functions for clarity ([65b85b22](https://github.com/airbnb/viaduct/commit/65b85b22)) by @fireboy1919

## Build System

- Enhance setup-build composite action with checkout and standardize all workflows ([540dcaa1](https://github.com/airbnb/viaduct/commit/540dcaa1)) by @geovannefduarte
- Centralize CI alerting and establish workflow architecture ([#311](https://github.com/airbnb/viaduct/pull/311)) by @rstata
- Add validate_release_state.py pure branch/version validation helper ([5dd78f2a](https://github.com/airbnb/viaduct/commit/5dd78f2a)) by @geovannefduarte
- Add Sonatype snapshot repo support to demoapp settings ([a571ee42](https://github.com/airbnb/viaduct/commit/a571ee42)) by @geovannefduarte
- Add confirmDemoAppVersions and setReleaseCandidateVersion tasks ([7ad9d6f0](https://github.com/airbnb/viaduct/commit/7ad9d6f0)) by @geovannefduarte
- Restore checkout before setup-build composite action calls ([66c668bd](https://github.com/airbnb/viaduct/commit/66c668bd)) by @geovannefduarte

- Propagate test and BCV exit codes so CI failures are visible ([01794580](https://github.com/airbnb/viaduct/commit/01794580)) by @geovannefduarte

- Remove configuration-time filesystem probing ([cefe431d](https://github.com/airbnb/viaduct/commit/cefe431d)) by @rstata
- Split plugin-authoring Kotlin conventions; consolidate Maven Central orchestration ([aa6588a6](https://github.com/airbnb/viaduct/commit/aa6588a6)) by @rstata
- Remove dead build scan artifact pipeline ([5b80791d](https://github.com/airbnb/viaduct/commit/5b80791d)) by @geovannefduarte
- Rename post-slack-alerts.yml to post-alerts.yml ([c4d9d042](https://github.com/airbnb/viaduct/commit/c4d9d042)) by @geovannefduarte
