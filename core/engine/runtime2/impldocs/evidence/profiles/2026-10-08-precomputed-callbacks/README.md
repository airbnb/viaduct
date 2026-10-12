# Prepared callbacks in the Resolution overhead benchmark — 2026-10-08

Preparing fixture outputs reduces the measured mean from **18.994 to 14.976 ms/resolution**, a **4.017 ms (21.15%)** reduction. All emitted workload statistics are byte-identical. This corrects what the overhead benchmark measures; it is not a production engine optimization. Generated correctness tests and the full, frozen-property, and isolated-correctness benchmarks retain their ordinary input-sensitive resolver programs and checker input assertions.

## Callback and setup boundaries

Field callbacks return their fixed value-plan output, assembled before measurement. Node callbacks return an eagerly assembled output from a lookup scoped to their concrete type and keyed by the requested ID. Checker callbacks return their setup-chosen outcome without executing the fixture's assertions comparing named input pairs and their fingerprints. Engine-owned resolver/checker input materialization, variable binding and providers, output lowering and projection, coroutine scheduling, cycle checks, and invocation instrumentation remain timed.

An untimed discovery world resolves the fixed 100 queries with constant field outputs and records node IDs. Each measured invocation builds a separate fresh registry and materializes its own canonical outputs for those IDs. Unknown IDs raise an error rather than triggering output generation. The discovery world's successor cache and operation state are not reused. This preserves the benchmark's existing fresh-registry cache population during the measured query batch.

Input-dependent scalar values and synthetic hash seeds no longer vary in this benchmark. The field plans use their fixed setup values. Reported result sizes, resolver/checker counts, variable-bearing calls, dependency depth, and result depth remain unchanged; this does not claim equality of raw values or every unselected synthetic output subtree. The comparison intentionally crosses a fixture-semantic boundary and must not be interpreted as an isolated engine speedup or an exact stopwatch measurement of the old callbacks.

## Source, environment, and commands

Baseline: clean `c9b53de9df17bddd4ba25fdfd72361c9e9129862`, with registry successor caching already installed. Candidate: clean `113b0e877626afd08ba07901e518e92bbc267106`. [Source state](source-state.json) records the worktree, branch, and agent session. Worktree: `/home/raymie_stata/repos/3ht/projects/viaduct/oss`, branch `rstata--1perf`, session `01a11ce9-fb33-7b50-aa1f-35ab16fbef2e`. Corpus resources are not regenerated; [resource hashes](corpus.sha256) and [JAR hashes](benchmark-jars.sha256) identify the exact inputs and executables.

Host: `raymie-stata-codex`, Linux `6.1.177-224.371.amzn2023.x86_64`, KVM guest, Intel Xeon 6975P-C, 32 physical cores / 64 logical CPUs, one NUMA node, 495 GiB RAM, no swap, `cpu.max=max 100000`. Corretto `21.0.4+7-LTS`, JMH `1.36`, compiler Blackholes, one JMH thread and one Resolution dispatcher thread, default JVM/heap options. Baseline then candidate run serially, without concurrent builds, tests, or profiling from this task. The candidate recording also starts and ends with a clean worktree. JARs and raw JFR remain in `/tmp/runtime2-precomputed-outputs-20261008`.

```sh
./gradlew :core:engine:runtime2:jmhJar --no-configuration-cache --console=plain
# Run this once with variant=baseline and once with variant=candidate.
variant=baseline
java -jar /tmp/runtime2-precomputed-outputs-20261008/$variant.jar \
  'viaduct\.engine\.runtime2\.resolution\.ResolverBenchmark\.overhead' \
  -p loopCount=1 -wi 5 -i 8 -f 3 -foe true -rf json \
  -rff /tmp/runtime2-precomputed-outputs-20261008/$variant.json \
  -jvmArgsAppend "-DresolverBenchmarkReportFile=/tmp/runtime2-precomputed-outputs-20261008/$variant-statistics.txt"
java -jar /tmp/runtime2-precomputed-outputs-20261008/candidate.jar \
  'viaduct\.engine\.runtime2\.resolution\.ResolverBenchmark\.overhead' \
  -p loopCount=5 -wi 1 -i 1 -f 1 -foe true \
  -jvmArgsAppend '-DresolutionOverheadProfileOutput=/tmp/runtime2-precomputed-outputs-20261008/resolution-overhead.jfr -XX:FlightRecorderOptions=stackdepth=256'
```

## Controlled timing and workload

Each single-shot JMH operation contains 100 top-level resolutions. Errors are JMH's 99.9% confidence intervals over 24 measured samples per variant (eight iterations in each of three forks).

| Variant | Score and error (s / 100 resolutions) | Mean and error (ms / resolution) |
| --- | --- | --- |
| Original callbacks | 1.899357 ± 0.013031 | 18.994 ± 0.130 |
| Prepared callbacks | 1.497617 ± 0.008029 | 14.976 ± 0.080 |

[Every measured iteration](measured-iterations.tsv), [baseline JSON](baseline.json), [candidate JSON](candidate.json), and their [baseline](baseline.txt) and [candidate](candidate.txt) logs preserve the raw measurements. [Baseline statistics](baseline-statistics.txt) and [candidate statistics](candidate-statistics.txt) are byte-identical and retain every average, percentile label, and maximum. In particular, the mean query still returns 296.39 fields and executes 280.27 resolvers, 84.94 field checkers, and 46.48 type checkers; variable-bearing resolver executions still average 48.00 with P50=49, variable stack depth has P50=2, and result depth has P90=7.

## Final execution profile

The diagnostic recording contains 500 resolutions, takes 8.036 s (16.072 ms/resolution), and has 530 execution samples. Its single measured iteration after one warmup includes JFR overhead; use the controlled comparison above for the saving.

There are **zero samples** in `ObjectPlan.materializeObject`, `generatedHashObject`, `sensitiveScalar`, or fixture output/input fingerprint traversal (`FingerprintBudget.output`). Resolver relation evaluation, including the engine's projection/lowering work, appears in 21 samples (3.96%), versus 23.17% in the [preceding profile](../2026-10-08-post-cache/README.md). Argument fingerprinting remains in one sample (0.19%) from variable-provider work. This does not imply that constant callbacks or lookups take literally zero time. [Callback counts](resolution-overhead/callback-samples.json) distinguish fixture work from the inclusive relation boundary.

| Remaining engine work | Samples / 530 | Inclusive share |
| --- | --- | --- |
| Construction-demand closure | 122 | 23.02% |
| Resolver-input materialization | 117 | 22.08% |
| Successor-demand analysis | 89 | 16.79% |
| Selection normalization | 80 | 15.09% |
| Runtime cycle checking | 46 | 8.68% |
| Recursive successor compaction | 45 | 8.49% |
| Output conformance/lowering | 14 | 2.64% |

These shares overlap and are not an additive time partition. [Component counts](resolution-overhead/component-samples.json), [inclusive methods](resolution-overhead/inclusive-cpu.tsv), and the standard exported stacks retain the definitions and evidence. Removing fixture work changes the denominator; larger shares for unchanged engine paths are not evidence of a regression. The next engine opportunities remain reusable fragment layouts and reducing temporary collections in resolver-input materialization.

## Validation

The 47 `GeneratorTest` controls pass on the final runtime/support code ([build/test log](final-build-and-tests.txt)); the three new preparation tests pass along with ktlint, detekt, and documentation-label checks in the [final focused check](final-focused-check.txt). The new tests cover eager node construction, reuse by type/ID, null outputs, snapshot isolation, failure without construction on an unknown ID, constant field/checker callbacks that do not inspect inputs, and retained assertions/sensitivity in ordinary worlds.

Full checks during implementation run all 2,481 reported tests (166 skipped). The [unseeded check](full-check.txt) has one generated type-checker coverage failure; the [seed-1 check](full-check-seed-1.txt) has three failures in that same coverage contract. The user confirms this is a known issue. The unseeded failure at seed `3895819303534908208` is preserved in [its XML](candidate-type-checker-failure.xml). Explicit baseline and candidate replays produce identical failure messages and coverage counts: [baseline](baseline-type-checker-replay.xml), [candidate](candidate-type-checker-replay.xml), and [baseline classpath override](baseline-replay.gradle). The final checker-assertion refinement was validated with the focused controls above; no full-check pass is claimed.

## Reproducing profile exports

```sh
core/engine/runtime2/export-resolver-profile.sh RECORDING.jfr OUTPUT_DIRECTORY
python3 core/engine/runtime2/impldocs/evidence/profiles/2026-10-08-post-cache/analyze-profile.py \
  RECORDING.jfr OUTPUT_DIRECTORY
python3 core/engine/runtime2/impldocs/evidence/profiles/2026-10-08-post-cache/analyze-components.py \
  RECORDING.jfr OUTPUT_DIRECTORY/component-samples.json
python3 core/engine/runtime2/impldocs/evidence/profiles/2026-10-08-precomputed-callbacks/analyze-callbacks.py \
  RECORDING.jfr OUTPUT_DIRECTORY/callback-samples.json
```
