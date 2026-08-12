# Execution Registry: KSP Pipeline and Incrementality

This document explains the three-stage build pipeline that produces the tenant module config file
(`META-INF/viaduct/modules/<tenantPackage>.json`) from annotated resolver source code, with
particular attention to how incrementality and stale-output cleanup work at each stage.

## Pipeline overview

```
Kotlin source files
  │  (@Resolver, @TenantBootstrapper annotations)
  ▼
[Stage 1] kspKotlin (KSP — isolation mode)
  │  Emits one descriptor JSON per source file under:
  │    build/generated/ksp/main/resources/viaduct-registry/
  ▼
[Stage 2] extractKspRegistryDescriptors (Gradle Sync)
  │  Copies descriptor files to:
  │    build/intermediates/viaduct-registry-descriptors/
  ▼
[Stage 3] assembleViaductModuleConfigFile (AssembleTenantModuleConfigFileTask)
     Reads all descriptors and emits:
       build/generated-resources/viaduct-registry/META-INF/viaduct/modules/<pkg>.json
```

The generated resources directory is wired into the `main` source set so the config file
lands in the module's JAR.

---

## Stage 1 — KSP in isolation mode

`RegistryExtractorProcessor` (the KSP `SymbolProcessor`) delegates per-file output writes to `ResolverDescriptorProcessor`, which calls `Dependencies(aggregating = false, sources = arrayOf(sourceFile))`. The `aggregating = false` flag is the key:

- **Isolation mode**: KSP declares that each output file depends on exactly one source file.
  When a `.kt` file changes, KSP re-runs the processor only for that file and regenerates its
  descriptor. All other descriptors remain untouched.

- **Automatic stale-output cleanup**: When a `.kt` file is *deleted*, KSP removes the
  corresponding descriptor JSON. No manual bookkeeping is required. This is guaranteed by the
  KSP contract for isolation-mode processors.

- **Why isolation and not aggregating**: The registry extractor needs to see only the symbols
  declared in each source file — it does not need to see the full symbol graph. Using isolation
  mode keeps incremental processing correct: a change to `FooResolver.kt` cannot affect the
  descriptor for `BarResolver.kt`, so those two outputs can be tracked independently.

---

## Stage 2 — Bridge Sync task

The `extractKspRegistryDescriptors` task is a Gradle `Sync` that copies the KSP resource output
into a stable intermediates directory.

```
from: build/generated/ksp/main/resources/viaduct-registry/
  to: build/intermediates/viaduct-registry-descriptors/
```

**Why the bridge exists:**  KSP registers `kspKotlin` lazily and its output path is an internal
detail. The bridge task gives `assembleViaductModuleConfigFile` a typed `TaskProvider` dependency
instead of a fragile string-based `dependsOn`, and provides a stable path that the assembly task
can track as a proper `@InputFiles` input.

**How stale intermediates are cleaned up:**  Gradle's `Sync` task tracks its output directory as
an `@OutputDirectory`. When a descriptor is deleted from the KSP output (because the upstream
source file was deleted), the Sync task's output fingerprint becomes stale on the next build.
Gradle re-runs the Sync and the now-absent source file is not copied, leaving the corresponding
intermediate absent as well. This propagates KSP's deletion signal faithfully into the intermediates
directory.

---

## Stage 3 — Assembly (deliberately non-incremental)

`AssembleTenantModuleConfigFileTask` reads all descriptor JSON files and invokes the aggregation
CLI to produce the single tenant config file.

**Why it is non-incremental:**  The assembly step aggregates *all* descriptors into a single
output file. A truly incremental implementation would need to handle cases such as:

- A descriptor file being added or removed (changes the set of registered resolvers)
- The tenant package being renamed (the old config file must be deleted)

Implementing these safely requires execution-style tests that exercise Gradle's directory-level
add/remove events. Until those tests exist, the task always does a full reconcile: it clears all
owned config files (`clearOwnedModuleConfigs`) and regenerates from the complete descriptor set.

**How it is still re-triggered correctly:**  Even though the task does not inspect `InputChanges`
internally, it is annotated with `@CacheableTask` and declares `descriptorDir` as an
`@Incremental @InputFiles` input. Gradle's up-to-date mechanism compares the fingerprint of all
files in `descriptorDir` between builds. If a descriptor is added, modified, or removed (including
by the bridge Sync in response to a KSP deletion), Gradle marks the assembly task out-of-date and
re-runs it.

**What `clearOwnedModuleConfigs` does:**  Before invoking the CLI, the task deletes all `.json`
files under `META-INF/viaduct/modules/` in its output directory. This ensures that stale config
files (e.g., from a renamed tenant package) do not accumulate across builds.

**How the assembled config is identified:**  The config emitted here is one entry in a map keyed by
`<tenantName, apiName>` — the tenant module plus the tenant API that produced it (`kotlin` for this
pipeline). That key, not the `executorFactory` FQN recorded in the file, determines which
configuration a later regenerated source replaces, and it is why one tenant can carry a config per
tenant API without either displacing the other. See
[`execution-registry-bootstrap.md`](execution-registry-bootstrap.md) for the identity model, the
one-config-per-key build invariant, and the overlay protocol.

---

## Summary: what happens in each scenario

| Scenario | Stage 1 (KSP) | Stage 2 (Sync) | Stage 3 (Assembly) |
|---|---|---|---|
| `@Resolver` class changed | Regenerates that file's descriptor | Syncs updated descriptor | Detects input changed → clears + regenerates |
| `@Resolver` class deleted | Removes its descriptor (isolation mode) | Sync detects missing source → removes intermediate | Detects input changed → clears + regenerates (or produces empty output) |
| Intermediate file manually deleted | UP-TO-DATE | Output stale → re-runs Sync → restores file | Input restored; may still be UP-TO-DATE if fingerprint matches |
| Unrelated source file changed | UP-TO-DATE (only changed files processed) | UP-TO-DATE | UP-TO-DATE |
| Assembly output manually deleted | UP-TO-DATE | UP-TO-DATE | Output stale → Gradle re-runs automatically |
