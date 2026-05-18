---
title: API Stability
description: Stability annotations for Viaduct APIs.
---

## What you can count on

The following packages make up Viaduct's public API for the purposes of semver and breaking changes:

- **`viaduct.api.*`** — The full tenant API and all generated code, **excluding `viaduct.api.internal`**
- **`viaduct.service`** — Top-level service types (e.g. `ViaductBuilder`)
- **`viaduct.service.wiring`** — Service wiring
- **`viaduct.service.api.*`** — The service embedding API

Within these packages, any individual declaration annotated `@InternalApi` is also excluded from the public API. These are rare exceptions — in the tenant API the goal is to confine truly internal things to `viaduct.api.internal`, but a handful of declarations in `viaduct.api.*` carry `@InternalApi` directly.

Anything annotated `@ExperimentalApi` is also in the public API surface, but its shape may still evolve — see below.

## What you should not count on

Everything outside those packages is considered Viaduct-internal and can change at any time without a semver major bump. This includes the engine, all runtime packages, and tenant runtime infrastructure. The `@InternalApi` annotation may appear on declarations in those packages, but it is redundant there — they are already internal by virtue of their package, not their annotation.

## Stability annotations

Within the public API surface, every declaration carries exactly one stability annotation:

| Annotation | What it means for you |
|---|---|
| `@StableApi` | Safe to depend on. Safe to depend on. Will not change without a deprecation cycle |
| `@ExperimentalApi` | The API is available and intentionally exposed, but its shape may still change. You can use it — you'll get a compiler warning at call sites unless you opt in with `@OptIn(ExperimentalApi::class)`. Watch upgrade notes when updating Viaduct. |
| `@InternalApi` | Not part of the public API, even if it appears in an otherwise-public package. Do not use the internal API, they are subject to change at any release. |
| `@Deprecated` | Being retired. Follow the migration guidance in the deprecation message and plan to remove usage before the next major release. |
| `@TypeInferenceApi` | Same as `@InternalApi` - these are not part of the public API - but they are linted differently for type-inference reasons. |

## Practical guidance

**New APIs with an uncertain shape:** Prefer `@ExperimentalApi` over `@StableApi` until the design is settled. This gives the API real-world exposure while signaling that breakage is possible.

**Upgrading Viaduct:** If you see a compiler warning for `@ExperimentalApi` usage after an upgrade, check the release notes. If BCV passes, no `@StableApi` surfaces have changed. Experimental APIs can change; stable ones cannot.

**Encountering `@InternalApi` inside `viaduct.api`:** This is a deliberate exception — the declaration is carved out of the compatibility guarantee even though it lives in a normally-public package. Do not depend on it.

**Anything outside the public packages:** Treat it as an implementation detail regardless of what annotations are or aren't on it. Package placement, not annotation presence, is the primary boundary.

For the detailed rules contributors follow when applying these annotations, see [Contributors: API Stability](../../contributors/api_stability/index.md).
