---
date: 2025-12-24
categories:
  - Releases
description: Release notes for Viaduct 0.17.0.
---

# Release 0.17.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.17.0).

<!-- more -->

## Breaking changes

- Move TenantAPIBootstrapper interface to service/api/spi as a generic type and remove deprecated Viaduct.getSchema() method. ([2f3f631c](https://github.com/airbnb/viaduct/commit/2f3f631c)) by @geovannefduarte
- Refactor error handling API to improve package organization and remove DataFetchingEnvironment dependency from public API signatures ([cb7696cb](https://github.com/airbnb/viaduct/commit/cb7696cb)) by @geovannefduarte

## Features

- Fast schema file format ([#254](https://github.com/airbnb/viaduct/pull/254)) by @rstata

## Bug Fixes

- Fix the release workflow failure in airbnb/viaduct where detektCustomRules was failing due to unmatched exclude patterns. ([d0999afb](https://github.com/airbnb/viaduct/commit/d0999afb)) by @fireboy1919

## Documentation

- Add some markdown files for agents ([20886008](https://github.com/airbnb/viaduct/commit/20886008)) by @rstata

## Refactoring

- Moves utilities that can be used for build-time fragment validation out of engine/api and into a share utils directory ([b552bcf1](https://github.com/airbnb/viaduct/commit/b552bcf1)) by @gummybug

## Chores

- Add pycache artifacts to gitignore file by @ryan.tanner
- Enables loading mock GRT objects from Niobe-generated JSON for testing @Resolver implementations. ([3e8ec0e9](https://github.com/airbnb/viaduct/commit/3e8ec0e9)) by @pclowes
- Moves Flags as Seal interface inside Flag Manager ([89090a58](https://github.com/airbnb/viaduct/commit/89090a58)) by @kristileka
- Decouple `apiCheck` execution from the regular `gradle check` ([70c0bb5c](https://github.com/airbnb/viaduct/commit/70c0bb5c)) by @nmarsollier
