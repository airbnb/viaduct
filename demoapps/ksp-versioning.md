# KSP Versioning for Viaduct Maintainers

This document covers what we've learned about KSP version compatibility and the constraints it imposes on the Viaduct Gradle plugins. It's aimed at framework maintainers, not end users.

## KSP Version Format

KSP versions follow the format `<kotlinVersion>-<kspMajor>.<kspMinor>.<kspPatch>`:

- **KSP1**: the KSP portion is `1.0.x` (e.g., `2.0.21-1.0.28`)
- **KSP2**: the KSP portion is `2.0.x` (e.g., `2.2.21-2.0.5`)

The Kotlin version prefix must exactly match the Kotlin compiler version used in the project. This coupling is in the KSP Gradle plugin/implementation, NOT in the processor API.

## KSP1 vs KSP2

KSP1 is a Kotlin compiler plugin that hooks into compiler internals. KSP2 is a rewrite built on the Kotlin Analysis API (K2). From a processor author's perspective:

- The `symbol-processing-api` interfaces are the same (backward compatible).
- KSP2 runs processors in a more isolated classloader (`KspAAWorkerAction`).
- KSP1's last Kotlin version is **2.1.20** (`2.1.20-1.0.32`). For Kotlin 2.2+, only KSP2 is published.
- Both KSP1 and KSP2 are available for Kotlin 2.0.x and 2.1.x (users can choose).

## Processor API Compatibility

The `com.google.devtools.ksp:symbol-processing-api` is backward compatible across KSP1 versions. Google's stated guarantee: old interfaces never change, and processors depend only on the API.

Our processor (`RegistryExtractorProcessor` in `tenant:codegen`) is compiled against `symbol-processing-api:1.9.25-1.0.20` as a `compileOnly` dependency. This single compiled artifact works across all KSP1 AND KSP2 versions we've tested (1.9.24 through 2.2.21).

## The KSP2 Classloader Gotcha

KSP2 runs processors in an isolated classloader separate from the compiler's classloader. This causes `ClassCastException` if the processor (or its dependencies) uses `kotlin-reflect` internals that bridge between classloaders.

Specifically: `jackson-module-kotlin` registers a `KotlinNamesAnnotationIntrospector` that calls `kotlin.reflect.full.KClasses.getMemberProperties()`. Under KSP2's isolation, this fails with:

```
ClassCastException: kotlin.jvm.internal.ClassReference cannot be cast to kotlin.reflect.jvm.internal.KClassImpl
```

**Our fix**: `ResolverParamsJsonCodec` uses a plain `ObjectMapper()` (no Kotlin module) for encoding, since encoding only happens in the KSP processor context. The `jacksonObjectMapper()` (with Kotlin module) is used only for decoding, which happens in the CLI (process-isolated worker JVM, no classloader conflict).

This means: if you add new JSON serialization code that runs inside the KSP processor, do NOT use `jacksonObjectMapper()` or register `KotlinModule`. Use a plain `ObjectMapper` and rely on getter-based serialization.

## Our Plugin's Approach

The Viaduct module plugin does NOT apply KSP itself. The consumer ("service engineer") brings the KSP plugin at whatever version matches their Kotlin compiler. Our plugin:

1. Reacts to `com.google.devtools.ksp` via `pluginManager.withPlugin(...)`.
2. Adds `com.airbnb.viaduct:buildtime:$version` to the `ksp` configuration (this contains the processor).
3. Validates Kotlin is in [1.9, 2.2] and warns about mismatches.
4. If KSP is not applied, logs a warning (runtime falls back to ClassGraph scanning).

This avoids the version-coupling problem entirely — we never need to know the consumer's Kotlin version at publish time.

## What We've Tested

The demo apps verify the full matrix:

- **KSP1 on Kotlin 1.9.24**: `1.9.24-1.0.20` (cli-starter)
- **KSP1 on Kotlin 2.0.21**: `2.0.21-1.0.28` (jetty-starter)
- **KSP1 on Kotlin 2.1.20**: `2.1.20-1.0.32` (micronaut-starter)
- **KSP2 on Kotlin 2.1.20**: `2.1.20-2.0.1` (ktor-starter) — proves KSP2 works at the overlap point
- **KSP2 on Kotlin 2.2.21**: `2.2.21-2.0.5` (starwars) — latest

## Future: Kotlin 2.3+ and Standalone KSP

Starting with KSP 2.3.0, KSP versions are completely independent of the Kotlin version (just `2.3.0`, `2.3.6`, etc. — no Kotlin prefix). KSP1 is not supported for Kotlin 2.3+, and the old `kotlinVersion-kspVersion` format is retired.

When we extend support to Kotlin 2.3+, we'll need to:

1. Verify the processor works with standalone KSP 2.3+ (likely just works given API stability).
2. Update the Kotlin version validation in `ViaductModulePlugin` to accept 2.3+.
3. Update documentation to explain the new versioning model to users.
4. Decide whether to drop Kotlin 1.9 support at the same time (simplifies the matrix significantly).
