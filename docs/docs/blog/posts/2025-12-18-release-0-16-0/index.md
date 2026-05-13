---
date: 2025-12-18
categories:
  - Releases
description: Release notes for Viaduct 0.16.0.
---

# Release 0.16.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.16.0).

<!-- more -->

## Breaking Changes

- support mapping projected types ([17618bea](https://github.com/airbnb/viaduct/commit/17618bea)) by @jbellenger

## Features

- add Viaduct Development Server with GraphiQL by @walter.phillips
- add GraphiQL HTML generator with Viaduct customizations by @walter.phillips
- add TenantRuntimeModule for runtime schema access ([a9123533](https://github.com/airbnb/viaduct/commit/a9123533)) by @skevy

## Bug Fixes

- Remove batchResolve from mutation resolver base class ([46a86e71](https://github.com/airbnb/viaduct/commit/46a86e71)) by @gummybug
- Ensures that executed checker RSSes are always part of the query plan ([964a1fcd](https://github.com/airbnb/viaduct/commit/964a1fcd)) by @gummybug

## Chores

- Migrate GlobalIDCodec from tenant to servic... ([d9e779da](https://github.com/airbnb/viaduct/commit/d9e779da)) by @geovannefduarte
- Fix double wrapping failure ([86903119](https://github.com/airbnb/viaduct/commit/86903119)) by @pclowes
- Adds @TestingApi to testing apis ([33ef3acf](https://github.com/airbnb/viaduct/commit/33ef3acf)) by @kristileka
- Change stability of GRTDomain and JsonDomain ([53219421](https://github.com/airbnb/viaduct/commit/53219421)) by @nmarsollier
- More binary schema testing (plus bug fix) ([d5a89fbb](https://github.com/airbnb/viaduct/commit/d5a89fbb)) by @rstata
- Convert RequiredSelectionSet from data class to regular class ([a8c9e4aa](https://github.com/airbnb/viaduct/commit/a8c9e4aa)) by @gummybug
- Introduce stability annotations on public t... ([e60ada4f](https://github.com/airbnb/viaduct/commit/e60ada4f)) by @nmarsollier
- Introduce stability annotations on public s... ([5e54777e](https://github.com/airbnb/viaduct/commit/5e54777e)) by @nmarsollier
- Build GraphQLSchema from ViaductSchema ([3b3e38a4](https://github.com/airbnb/viaduct/commit/3b3e38a4)) by @rstata
- adding more debug logs for access check execution ([3115493f](https://github.com/airbnb/viaduct/commit/3115493f)) by @amity177
- Extract ErrorMetadata and add graphql-java-... ([648ce086](https://github.com/airbnb/viaduct/commit/648ce086)) by @geovannefduarte
- update release notes by @jtuchscherer

## Continuous Integration

- Categorize and group changes by type in changelog by @ryan.tanner
- Create Github Workflow to check binary stability by @nestorruben.marsollier
- remove duplicative sections from pull request template by @ryan.tanner
