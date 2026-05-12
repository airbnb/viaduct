---
date: 2026-01-22
categories:
  - Releases
description: Release notes for Viaduct 0.20.0.
---

# Release 0.20.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.20.0).

<!-- more -->

## Breaking Changes
- **Remove ValueMapper API** - The `ValueMapper` class has been removed, having been obsoleted by `Domain` and `Conv` (@ryantanner)

## Features
- **Java-friendly resolver API** - Implemented a Java-friendly API layer for writing Viaduct GraphQL resolvers, allowing Java developers to create resolvers using `CompletableFuture` instead of Kotlin suspend functions (@ryantanner)
- **Type-safe resolver test fixture API** - Add type-safe resolver test fixture API for tenant resolvers (@ryantanner)

## Fixes
- **Demo apps standalone mode** - Resolve standalone mode dependency resolution for demo apps (@ryantanner)

## Refactoring
- **Schema nullability improvements** - Tighten nullability for `Value` types in ViaductSchema (@rstata)
- **Schema type hierarchy simplification** - Series of changes to simplify ViaductSchema type hierarchies and unify schema implementation classes and algorithms (@rstata)

## Documentation
- **Website migration** - Convert website from Hugo/Docsy to MkDocs (@ryantanner)
- **Website fixes** - Fix issues with website nav and pixelated images (@ryantanner)
- **Roadmap updates** - Update public roadmap and fix description of Raymie (@ryantanner)

## Tests
- Added E2E tests for custom root schema types (@ryantanner)

## Chores
- Replace non-inclusive terminology in test files (@rstata)
- Add strict reviewers for API breaking changes (@ryantanner)
