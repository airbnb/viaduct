---
date: 2026-02-06
categories:
  - Releases
description: Release notes for Viaduct 0.23.0.
---

# Release 0.23.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.23.0).

<!-- more -->

## Breaking Changes

- use ctx.query(selections: String, variables: Map<String, Any?>) instead. by @gummybug

## Features

- Add marker interface for Fields generated class. This enables use of type-safe functions and utilities to consumers. ([4711c395](https://github.com/airbnb/viaduct/commit/4711c395)) by @alexanderuv
- add `PageInfo` type support for Relay Connection specification ([a74984a3](https://github.com/airbnb/viaduct/commit/a74984a3)) by @geovannefduarte
- Implemented Java Resolvers codegen ([9ae33ace](https://github.com/airbnb/viaduct/commit/9ae33ace)) by @catacraciun
- Add a version of ctx.query and ctx.mutation that takes the selection set string and variables directly, rather than the `SelectionSet` type. Usage is `ctx.query("foo", vars)` instead of `ctx.query(ctx.selectionsFor(Query.Reflection, "foo", vars))`, which reduces boilerplate. ([8f97abac](https://github.com/airbnb/viaduct/commit/8f97abac)) by @gummybug
- Add enum exerciser tests for Java codegen ([316beb7e](https://github.com/airbnb/viaduct/commit/316beb7e)) by @catacraciun

## Bug Fixes

- constrain DateTime and Date generators to Long millis range ([1d4e0a83](https://github.com/airbnb/viaduct/commit/1d4e0a83)) by @viaduct-maintainers

## Chores

- tenant public API file sync. ([5842932e](https://github.com/airbnb/viaduct/commit/5842932e)) by @gokhan-ozgozen

## Build System

- Adds a new root type configuration to application plugin. ([bbf63bde](https://github.com/airbnb/viaduct/commit/bbf63bde)) by @kristileka
