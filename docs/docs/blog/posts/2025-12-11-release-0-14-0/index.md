---
date: 2025-12-11
categories:
  - Releases
description: Release notes for Viaduct 0.14.0.
---

# Release 0.14.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.14.0).

<!-- more -->

## New Features

- feat(API Stability): Adding annotations for api stability. ([e4b2f480](https://github.com/airbnb/viaduct/commit/e4b2f480)) by @nmarsollier
- feat(release): Validation using `VERSION` file. ([ab354195](https://github.com/airbnb/viaduct/commit/ab354195)) by @gokhan-ozgozen

## Fixes

- fix: remove some dead dependencies ([8c706fc4](https://github.com/airbnb/viaduct/commit/8c706fc4)) by @rstata
- fix: report typecheck errors with field metrics ([b96f201c](https://github.com/airbnb/viaduct/commit/b96f201c)) by @vickeyyeh
- fix(viaduct): restore testCodeCoverageVerification task for CI/CD ([b3eeef68](https://github.com/airbnb/viaduct/commit/b3eeef68)) by @fireboy1919

## Test improvements

- test: Add support for mocking ctx.mutation in resolver unit tests. ([20f8a459](https://github.com/airbnb/viaduct/commit/20f8a459)) by @gummybug
- test: Added more graphQL schema edge case tests ([896b293f](https://github.com/airbnb/viaduct/commit/896b293f)) by @catacraciun
- test: Add parameterized schema-driven tests for Viaduct bytecode generation to validate code generation ([c4fbdc46](https://github.com/airbnb/viaduct/commit/c4fbdc46)) by @catacraciun

## Doc improvements

- docs: fix badge links in readme to point to sonatype/gradle artifacts ([#236](https://github.com/airbnb/viaduct/pull/236)) by @ryan.tanner
- docs: Break out release runbook and update it ([#235](https://github.com/airbnb/viaduct/pull/235)) by @raymie.stata

## Misc improvements

- chore: Fixing dokka formatting issues ([#243](https://github.com/airbnb/viaduct/pull/243)) by @johannes.tuchscherer
- chore: Remove compilation warnings for Viaduct OSS... ([f9811845](https://github.com/airbnb/viaduct/commit/f9811845)) by @geovannefduarte
- chore: Removes the following dispatcher types: ([6a582548](https://github.com/airbnb/viaduct/commit/6a582548)) by @kristileka
- chore: Moves the following files to engine/runtime ([1ba27910](https://github.com/airbnb/viaduct/commit/1ba27910)) by @kristileka
- chore(viaduct/engine): remove registry parameters from ExecutionParameters.Constants and Factory, refactor DispatcherRegistry ([d3b5c24b](https://github.com/airbnb/viaduct/commit/d3b5c24b)) by @skevy
- chore(viaduct/engine): context passing cleanup for subquery execution ([a332a918](https://github.com/airbnb/viaduct/commit/a332a918)) by @skevy
- build: only run scheduled release job on airbnb/viaduct repo ([#240](https://github.com/airbnb/viaduct/pull/240)) by @ryan.tanner
- chore: move inputType() methods to internal pa... ([5d607ee4](https://github.com/airbnb/viaduct/commit/5d607ee4)) by @fireboy1919
- refactor: a byproduct from Viaduct internal refactoring. We now only need typeName and fieldName in TenantNameModule. ([886c20fe](https://github.com/airbnb/viaduct/commit/886c20fe)) by @junjinp
- chore: simplify schema registration and builde... ([3ca9ec7c](https://github.com/airbnb/viaduct/commit/3ca9ec7c)) by @fireboy1919
- chore: simplify to use VERSION file directly ([0765b8e4](https://github.com/airbnb/viaduct/commit/0765b8e4)) by @fireboy1919
