# Viaduct API Annotations Guide

This document explains how API annotations interact with:

- Kotlin visibility (`private`, `internal`, `public`)
- Kotlin’s opt-in mechanism (`@RequiresOptIn` / `@OptIn`)
- Binary Compatibility Validator (BCV) and `.api` baselines

## Overview

Viaduct uses the following annotations as part of its stability model:

- `@StableApi`
- `@ExperimentalApi`
- `@InternalApi`
- `@TestingApi`
- `@Deprecated` (standard Kotlin annotation with Viaduct-specific lifecycle rules)

These annotations sit on top of normal Kotlin visibility and act as **semantic
markers**. They serve three purposes:

1. Communicate **stability level** and **intended audience**.
2. Control which symbols are tracked in **BCV `.api` baselines**.
3. Make “risky” usage visible via **opt-in** (where applicable).

At a high level, our BCV configuration treats:

- `@StableApi` and `@Deprecated` symbols as **public, tracked** API.
- `@InternalApi`, `@TestingApi`, and `@ExperimentalApi` as **non-public markers**
  that are **excluded** from `.api` baselines. Changes to these do not cause BCV
  failures.

## Quick reference 

- Use **visibility** (`private`, `internal`, `public`) as the first line of
  encapsulation.
- Use **annotations** to express intended audience and stability:
  - `@StableApi` for long-term, tracked public contracts.
  - `@ExperimentalApi` for public-but-evolving surfaces, excluded from BCV until
    they mature.
  - `@InternalApi` for framework internals that happen to be visible.
  - `@TestingApi` for test/diagnostic/tooling hooks, visible but not part of the
    public surface.
  - `@Deprecated` for previously stable APIs that are being phased out.

# Annotation details

## `@StableApi`

### Purpose

`@StableApi` marks APIs that are part of Viaduct’s **supported public surface**.

These are the symbols that:

- Tenant applications and other Viaduct consumers are **expected** to call
  directly.
- We want to keep **binary compatible** within a major version.
- Are **persisted** into BCV `.api` baselines and validated on every change.

A change to a `@StableApi` symbol is treated as a **contract change**. Examples:

- Removing a `@StableApi` function or class.
- Changing its parameter types, return type, or type parameters.
- Changing visibility of a `@StableApi` type that consumers may reference.

These changes will:

- Show up as diffs in the `.api` files.
- Cause `apiCheck` / `runApiCheck` to fail until `.api` baselines are updated.
- Require a conscious decision about versioning (typically a major version
  bump).

### Usage guidelines

Use `@StableApi` when:

- The type or member is intended as a **long-term contract** for Viaduct
  consumers.
- You are prepared to treat incompatible changes as breaking and coordinate them
  via deprecation and versioning.

Promotion path:

- New APIs should usually start as `@ExperimentalApi`.
- Once they stabilise, re-annotate them as `@StableApi` and let BCV begin
  tracking them.

### Example

```kotlin
/**
 * Context passed to resolvers to access arguments and environment data.
 */
@StableApi
interface ResolverExecutionContext {
    fun <T> argument(name: String): T?
    fun hasArgument(name: String): Boolean
}
```

## `@InternalApi`

### Purpose

`@InternalApi` marks symbols that are **technically visible** (`public` or
`internal`) but are considered **framework internals**, not part of the
supported public API.

Typical reasons for `@InternalApi`:

- The symbol must be `public` or `internal` due to:
  - Reflection or runtime discovery
  - Dependency injection
  - Cross-module wiring
- Conceptually, it is not an API we want application developers to call or
  implement directly.
- We want freedom to change or remove it without treating this as a break to the
  external contract.

In BCV configuration:

- `@InternalApi` is treated as a **non-public marker**.
- These symbols are **excluded** from `.api` baselines.
- Evolving them does **not** require a major version bump.

As an opt-in marker:

- Using `@InternalApi` symbols from other modules requires
  `@OptIn(InternalApi::class)`, which makes usage visible to reviewers.

### Usage guidelines

Use `@InternalApi` when:

- The element must be visible across module boundaries, **and**
- It is not part of the public contract for Viaduct consumers.

Keep internal implementation code outside canonical public packages where
feasible. If an internal element must live in a public package, always annotate
it `@InternalApi`.

### Example

```kotlin
/**
 * Internal factory used by the engine to construct selection sets.
 * Not intended for tenant application use.
 */
@InternalApi
class SelectionSetFactory(
    private val schema: GraphQLSchema,
) {
    fun createForField(field: GraphQLFieldDefinition): SelectionSet {
        // ...
    }
}
```

## `@TestingApi`

### Purpose

`@TestingApi` marks symbols that exist primarily to support **Viaduct’s own
tests, diagnostics, or related tooling**.

These symbols often live in `src/main` instead of `src/test` because:

- Tests in other modules need to reuse them, or
- They act as shared fixtures or hooks for integration-style tests.

However, they are **not meant for tenant use**, even if they are technically
`public`. From the perspective of Viaduct consumers, `@TestingApi` should be
treated as internal-only.

In BCV configuration:

- `@TestingApi` is a **non-public marker**.
- `@TestingApi` symbols are **excluded** from `.api` baselines.
- Changing or removing them does not impact BCV.

### Usage guidelines

Use `@TestingApi` when:

- The element exists only to support Viaduct tests, diagnostics, or tooling.
- It is not a candidate for the external public surface, even if it must ship
  in a production artifact.

Guidelines for Viaduct developers:

- Prefer moving test helpers into dedicated test-only modules when practical.
- If a testing helper must ship in a main artifact, annotate it `@TestingApi`
  to make that intent explicit.
- External consumers should not rely on `@TestingApi` APIs; for them, the
  relevant stability levels are `@StableApi`, `@ExperimentalApi`,
  `@InternalApi`, and `@Deprecated`.

Conceptually, `@TestingApi` is similar to JetBrains’ `@TestOnly`, but integrated
into Viaduct’s stability vocabulary.

### Example

```kotlin
/**
 * Utility class for testing purposes, used to expose otherwise-internal methods to test code.
 */
@TestingApi
object ObjectBaseTestHelpers {
  /**
   * Similar to [ObjectBase.Builder.put], but allows setting an alias for the field.
   * This is primarily for testing purposes to ensure that the aliasing works correctly.
   */
  fun <T, R : ObjectBase.Builder<T>> putWithAlias(
    builder: R,
    name: String,
    alias: String,
    value: Any?
  ): R {
    builder.put(name, value, alias)
    return builder
  }
}
```

## `@ExperimentalApi`

### Purpose

`@ExperimentalApi` marks APIs that are **under active design**. These are
symbols that we want to expose to early adopters, but we explicitly reserve the
right to:

- Change their shape in backward-incompatible ways, or
- Remove them altogether, even in minor releases.

The goals of `@ExperimentalApi` are to:

- Allow experimentation and feedback collection without committing to long-term
  stability.
- Make usage of these APIs **visible and intentional** through opt-in.
- Keep them out of the BCV-tracked stable surface until they are ready.

In BCV configuration:

- `@ExperimentalApi` is a **non-public marker**.
- Experimental symbols are excluded from `.api` baselines.
- When an experimental API matures, it is re-annotated as `@StableApi`, at which
  point changes will be tracked by BCV.

As an opt-in marker:

- Callers are required to use `@OptIn(ExperimentalApi::class)` (or the
  corresponding compiler flag), acknowledging that the API may change.

### Usage guidelines

Use `@ExperimentalApi` when:

- You are introducing a new feature or surface and want to gather feedback.
- The design is not yet settled and may change.
- You want to discourage casual adoption while still allowing opt-in usage.

Typical lifecycle:

1. Introduced as `@ExperimentalApi`.
2. Used by early adopters with explicit opt-in.
3. Once stable, re-annotated as `@StableApi` and included in BCV baselines.

### Example

```kotlin
/**
 * Experimental extension point for custom query logging.
 * API is not stable and may change or be removed.
 */
@ExperimentalApi
interface QueryLoggingHook {
    fun onQueryStarted(rawQuery: String)
    fun onQueryCompleted(rawQuery: String, durationMs: Long)
}
```

## `@Deprecated`

### Purpose

`@Deprecated` is the standard Kotlin annotation, but Viaduct uses it with a
specific lifecycle rule in its stability model:

- It represents a **distinct state** for APIs that were previously part of the
  stable, public surface and are now being retired.
- A typical transition is:
  - `@StableApi` → `@Deprecated` → removed in a future release.

By convention in Viaduct:

- `@Deprecated` is used **without combining it** with other stability
  annotations.
- When an API becomes deprecated, its previous stability annotation (for
  example `@StableApi`) is removed and replaced by `@Deprecated`.

In BCV terms:

- Deprecated APIs remain part of the tracked binary surface as long as they
  exist.
- Removing a `@Deprecated` API is a structural change and will be caught by BCV.

### Usage guidelines

Use `@Deprecated` when:

- A previously stable API is being replaced by a better alternative, and
  consumers need time to migrate.
- You want to communicate that an API is on a path to removal and provide a
  migration path.

Guidelines:

- Always include a `message` and, when feasible, a `replaceWith` expression to
  guide migrations.
- Coordinate removals with BCV checks and versioning decisions.
- Focus deprecation on APIs that have reached a stable state; experimental APIs
  that did not work out can usually be removed directly or, where appropriate,
  explicitly deprecated if they are widely used.

### Example

```kotlin
@Deprecated(
    message = "Use NewResolverContext instead.",
    replaceWith = ReplaceWith("NewResolverContext")
)
interface ResolverContext {
    // ...
}
```

## Relationship with BCV and `.api` baselines

BCV operates purely at the level of **binary structure**. It does not know about
semantics, stability levels, or intended audience. Our annotations bridge that
gap for Viaduct developers.

### What BCV tracks

In BCV-participating modules:

- Public symbols that are **not** marked with a non-public marker
  (`@InternalApi`, `@TestingApi`, `@ExperimentalApi`) are candidates for
  inclusion in `.api` baselines.
- In practice, the public, stable surface consists of:
  - `@StableApi` APIs.
  - `@Deprecated` APIs that were previously stable and are kept for migration.

Any structural change to these symbols:

- Shows up as a diff in the `.api` files.
- Causes `apiCheck` (or wrapper tasks like `runApiCheck`) to fail until baselines
  are updated or the change is reverted.

### What BCV ignores

Symbols marked with:

- `@InternalApi`
- `@TestingApi`
- `@ExperimentalApi`

are configured as **non-public** for BCV purposes and are excluded from
baselines. These can change freely from BCV’s perspective.

This means:

- You can iterate quickly on internals and experimental surfaces without
  triggering BCV failures.
- You still need to consider actual usage: experimental APIs may have real
  consumers and changes can still break them, even if BCV is silent.
