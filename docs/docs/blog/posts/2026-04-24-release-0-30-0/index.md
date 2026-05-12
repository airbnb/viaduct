---
date: 2026-04-24
categories:
  - Releases
description: Release notes for Viaduct 0.30.0.
---

# Release 0.30.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.30.0).

<!-- more -->

## Breaking Changes

- Rename `ResolverExecutionContext.nodeFor` to `nodeRef` ([588b2ffc](https://github.com/airbnb/viaduct/commit/588b2ffc)) by @gummybug

## Features

- File-driven bootstrapping based on KSP. ([5eef105f](https://github.com/airbnb/viaduct/commit/5eef105f)) by @gokhan-ozgozen
- Add total-count-aware pagination helpers for ConnectionArguments API ([e4b61d16](https://github.com/airbnb/viaduct/commit/e4b61d16)) by @kristileka
- Make AssembleTenantModuleConfigFilesTask incremental with unit tests ([3906b242](https://github.com/airbnb/viaduct/commit/3906b242)) by @rstata
- KSP registry-extractor processor and aggregation pipeline spike ([be478800](https://github.com/airbnb/viaduct/commit/be478800)) by @rstata
- Add @InTenantCode and @InFrameworkCode documentation annotations ([b442059b](https://github.com/airbnb/viaduct/commit/b442059b)) by @fireboy1919
- Adds isBatching to @resolver ([70b36697](https://github.com/airbnb/viaduct/commit/70b36697)) by @kristileka
- Add mutation root support for @namespaceType directive ([5d7d15a2](https://github.com/airbnb/viaduct/commit/5d7d15a2)) by @amity177

## Bug Fixes

- Fix race condition in non-selective node resolver cache deduplication ([e56c4daf](https://github.com/airbnb/viaduct/commit/e56c4daf)) by @rstata
- Fix resolution of fromObjectField variables on non-root fields ([2be9b1dc](https://github.com/airbnb/viaduct/commit/2be9b1dc)) by @jbellenger
- Fix engine hangs on subqueries ([72123671](https://github.com/airbnb/viaduct/commit/72123671)) by @jbellenger
- Add missing actions:read permission to orchestrator workflows ([80f597d5](https://github.com/airbnb/viaduct/commit/80f597d5)) by @geovannefduarte

## Documentation

- Adds new docs for connection changes ([c6611756](https://github.com/airbnb/viaduct/commit/c6611756)) by @kristileka
- Clarify variable docs ([d860ede5](https://github.com/airbnb/viaduct/commit/d860ede5)) by @jbellenger
- Update documentation to reflect @resolver(isBatching) API ([e71571ad](https://github.com/airbnb/viaduct/commit/e71571ad)) by @gummybug

## Testing

- Improve stability and debuggability of property tests in shared-mapping module ([17b01aba](https://github.com/airbnb/viaduct/commit/17b01aba)) by @geovannefduarte

## Refactoring

- Remove unused FieldError exception from tenant API ([7df474f4](https://github.com/airbnb/viaduct/commit/7df474f4)) by @geovannefduarte

## Chores

- Update kotlin to 1.9.25 ([260dba66](https://github.com/airbnb/viaduct/commit/260dba66)) by @skevy

## Build System

- Use Central Portal snapshot endpoint for snapshot publications ([#321](https://github.com/airbnb/viaduct/pull/321)) by @geovannefduarte
- Resolve INCLUDED version leak in published POMs for test fixture deps ([27205c73](https://github.com/airbnb/viaduct/commit/27205c73)) by @geovannefduarte
- Remove deprecated CI workflows and migrate triggers to ci-trigger.yml ([#324](https://github.com/airbnb/viaduct/pull/324)) by @geovannefduarte
- Add build-time check that KSP and Kotlin versions align ([1cad3e95](https://github.com/airbnb/viaduct/commit/1cad3e95)) by @rstata
- Rewrite RELEASE-RUNBOOK.md for new workflow architecture ([4e9f57d9](https://github.com/airbnb/viaduct/commit/4e9f57d9)) by @geovannefduarte
