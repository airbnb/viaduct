# KSP Versioning for Viaduct Maintainers

This document covers what we've learned about KSP version compatibility and the constraints it imposes on the Viaduct Gradle plugins. It's aimed at framework maintainers, not end users.

## KSP Version Format

Before KSP 2.3, versions follow `<kotlinVersion>-<kspMajor>.<kspMinor>.<kspPatch>`:

- **KSP1**: the KSP portion is `1.0.x` (e.g., `2.0.21-1.0.28`)
- **KSP2**: the KSP portion is `2.0.x` (e.g., `2.2.21-2.0.5`)

The Kotlin version prefix must match the project's compiler version. KSP 2.3+ uses standalone versions such as `2.3.7`, independent of the Kotlin compiler version. This coupling is in the KSP Gradle plugin/implementation, not in the processor API.

## KSP1 vs KSP2

KSP1 is a Kotlin compiler plugin that hooks into compiler internals. KSP2 is a rewrite built on the Kotlin Analysis API (K2). From a processor author's perspective:

- The `symbol-processing-api` interfaces are the same (backward compatible).
- KSP2 runs processors in a more isolated classloader (`KspAAWorkerAction`).
- KSP1's last Kotlin version is **2.1.20** (`2.1.20-1.0.32`). For Kotlin 2.2+, only KSP2 is published.
- Both KSP1 and KSP2 are available for Kotlin 2.0.x and 2.1.x (users can choose).

## Processor API Compatibility

The `com.google.devtools.ksp:symbol-processing-api` is backward compatible across KSP1 versions. Google's stated guarantee: old interfaces never change, and processors depend only on the API.

Our processor (`RegistryExtractorProcessor` in `tenant:codegen`) is compiled against `symbol-processing-api:2.3.7` as a `compileOnly` dependency. Its published artifact is tested with KSP 2.2.21-2.0.5 and 2.3.7.

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
3. Validates Kotlin is in [2.2, 2.3] and warns about mismatches.
4. Resolver modules must apply KSP to generate their module configs; runtime resolver discovery has no scanning fallback.

This avoids the version-coupling problem entirely — we never need to know the consumer's Kotlin version at publish time.

## What We've Tested

The demo apps verify the full matrix:

- **KSP2 on Kotlin 2.2.21**: `2.2.21-2.0.5` (demoapp defaults) — minimum supported consumer pair
- **KSP 2.3 on Kotlin 2.3.21**: `2.3.7` (Ktor CI matrix) — upgraded compiler pair
- **Gradle 8.11 and 9.1.0**: Micronaut CI matrix with Kotlin 2.2.21

## Kotlin 2.3+ and Standalone KSP

Starting with KSP 2.3.0, KSP versions are completely independent of the Kotlin version (just `2.3.0`, `2.3.6`, etc. — no Kotlin prefix). KSP1 is not supported for Kotlin 2.3+, and the old `kotlinVersion-kspVersion` format is retired.

The processor and Ktor demoapp run with KSP 2.3.7. Artifacts compiled with Kotlin 2.3 require Kotlin 2.2 or newer in tested consumers; the Kotlin 1.9, 2.0, and 2.1 demoapp compilers reject their metadata.
