---
date: 2026-01-16
categories:
  - Releases
description: Release notes for Viaduct 0.19.0.
---

# Release 0.19.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.19.0).

<!-- more -->

## Features

- Add subquery execution metric for ExecutionHandle rollout validation ([efd8e105](https://github.com/airbnb/viaduct/commit/efd8e105)) by @skevy
- add `Engine.executeSelectionSet` API with three-tier architecture ([2d4eb1eb](https://github.com/airbnb/viaduct/commit/2d4eb1eb)) by @skevy
- implement QueryPlan.buildFromSelections() for subquery execution ([eedd964c](https://github.com/airbnb/viaduct/commit/eedd964c)) by @skevy
- add foundation for subquery execution via ExecutionHandle ([88e95bd6](https://github.com/airbnb/viaduct/commit/88e95bd6)) by @skevy
- add micronaut-starter with serve-only DI support by @walter.phillips

## Bug Fixes

- Use HEAD instead of release tag for changelog generation ([02930859](https://github.com/airbnb/viaduct/commit/02930859)) by @geovannefduarte
- resolve GraphiQL introspection errors for directives with no arguments by @walter.phillips
- Fetch full history and tags for changelog generation ([d46d7fe4](https://github.com/airbnb/viaduct/commit/d46d7fe4)) by @geovannefduarte

## Documentation

- Add release and announcements sections to website by @ryan.tanner

## Testing

- Add `ctx.query`/`ctx.mutation` feature tests and reorganize test suite ([91505f2c](https://github.com/airbnb/viaduct/commit/91505f2c)) by @skevy

## Chores

- Simplify ViaductSchema type hierarchies [1/n] ([67c4c86e](https://github.com/airbnb/viaduct/commit/67c4c86e)) by @rstata
- Moves checker dispatcher to runtime ([75eb2816](https://github.com/airbnb/viaduct/commit/75eb2816)) by @kristileka
- Deprecate StandardViaduct getEngine and mkEngineExecutionContext ([e8eb9bb2](https://github.com/airbnb/viaduct/commit/e8eb9bb2)) by @geovannefduarte
- Add support for custom root schema types in Viaduct codegen ([78f3130e](https://github.com/airbnb/viaduct/commit/78f3130e)) by @catacraciun

## Continuous Integration

- Install python dependencies in release workflow by @ryan.tanner

## Build System

- integrate binary schema into viaduct build system ([2ace7b44](https://github.com/airbnb/viaduct/commit/2ace7b44)) by @diwas
