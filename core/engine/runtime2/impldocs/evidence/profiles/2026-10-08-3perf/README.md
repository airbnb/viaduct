# Incremental engine improvements on the corrected overhead baseline

This collection replaces the imported optimization evidence from the older callback/checker workloads. The baseline is the corrected `rstata--baseline` implementation: prepared resolver outputs, production-informed checker fragments, deferred runtime cycle diagnosis, and checker-owned Query-root correctness evidence. Apply guard reuse, then singleton concatenation reuse, then registry-lifetime fixed successor demand caching. Each row after the baseline compares with the immediately preceding row; the underlying 100 client query sources and every emitted workload statistic remain byte-identical.

## Measurement contract

Each measured JMH operation resolves the fixed representative corpus's 100 queries. Measurements use Corretto 21.0.4+7-LTS and JMH 1.36, single-shot timing, `loopCount=1`, five warmup iterations, eight measured iterations, three fresh forks, one JMH thread and one Resolution dispatcher thread. Build and measurement stages run serially without overlapping tests, compilation, linting, or profiles. Reported errors are JMH's within-run 99.9% confidence errors; shared-host variation limits attribution and generalization. Setup prepares callbacks, query fragments, registries, and operation contexts outside measurement. Each invocation has a fresh registry, reused across the 100 resolutions, so the successor cache starts cold and may warm during the batch.

The per-stage `source-state.json` records exact UTC timestamps, commands, measured revisions, relevant source hashes, corpus hashes, JAR checksum and timestamp-independent Runtime2 JAR-content hash. `host.json` records CPU, memory, kernel, Java and host identity. Each stage preserves raw JMH JSON/output, all 24 measured iterations, build logs and the complete workload report. The immutable benchmark JARs remain under `/tmp/runtime2-3perf/STAGE/benchmark.jar`; no timings are inferred from profiled runs.

Measurements use clean committed trees. Subsequent amendments add this evidence and generated Bazel test dependency wiring, without changing the measured runtime or benchmark source; each retained `implementation.patch` and source/JAR-content manifest identifies the measured implementation independently of that evidence amendment. Rebuild the corresponding semantic commit with `./gradlew :core:engine:runtime2:jmhJar --no-configuration-cache --console=plain`, then run the exact recorded benchmark command, replacing only the temporary JAR/output paths as necessary. `run-stage.py` documents the serial build-and-measure workflow used in Treehouse `~/repos/3ht/projects/viaduct/oss`.

## Incremental results

| Stage | ms/resolution, JMH 99.9% error | Incremental time reduction |
| --- | ---: | ---: |
| 00-baseline | 12.824 ± 0.058 | Baseline |
| 01-guard-reuse | 12.078 ± 0.084 | 5.82% |
| 02-concatenation-reuse | 11.747 ± 0.060 | 2.74% |
| 03-successor-cache | 10.862 ± 0.057 | 7.53% |

The measured mean falls 15.30% across the currently recorded stages. Percentages describe this fixed workload and these controlled runs, rather than a production traffic estimate.

## Validation

The validation and replay scripts require `defusedxml` for parsing JUnit XML. Install it in the Python environment used to run them (`python3 -m pip install defusedxml`). This hardens report parsing without changing the retained measurements or engine implementation.

All 37 focused regressions passed, covering guard and concatenation semantics, cache reuse and override isolation, cycle-cut exclusion, concurrent cold computations, checker-owned Query roots and prepared checker/callback fixtures. Config sync and lint passed; generated Bazel wiring adds the cache test's Kotest assertions dependency. The full OSS Bazel run passed 256 of 257 test targets (170 executed, 87 cached). Its sole failure is the same known missing `ERROR_ARGUMENT_IN_TYPE_INPUT` activation signature in `ResolverMultithreadedStressTest`, profile `resolution-type-checker-denial`, seed `6550275219250612614`, size `10:3:5`. The resolution target passed 455 individual tests and failed only that aggregate coverage guard. Complete baseline and candidate replays produce identical coverage counts and the same sole failure. The full result and failed-target output are retained in `validation/bazel.txt` and `validation/bazel-resolution-test.txt`. Exact commands, source revisions, UTC timestamps, per-suite test counts and complete Gradle logs accompany the results. No Java evidence source is introduced by this stack.

The exact `./gradlew :core:engine:runtime2:check` ran 2,515 reported tests (166 skipped) with one failure: the known randomized type-checker activation-coverage guard missed `ERROR_ARGUMENT_IN_TYPE_INPUT` in profile `resolution-type-checker-mixed`, seed `-8164484992334210930`, size `10:3:5`. All generated semantic correctness cases passed. Replaying the complete profile on both the corrected baseline and the optimization stack produced identical activated coverage counts and the same sole failure; both reports and the automated comparison are retained. No generator or coverage guard is weakened, and the full check is not represented as passing.
