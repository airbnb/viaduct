---
date: 2026-01-29
categories:
  - Releases
description: Release notes for Viaduct 0.22.0.
---

# Release 0.22.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.22.0).

<!-- more -->

## Features

- This PR adds the foundational connection interfaces to `tenant/api`, establishing the core types needed. ([9414baef](https://github.com/airbnb/viaduct/commit/9414baef)) by @kristileka
- Introduces ClassGraphScanner, a centralized class graph scanning utility with caching support ([a75c21a4](https://github.com/airbnb/viaduct/commit/a75c21a4)) by @junjinp
- Explicitly differentiate between Selective and Non-selective resolvers. ([ae5e18d2](https://github.com/airbnb/viaduct/commit/ae5e18d2)) by @alexanderuv
- add `@connection` and `@edge` directives to default schema ([91d3f2b8](https://github.com/airbnb/viaduct/commit/91d3f2b8)) by @geovannefduarte
- Implement Java GRTs Codegen. Supported types: Enum, Objects, Input types, Interfaces, Unions ([7e6e14a2](https://github.com/airbnb/viaduct/commit/7e6e14a2)) by @catacraciun
- use AppClassLoader instead of URLClassLoader to scan the class graph and load the resolvers, so that we can leverage HotswapAgent properly. ([50125823](https://github.com/airbnb/viaduct/commit/50125823)) by @junjinp

## Bug Fixes

- use correct FQN for nonPublicMarkers annotations ([4faeae63](https://github.com/airbnb/viaduct/commit/4faeae63)) by @gokhan-ozgozen
- Configure shadow jar as main artifact for fat jar modules ([f1abd90e](https://github.com/airbnb/viaduct/commit/f1abd90e)) by @fireboy1919

## Documentation

- Reorganize doc navigation by @ryan.tanner
- add `@connection` and `@edge` directive documentation with schema examples ([9e02c3aa](https://github.com/airbnb/viaduct/commit/9e02c3aa)) by @geovannefduarte
- Add AGENTS/CLAUDE.md ([f37137ec](https://github.com/airbnb/viaduct/commit/f37137ec)) by @rstata

## Refactoring

- Move to coarse grain asynchrony [1/n] ([65f81e3d](https://github.com/airbnb/viaduct/commit/65f81e3d)) by @rstata
- Update mapper creation function name from `map` to `mapperTo` ([8a2f3d64](https://github.com/airbnb/viaduct/commit/8a2f3d64)) by @alexanderuv
- replace graphql-java Value with our own ([8251c3bb](https://github.com/airbnb/viaduct/commit/8251c3bb)) by @rstata

## Chores

- Bump snapshot version by @ryan.tanner
- migrate GitHub org from viaduct-graphql to viaduct-dev ([dd8af6f9](https://github.com/airbnb/viaduct/commit/dd8af6f9)) by @fireboy1919
- Revert "Remove MTD to make code hotswap work with HotswapA... ([af995fd3](https://github.com/airbnb/viaduct/commit/af995fd3)) by @cetinsahin

## Build System

- require API stability annotations in bcv-api modules ([cfe6495a](https://github.com/airbnb/viaduct/commit/cfe6495a)) by @nmarsollier
- Fix broken dokka aggregation in service module by @ryan.tanner
