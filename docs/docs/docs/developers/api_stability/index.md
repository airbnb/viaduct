---
title: API Stability
description: API Stability in Viaduct
---


### Purpose and scope

This document explains:

- What each stability annotation means for adopters.
- How Kotlin opt-in and deprecation interact with those annotations.

### Public surface: what counts as “public API”

Viaduct’s *external public surface* is defined by:

1. **Kotlin visibility** (what is technically `public` / visible outside the module), and
2. **Designated public packages** that are intended for consumer use.

Everything outside those canonical public packages should be treated as implementation detail even if it is `public` in Kotlin.

## Stability levels

### Stability table (consumer view)

| Annotation | Meaning | Who should use it | Guarantees |
|---|---|---|---|
| `@StableApi` | Long-term public contract | All consumers | Binary compatible within a major version; removal via deprecation |
| `@ExperimentalApi` | Public but evolving (opt-in) | Early adopters | May change or be removed in any release |
| `@Deprecated` | Scheduled for replacement/removal | Everyone | Migration path documented; removal in a future release |
| `@InternalApi` | Not a consumer contract | Viaduct developers only | No stability guarantee |
| `@TypeInferenceApi` | Framework-internal, type-inference support only | Do not use directly | Unreliable; may change or be removed without notice. No opt-in required because generated code implements these interfaces transparently — consumers never reference them directly. |
| `@VisibleForTest` | Viaduct internal use for tests | This annotation is only used on internal Viaduct tests that for some reason needs to be published in public packages |

### Practical guidance

- Prefer `@StableApi` when possible; build shared abstractions on top of it.
- Use `@ExperimentalApi` only when needed; isolate usage and re-validate during upgrades.
- Avoid `@InternalApi` in production; it may change or disappear without deprecation.
- Never use `@TypeInferenceApi` directly; it exists to help the Kotlin compiler resolve types in generated code.
- Treat `@Deprecated` as a migration signal; expect removals only in major versions.

## Opt-in behavior

Some stability annotations are built on Kotlin’s `@RequiresOptIn` and therefore affect compilation at usage sites:

- `@ExperimentalApi` surfaces as a **warning** unless you opt in.
- `@InternalApi` surfaces as a **warning** unless you opt in.
- `@TypeInferenceApi` does not require opt-in.
- `@StableApi` does not require opt-in.

## Upgrade expectations

When upgrading Viaduct:

1. **Stable APIs** should remain binary compatible within a major version; breaking changes should be coordinated via deprecation and versioning.
2. **Experimental APIs** may change between releases; re-test and re-check call sites that opted in.
3. **Internal APIs** may break without warning; if you opted in, you own the risk.
4. **Type-inference APIs** may change or disappear without notice; do not reference them directly.
5. **Deprecated APIs**: follow the migration guidance and plan to remove usage before the next major bump.

