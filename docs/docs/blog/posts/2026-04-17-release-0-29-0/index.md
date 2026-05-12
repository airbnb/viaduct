---
date: 2026-04-17
categories:
  - Releases
description: Release notes for Viaduct 0.29.0.
---

# Release 0.29.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.29.0).

<!-- more -->

## Features

- Adjust class graph result cache expiration time according to the service environment ([0bf7cc90](https://github.com/airbnb/viaduct/commit/0bf7cc90)) by @junjinp
- Adds ConnectionTypeStructureRule, ConnectionEdgeStructureRule, Connec…tionPageInfoRule, and ConnectionArgumentsNullabilityRule covering: @connection must have edges (@edge type) and non-null pageInfo; ([d6d6df13](https://github.com/airbnb/viaduct/commit/d6d6df13)) by @kristileka
- Validation based on KSP. ([1d8ece05](https://github.com/airbnb/viaduct/commit/1d8ece05)) by @gokhan-ozgozen
- Add include_node_definition flag to GenerateDefaultSchema ([ac50ef02](https://github.com/airbnb/viaduct/commit/ac50ef02)) by @viaduct-maintainers
- Replace reflection-based POJO population with a wrapping pattern for Java GRTs, mirroring Kotlin's ObjectBase/InputLikeBase architecture. ([7dc205b3](https://github.com/airbnb/viaduct/commit/7dc205b3)) by @catacraciun
- Instrument resolveSelectionSet results with InstrumentedEngineExecutionContext ([ace367b7](https://github.com/airbnb/viaduct/commit/ace367b7)) by @vickeyyeh
- Migrates connections feature app to contract ([8def936e](https://github.com/airbnb/viaduct/commit/8def936e)) by @kristileka
- Add resolveSelectionSetAsync and extract executeSelectionSet helper ([a09b9628](https://github.com/airbnb/viaduct/commit/a09b9628)) by @catacraciun
- Migrate TenantAPIBootstrapper, TenantPackageFiltering, and PolicyCheck to contract tests ([2cf4e97b](https://github.com/airbnb/viaduct/commit/2cf4e97b)) by @rstata
- Migrate tutorial tests to contract tests ([850ca6cb](https://github.com/airbnb/viaduct/commit/850ca6cb)) by @rstata
- Add `RootCompositeField` reflective type for factory functions ([9a213c71](https://github.com/airbnb/viaduct/commit/9a213c71)) by @amity177
- Add IValidator interface to tenant validation package ([f31ae524](https://github.com/airbnb/viaduct/commit/f31ae524)) by @gokhan-ozgozen
- Add NoCustomDirectivesRule to reject custom directive definitions in schemas ([79915b6c](https://github.com/airbnb/viaduct/commit/79915b6c)) by @kristileka
- Migrate simple feature-app tests to contract tests ([8fea4f3e](https://github.com/airbnb/viaduct/commit/8fea4f3e)) by @rstata
- Extract @TestSchema schemas from compiled bytecode via ASM ([955810db](https://github.com/airbnb/viaduct/commit/955810db)) by @rstata
- Publisher/consumer contract test codegen with Java GRT scalar fixes ([482bf24e](https://github.com/airbnb/viaduct/commit/482bf24e)) by @rstata

## Bug Fixes

- Enforce exception attribution at executor SPI boundaries ([c3334f47](https://github.com/airbnb/viaduct/commit/c3334f47)) by @fireboy1919
- Implement valueToLiteral on DateTimeScalar to fix NPE when used as variable argument ([40c99e5b](https://github.com/airbnb/viaduct/commit/40c99e5b)) by @vickeyyeh
- Prevent generated schemas from introducing circular directive dependencies ([6570ca19](https://github.com/airbnb/viaduct/commit/6570ca19)) by @jbellenger
- Fix instrumentation parity for `getObjectValue()`/`getQueryValue()` callers ([c6b9aa34](https://github.com/airbnb/viaduct/commit/c6b9aa34)) by @vickeyyeh

## Testing

- Migrate BatchResolverErrorHandling to full contract test and add guiceModules() hook ([7e1ca467](https://github.com/airbnb/viaduct/commit/7e1ca467)) by @rstata
- Migrate RootTypesSchemaClause to full contract test ([23c90077](https://github.com/airbnb/viaduct/commit/23c90077)) by @rstata
- Migrate last two schema-holder feature-app tests to testFixtures ([cf834f17](https://github.com/airbnb/viaduct/commit/cf834f17)) by @rstata
- Create contract test base class hierarchy ([1322d246](https://github.com/airbnb/viaduct/commit/1322d246)) by @rstata
- Migrate FeatureTestSchema to @TestSchema contract ([03eead2e](https://github.com/airbnb/viaduct/commit/03eead2e)) by @rstata

## Refactoring

- Enforce exactly-one-parent constraint on @namespaceType using ViaductReverseSchema.inboundFields ([69d9ef08](https://github.com/airbnb/viaduct/commit/69d9ef08)) by @amity177
- Gate sync value computation at ResolverDataFetcher, remove SyncFieldResolverDispatcher ([b6ea487f](https://github.com/airbnb/viaduct/commit/b6ea487f)) by @vickeyyeh
- Introduce InternalEngineExecutionContext to decouple asImpl() from concrete class ([60c06e5a](https://github.com/airbnb/viaduct/commit/60c06e5a)) by @vickeyyeh
- Move @TestSchema annotation and migrate ApiTestSchema ([3b562625](https://github.com/airbnb/viaduct/commit/3b562625)) by @rstata

## Chores

- Remove redundant schema objects jar repacking in schema bytecode generation ([f9fe565c](https://github.com/airbnb/viaduct/commit/f9fe565c)) by @njlynch

## Build System

- Harden CI workflows with permissions, concurrency and pinned action SHAs ([6b1e9b9b](https://github.com/airbnb/viaduct/commit/6b1e9b9b)) by @geovannefduarte
- Add publish-branch.yml atomic workflow for snapshot and release publication ([#316](https://github.com/airbnb/viaduct/pull/316)) by @geovannefduarte
- Extract alert formatting into composite action and isolate Python tests ([#315](https://github.com/airbnb/viaduct/pull/315)) by @geovannefduarte
- Add ci-trigger, demoapps-nightly-check and nightly-build orchestration workflows ([#319](https://github.com/airbnb/viaduct/pull/319)) by @geovannefduarte
- Add push-demoapps and check-published-demoapps atomic workflows ([#318](https://github.com/airbnb/viaduct/pull/318)) by @geovannefduarte
- Promote getObjectValue() and getQueryValue() to stable API ([17171720](https://github.com/airbnb/viaduct/commit/17171720)) by @vickeyyeh
- Add demoapps-ci-check.yml atomic workflow for demoapp CI validation ([#317](https://github.com/airbnb/viaduct/pull/317)) by @geovannefduarte
- Add actions:read to ci-manual-trigger permissions ([c394899b](https://github.com/airbnb/viaduct/commit/c394899b)) by @geovannefduarte
- Add missing actions:read permission to orchestrator workflows ([cec4a94c](https://github.com/airbnb/viaduct/commit/cec4a94c)) by @geovannefduarte
