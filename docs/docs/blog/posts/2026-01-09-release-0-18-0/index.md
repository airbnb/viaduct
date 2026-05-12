---
date: 2026-01-09
categories:
  - Releases
description: Release notes for Viaduct 0.18.0.
---

# Release 0.18.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.18.0).

<!-- more -->

## Features

- Add fat jar bundle modules for easier dependency management ([d41bbf09](https://github.com/airbnb/viaduct/commit/d41bbf09)) by @fireboy1919

## Documentation

- Update Viaduct OSS release runbook preflight + branch naming ([381df8cc](https://github.com/airbnb/viaduct/commit/381df8cc)) by @nmarsollier

## Testing

- Add random schema to ViaductSchema test fixtures ([c15084d6](https://github.com/airbnb/viaduct/commit/c15084d6)) by @rstata

## Refactoring

- Change identifier representation in binary schema format ([75ff75b9](https://github.com/airbnb/viaduct/commit/75ff75b9)) by @rstata

## Chores

- Downgrade ktlint to 1.2.1 to align with treehouse ([b1c0acf6](https://github.com/airbnb/viaduct/commit/b1c0acf6)) by @geovannefduarte
- Clean up kotlin compiler warnings across service modules ([496b4a37](https://github.com/airbnb/viaduct/commit/496b4a37)) by @geovannefduarte
- Moves LazyEngineObjectData to runtime and removes it from NodeEngineObjectData ([2c64bfb8](https://github.com/airbnb/viaduct/commit/2c64bfb8)) by @kristileka
- Add a dedicated Buildkite step to run the binary-compatibility validator ([b05e1c17](https://github.com/airbnb/viaduct/commit/b05e1c17)) by @nmarsollier
- Update error handling docs to reflect DFE-free API ([cbcedaa1](https://github.com/airbnb/viaduct/commit/cbcedaa1)) by @geovannefduarte
- Add synchronization to flaky child plan tests ([8069b91b](https://github.com/airbnb/viaduct/commit/8069b91b)) by @geovannefduarte
- Update CONTRIBUTING.md to reflect current release process ([54c2d717](https://github.com/airbnb/viaduct/commit/54c2d717)) by @rstata
- Type EngineExecutionContext.globalIDCodec as GlobalIDCodec ([416bd83f](https://github.com/airbnb/viaduct/commit/416bd83f)) by @geovannefduarte
- Remove mock GlobalID classes in favor of real implementations ([65889560](https://github.com/airbnb/viaduct/commit/65889560)) by @geovannefduarte
