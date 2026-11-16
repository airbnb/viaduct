# Unified ND Selection Graph on Schema 4, 2026-11-16

This fresh measurement covers the full Runtime2 selection API migration, using graphql-java [PR #4562](https://github.com/graphql-java/graphql-java/pull/4562) at `69c7bfbef920e5b95b7c295b60ef0652523eef3d`. Operations and resolver/checker fragments share one selection API for construction and projection. Production no longer has `MaterializeSelectionForest` or an AST-to-spec-to-two-forest conversion. The [integration guide](../../../integration/normalized-document.md) describes the API and remaining execution traversals.

## Results And Scope

Both cases create a fresh request forest and recursively visit all **187,215 logical selections** from the upstream schema-4 large query. Deferred collection and field argument decoding are included. Errors are JMH's 99.9% confidence half-widths; MB means decimal megabytes.

| Implementation | Time, ms/op | Allocation, MB/op |
| --- | ---: | ---: |
| Pre-migration AST + two forests | 147.726 ± 2.586 | 237.827 |
| Unified ND-backed graph | 6.294 ± 0.031 | 14.997 |

The mean time is **95.74% lower** (23.47× faster) and allocation is **93.69% lower**. Parsing, schema construction, validation/ND creation, request variable coercion, demand closure, resolver execution and response completion are excluded. This does not measure projection execution or end-to-end request latency. The stress query has extensive fragment reuse; the numbers do not establish savings for other workloads. The comparison measures the combined migration, including sharing, deferred collection and key caching, rather than isolating ND as the only cause.

The baseline is the preserved pre-migration benchmark jar from the [construction-only experiment](../2026-11-16-normalized-document-backed-schema4/README.md), freshly rerun on the server. It still performs the original AST → spec → materialization → construction conversion. The current test-fixture AST oracle produces the unified API and therefore cannot stand in for that baseline. Both jars use the same GJ PR version and recursive occurrence-counting workload. The earlier prototype's 97.00% time / 98.40% allocation reductions belong to that older source revision; use this report's measurements for the full migration.

An untimed [structural census](forest-shape.txt) using the candidate jar finds 8,314 distinct Selection objects, 3,014 distinct keys and 994 exposed forest handles for the shared graph, preserving all 187,215 logical occurrences. The AST oracle exposes 187,215 selections and keys and 43,241 forest handles. This counts objects reachable through the selection API, not all JVM allocations or private delegates. [Census source](ForestShape.java) is retained.

## Run Configuration And Timing

The Hetzner AMD Ryzen 5 3600 host ran OpenJDK 25.0.5.0.3, JMH 1.36, G1GC, `-Xms2g -Xmx8g` and one benchmark thread. Each case used **two forks, two 30-second warmups and three 60-second measurements per fork**: eight scheduled minutes per case, **16 scheduled minutes total**. JMH reported **00:08:29** per case, **00:16:58 total**, including setup. The baseline began at `2026-11-15T23:48:52Z`; the candidate began at `2026-11-15T23:57:22Z` (November 16 in Australia/Brisbane). Every full measurement is retained; none was discarded. A separate one-second [smoke run](smoke.log) checked the candidate before the timed runs and contributes no reported score.

Each fork's trial setup recursively compares canonical keys, type applicability, inclusion conditions and children against the AST control. Shared-root setup also verifies zero decoded fields before observation and checks the complete occurrence count. Setup uses `QueryComplexityLimits.NONE` because this fixture exceeds default complexity limits. Separate production tests cover aliases, child projection, variables, mutations, callbacks and resolver/checker invocation semantics.

The run exited with code 0. Saved original and restored host settings match for both [baseline](baseline/host-restored-state.json) and [candidate](candidate/host-restored-state.json). The [live verification](host-verification.json) confirms restored governors/services, a free benchmark lock and no remaining Java process.

## Local Validation

The final root `test`, Runtime2 `check`, and lint checks for the other changed Kotlin modules passed in **9m 38s** with **14,987 reported tests, including 202 skips, zero failures/errors**, across 1,263 XML suites. Runtime2 contributes 4,475 tests including 144 skips. Tests cover the maintained reference algorithms, generated correctness cases and Engine API contracts. Runtime2's production-boundary, package-layer, documentation-label and detekt checks also passed. [Module counts and exact command](local-validation.json) and the [complete final Gradle log](local-validation.log) retain the evidence. Gradle reused up-to-date results where valid.

The standalone HTML-tour validator has pre-existing stale source links: 372 diagnostics against the base revision and 359 after updating the selection explanations and affected citations, with no new diagnostic signatures. It is separate from Gradle `check`; [base](tour-base-validation.log) and [final](tour-final-validation.log) logs retain that limitation. The [documentation-label check](documentation-check.log) passed again after the report updates.

The first broad run identified three migration compatibility issues: newly nullable GJ test APIs in the Apollo plugin, the updated built-in directive schema fixture, and the ND source filename required by detekt. All were corrected before this successful final run.

## Source Identity And Reproduction

The Viaduct base is `a2483562b1ef964d9f5d2ad6206a771b78594dbf` plus [source-snapshot.tar.gz](source-snapshot.tar.gz). Apply its `working-tree.patch` to that base, then overlay the other archive members. [Source metadata](source-metadata.json) records hashes. The baseline source and artifact provenance are in the preceding experiment's evidence directory. Both jars include GJ PR jar SHA-256 `b5c586084b49da2e87bc3fcb6e6518309a1b8d0457a917ffc2c9ce105c4d0cc9`.

| Artifact | SHA-256 |
| --- | --- |
| Baseline JMH jar | `ccc916d8a308f416396788e6f1d0b36107bbfd5fa1d52c81b7ddd717b360b92b` |
| Candidate JMH jar | `8bbd67ce5270995b2c1ced3536950bbde7a0435415911cafbeacbb3077d017a6` |

After the measured snapshot, `NormalizedOperationSelections.kt` was renamed to `NormalizedSelectionSource.kt` for detekt, and KDoc, formatting, compatibility tests/fixtures and documentation were finalized. The timed method bodies and selection behavior were unchanged. The PR commit preserves that final source.

Build the pinned GJ checkout with `./gradlew buildFinalJar`, then use `scripts/build-normalized-document-benchmark.sh /path/to/graphql-java-0.0.0-HEAD-SNAPSHOT.jar`. On the prepared Hetzner host, run `scripts/run-normalized-document-benchmark.sh PRE_MIGRATION_JAR baseline baseline`, followed by `scripts/run-normalized-document-benchmark.sh CURRENT_JAR candidate unified`. The [benchmark guide](../../../testing/normalized-document-benchmark.md) documents the baseline distinction and timing. Raw data are [baseline JSON](baseline/results.json), [candidate JSON](candidate/results.json), their [baseline log](baseline/jmh.log) and [candidate log](candidate/jmh.log), and per-run metadata.

The repository pins unpublished `com.graphql-java:graphql-java:26.0-pr4562-69c7bfb`. Ordinary CI dependency resolution is expected to fail; the local helper supplies the pinned jar to all included builds using an isolated temporary Maven repository. Merging requires an available upstream artifact/version.
