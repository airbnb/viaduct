---
title: Contributors Guide
description: API Stability Annotations in Viaduct
---

## Canonical stability annotations

The api stability rules are defined in tenant/api (including generated code), service/api and service/wiring.

A declaration may be annotated with **exactly one** of the following stability annotations:
- `@StableApi`
- `@ExperimentalApi`
- `@InternalApi`
- `@VisibleForTest`

Having more than one stability annotation on the same declaration is invalid and enforced by a Detekt rule. 

> Terminology: in this document, “declaration” includes classes, functions, properties, constructors, nested types, and type aliases.

## One stability annotation per declaration

To keep semantics unambiguous, Viaduct enforces:

- Each declaration may have **at most one** of `@StableApi`, `@ExperimentalApi`, `@InternalApi`, or `@VisibleForTest`.
- Multiple stability annotations on the same declaration are invalid and are enforced by tooling.

## `@Deprecated` and lifecycle transitions

`@Deprecated` is treated as a lifecycle state; when an API becomes deprecated, the previous stability annotation is removed (not combined).  Represents a lifecycle state (migration signal) for an API that was previously stable and is being retired.

Project policy for public-facing APIs:
- Deprecated APIs **do not** carry another stability annotation in combination.
- When an API becomes deprecated, the previous stability annotation is removed.

### Practical rule of thumb

- If it is deprecated, annotate it with `@Deprecated(...)` and **do not** keep `@StableApi` / `@ExperimentalApi` on the same declaration.
- If you still want to communicate “this was stable”, do that in the deprecation `message` and/or KDoc.

## Quick decision tree

1. **Is this intended to be used by consumers (tenant services / external adopters)?**
   - Yes → `@StableApi` or `@ExperimentalApi`.  
   - No → go to #2.

2. **Is this only present to support Viaduct’s own tests/fixtures/diagnostics, but must ship in a production artifact?**
   - Yes → `@VisibleForTest`. 
   - No → `@InternalApi`. 

3. **Is the API being retired?**
   - Yes → `@Deprecated(...)` (and remove the stability annotation).

## Valid combinations (and where they apply)

### Per-declaration rules

| What you want | Valid? | How |
|---|---:|---|
| Mark something stable | ✅ | Apply `@StableApi` on the declaration |
| Mark something experimental | ✅ | Apply `@ExperimentalApi` on the declaration |
| Mark something internal-only | ✅ | Apply `@InternalApi` on the declaration |
| Mark something test-only | ✅ | Apply `@VisibleForTest` on the declaration |
| Multiple stability annotations on one declaration | ❌ | Detekt failure |
| Deprecate a previously stable API and keep `@StableApi` | ❌ | Use only `@Deprecated(...)` |

### Enclosing scope behavior (coverage + BCV)

**Enclosing stability counts as coverage for Detekt**:
- A member is considered “covered” if it has a stability annotation itself **or** any enclosing declaration has one.

**Enclosing non-public markers affect BCV `.api` output**:
- A declaration appears in `.api` dumps only if it is public/protected **and** neither it nor any enclosing declaration has a `nonPublicMarker` (such as `@InternalApi` or `@VisibleForTest`). 

### Overrides and private declarations

The Detekt rule ignores:
- private declarations,
- overrides (stability is defined by the base declaration),
- local declarations inside functions.

## Practical examples

### Example A: Stable public class with an internal helper

```kotlin
@StableApi
class PublicController {

    fun publicEndpoint() {} // OK: covered by @StableApi on the class

    @InternalApi
    fun internalHelper() {}  // OK: member has its own stability
}
```

The Detekt rule will not complain about the stable member coverage pattern shown above.

**What happens:**
- `PublicController` and `publicEndpoint()` are part of the tracked public surface (BCV will dump them).
- `internalHelper()` is treated as non-public for BCV (because `@InternalApi` is a non-public marker). 

### Example B: Internal class filters out all its members from `.api`

```kotlin
@InternalApi
class InternalOnlyService {
    fun methodA() {}
    fun methodB() {}
}
```

BCV behavior:
- the class and its members do not appear in `.api` dumps because the enclosing class is a `nonPublicMarker`. 

### Example C: Experimental API (opt-in warning for consumers)

```kotlin
@ExperimentalApi
fun newCapability(): String = "v2"
```

Call-site behavior without opt-in:
- `@ExperimentalApi` is typically `@RequiresOptIn(level = WARNING)`, so consumers see a compiler warning unless they opt in.

Consumer-like usage:

```kotlin
@OptIn(ExperimentalApi::class)
fun useIt() {
    newCapability()
}
```

### Example D: `@VisibleForTest` is stricter than experimental

`@VisibleForTest` uses `@RequiresOptIn(level = ERROR)` to express “tests only”.

```kotlin
@VisibleForTest
fun internalTestHook() { /* ... */ }
```

A consumer-like module calling it without opt-in gets a **compiler error**, not a warning.

### Example E: Deprecating a stable API

When retiring a stable API, replace its stability annotation with `@Deprecated(...)`. 

```kotlin
@Deprecated(
    message = "Use newFoo() instead.",
    replaceWith = ReplaceWith("newFoo()")
)
fun oldFoo() { /* ... */ }

@StableApi
fun newFoo() { /* ... */ }
```

## Invalid examples (and why)

### Invalid: Two stability annotations on one declaration

```kotlin
@InternalApi
@ExperimentalApi
fun dangerousExperimentalHelper() = Unit
```

This is disallowed; even though Kotlin would effectively require both opt-ins and `Level.ERROR` would dominate, Viaduct enforces “pick one stability annotation per declaration.”

## How opt-in is configured across Viaduct modules

Kotlin compiler `-opt-in=...` flags act like module-wide `@OptIn`.

Typical configuration:

- **Internal Viaduct modules** opt in to `InternalApi`, `ExperimentalApi`, and `VisibleForTest` to avoid scattering `@OptIn` in the implementation.
- **Consumer-like code** (including demo apps and `testFixtures`) must use `@OptIn` explicitly for internal/experimental usage.
