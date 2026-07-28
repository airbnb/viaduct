# Gradle Build Architecture

Viaduct's Gradle build is organized as a **composite build** — a set of independently viable included builds coordinated by a thin root project.

## Design Objectives

- **Encapsulate the development inner loop.** Most contributors work on engine, tenant, service, or shared code. The build should let them run tests on that code without waiting on plugin publishing, publication wiring, or demoapp configuration.

- **Verify publication correctness.** Publication is fragile — a project can compile fine under composite source substitution but produce broken published artifacts. Demoapps are real consumers of Viaduct's plugins and libraries, so the canonical way to catch downstream breakage is to run them standalone against actual published artifacts, exercising the same dependency graph an external consumer would see. This is what root `check` and CI run (`demoappsStandaloneTest`), not composite source substitution.

- **Decouple internal module structure from the public API surface.** External consumers should depend on a small set of stable coordinates. Internal modules should be free to split, merge, or reorganize without breaking downstream builds.

- **Eliminate manual dependency wiring.** Gradle's composite auto-substitution should handle the mapping from Maven coordinates to local source. No hand-maintained substitution rules.

- **Keep each build independently viable.** `core`, `gradle-plugins`, `publications`, and each demoapp should each be able to build and test on their own, so that changes to one build's configuration don't break another.

## Build Map

```
viaduct/                         ← root project (orchestration only)
├── build-logic/                 ← shared build conventions and plugins
├── core/                        ← included build: library source code
│   ├── engine/                  (api, runtime, wiring)
│   ├── service/                 (api, runtime, wiring, serve)
│   ├── tenant/                  (api, codegen, ksp, validation, runtime, wiring, tutorials)
│   ├── shared/                  (apiannotations, arbitrary, codegen, dataloader, deferred,
│   │                             errors, graphql, invariants, mapping, utils, viaductschema)
│   └── x/javaapi/              (api, codegen, runtime)
├── publications/                ← included build: published facade artifacts
│   ├── api                      (single-dependency entry point for tenant developers)
│   ├── bom                      (BOM for version alignment)
│   ├── buildtime                (compile-only dependencies)
│   ├── runtime                  (runtime-only dependencies)
│   └── test-fixtures            (test utilities)
├── gradle-plugins/              ← included build: Gradle plugins for application developers
│   ├── application              (ViaductApplicationPlugin)
│   ├── module                   (ViaductModulePlugin)
│   └── common                   (shared plugin utilities)
├── demoapps/                    ← each demoapp is its own included build
│   ├── cli-starter/
│   ├── jetty-starter/
│   ├── ktor-starter/
│   ├── micronaut-starter/
│   └── starwars/
└── docs/                        ← root subproject (MkDocs + Dokka)
```

## The Root Project

The root `build.gradle.kts` owns no source code. It applies the `buildroot.orchestration` plugin, which creates lifecycle tasks (`check`, `test`, `build`, `detekt`, `ktlintCheck`, etc.) that delegate into the **participating included builds**: `core`, `gradle-plugins`, and `publications`. When you run `./gradlew check` at the root, Gradle fans out into those three builds.

Demoapps are included in the composite for dependency substitution but are *not* participating builds — they are not exercised by root lifecycle tasks. They are tested separately (see below).

## The `core` Build

`core` contains the engine, tenant API, service layer, and shared libraries — the code that most contributors work on day-to-day. Making `core` its own included build means a developer can run `./gradlew -p core test` and get a focused, fast inner loop. Gradle-plugin publishing, publication wiring, demoapp configuration, and documentation are all pushed out of this critical path.

`core` assigns **path-based Maven groups** at settings time so that composite auto-substitution registers the correct coordinates for each project. For example, projects under `core/tenant/` get group `com.airbnb.viaduct.tenant`, so `:tenant:api` publishes as `com.airbnb.viaduct.tenant:api`. This means each project's Gradle name matches its directory name — no need for synthetic project names to avoid collisions.

`core` also hosts JaCoCo aggregation and coverage thresholds, keeping CI verification commands scoped: `./gradlew -p core jacocoTestCoverageVerification`.

## Maven Coordinate Scheme

The build uses a two-tier Maven group structure:

- **`com.airbnb.viaduct`** (base group) — the public-facing artifacts that external consumers should depend on. These are the shadow jars produced by the `publications/` build: `api`, `bom`, `buildtime`, `runtime`, `test-fixtures`, and the Gradle plugins. These are the only coordinates that appear in a consumer's `build.gradle.kts`.

- **`com.airbnb.viaduct.<subgroup>`** (e.g., `com.airbnb.viaduct.tenant`, `com.airbnb.viaduct.engine`, `com.airbnb.viaduct.service`, `com.airbnb.viaduct.shared`) — the fine-grained module coordinates from `core/`. These are internal to the build. External consumers never reference them directly; they are pulled in transitively through the publication facade artifacts.

This separation means `core` modules can freely split, merge, or reorganize without affecting the coordinates that external consumers depend on.

## The `publications` Build

The `publications` build produces the public-facing `com.airbnb.viaduct` artifacts — `api`, `bom`, `buildtime`, `runtime`, and `test-fixtures`. These are facade modules whose primary job is to re-export the right set of `core` dependencies as shadow jars under stable, documented coordinates. External consumers depend on these and only these.

As an included build, `publications` participates in Gradle's automatic dependency substitution. When any other build in the composite (e.g., a demoapp) declares a dependency on `com.airbnb.viaduct:api`, Gradle substitutes the local `publications/api` project. This eliminates manually maintained substitution rules.

## The `gradle-plugins` Build

`gradle-plugins` contains the Viaduct application and module Gradle plugins used by application developers. It depends on select `core` libraries (e.g., `shared:graphql`, `shared:viaductschema`) which are resolved via composite auto-substitution during development and via Maven coordinates when published.

## The `build-logic` Build

`build-logic` is a special included build that provides precompiled script plugins (build conventions) used across all other builds. It defines conventions for Kotlin compilation, static analysis (detekt, ktlint), publishing, JaCoCo, and Dokka documentation. Every other `settings.gradle.kts` includes `build-logic` via `pluginManagement { includeBuild("../build-logic") }`.

## Demoapps

Each demoapp is its own included build with a `settings.gradle.kts` that detects whether it's running inside the composite or standalone:

```kotlin
pluginManagement {
    if (gradle.parent != null) {
        // Composite mode: resolve plugins from local gradle-plugins source
        includeBuild("../../gradle-plugins")
    } else {
        // Standalone mode: resolve from Maven Central / Maven Local
        repositories { ... }
    }
}
```

This dual-mode design serves two purposes:

- **Composite mode** (fast local iteration): developers get source substitution for everything — plugins, core libraries, and publications, without a publish step. Useful for quick manual checks, but it does not exercise the published-artifact path, so it is not a substitute for standalone testing.

- **Standalone mode** (the canonical demoapp test): demoapps resolve all dependencies from published artifacts (Maven Local or Maven Central), exercising the exact dependency graph a real external consumer would see. This is the mode root `check` and CI run (`demoappsStandaloneTest`) — it is what actually catches downstream breakage, since publication is fragile enough that a project can compile fine under composite substitution but still produce broken published artifacts.
