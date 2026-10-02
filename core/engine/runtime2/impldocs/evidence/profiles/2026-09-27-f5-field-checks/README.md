# F5 Field-Checker Profiling

> **Historical snapshot.** Commands, paths, source names, and measurements below describe the recorded qplan build and are not current operating guidance. Use the maintained [performance guide](../../../testing/performance.md) for current commands.

This round investigated generated Resolution runtime field-check timeouts while delivering F5. The [scalability catalog](./scalability-catalog.md) records each input reduction separately from implementation repairs; the original depth-two denial workload remains an explicit limitation.

## Provenance

Host: `raymie-stata-codex`; Intel Xeon Platinum 8375C, one socket, 32 physical cores / 64 logical CPUs, one NUMA node, 495 GiB RAM, no swap. The cgroup CPU setting was `max 100000` (no quota). Session: `01a0e18b-2eca-7b02-9ff1-60262b09597f`.

The denial recording used Corretto 17.0.11.9.1, a 2 GiB test-worker heap, one Resolution thread, and the 15-second request timeout. It was captured from `a3dd6fa65` plus the complete intermediate source patch in [`denial-timeout/source.patch.gz`](./denial-timeout/source.patch.gz); that base commit alone is insufficient to reproduce it. Patch applicability was checked against the base with a temporary Git index. The raw JFR remains outside Git; its checksum and exported execution stacks, allocation stacks, hot methods, allocation sites, and GC pauses are checked in. No qplan phase events were enabled for this test-worker recording.

The later counting probe added concrete-key reuse and temporary test diagnostics, preserved as [`counting-probe.patch`](./denial-timeout/counting-probe.patch). Its 129,788 resolver invocations and 52,321 associated Query OERs are work observed before timeout, not completed-case statistics. The delivered test suite contains no temporary counters.

The repaired code memoizes fixed successor fragments only when independent of the active recursion stack, coalesces duplicate passive producer forests, caches immutable symbolic occurrence hashes, shares correctness-replay caches by result identity within one judgment, and reuses already-concrete canonical keys. Every owner projection remains independently checked. Runtime generator depth was bounded separately; no timeout, exactness assertion, or activated coverage requirement was relaxed.

## Diagnostic commands

The source snapshot and replay instructions are in the [catalog](./scalability-catalog.md#reproducing-f5-s1). The recording added this test JVM argument through a temporary Gradle init script:

```text
-XX:StartFlightRecording=filename=/tmp/qplan-f5-denial-timeout.jfr,dumponexit=true,settings=profile
```

The exported reports were generated from the saved recording:

```shell
./export-resolver-profile.sh /tmp/qplan-f5-denial-timeout.jfr semantics/profiles/2026-09-27-f5-field-checks/denial-timeout
```

## Validation evidence

The focused runtime and neighboring contracts passed 209 tests on 100 configured resolution threads. All five bounded checker profiles passed 750 cases at seed `424242` (the passive and reference profiles retain their own directed seeds). Success stress passed 2,500 cases on one resolution thread in 2m 19s; denial stress passed 2,500 cases on 100 threads in 1m 46s, with 210,364 checker applications verified. A thread dump observed all 100 Resolution workers. The unchanged deep stress passed 10,000 cases and verified 951,771 resolver applications in 5m 47s including recompilation. Raw generated/activated counts are in [`checker-stress-coverage.txt`](./checker-stress-coverage.txt).

A follow-up caught and repaired loss of statically excluded producer-selection metadata exposed through the execution adapter. Its targeted validation passed 4 model tests, 61 semantic tests on 100 threads, and 71 execution tests with 21 existing skips. The model tests also cover checker variables beneath abstract parent fields and exact provider-returned names. A final regression also prevents statically excluded recursion from triggering the symbolic-expansion guard; its focused run passed 22 tests on 100 threads. The final `./gradlew check -PresolverPropertySeed=424242` passed in 5m 40s (134 tasks: 27 executed, 107 up-to-date), reporting 2,140 tests with 226 existing skips and no failures or errors: arbitrary 67/0 skipped, model 312/1, semantics 1,201/5, execution 560/220. The check used in-process Kotlin compilation, a 3 GiB Gradle heap, 1 GiB metaspace, and two Gradle workers.

An earlier unseeded full check also missed the Resolver23 mixed profile's aggregate `PARENT_FIELD_DEMAND` activation signature at seed `8564939376200698885`, size `10:3:5`; its per-case assertions did not report a resolver defect. That sampling-coverage miss is separate from F5-S1 and was not addressed by reducing inputs or weakening the coverage requirement. The final gate uses the explicit recorded seed `424242`.

## Closeout controls

All three controls passed serially on the otherwise idle host from clean runtime revision `a1057d3f1c8b76da638e1b50dbcc329fea3eefaf` using Corretto 21.0.4+7-LTS and JMH 1.36. The tree was clean before and after measurement. Every measured iteration, score, work count, normalized mean, and emitted corpus statistic is recorded in the [performance history](../performance-history.md#2026-09-27-075714-utc); complete JMH output is in [`benchmark-results.txt`](./benchmark-results.txt). No controlled before/after benchmark exists, so this round makes no measured speedup claim. These checker-free controls do not measure the original depth-two denial workload. Benchmark inputs were not regenerated, and the checksums below were verified after the runs.

Each task ran from the qplan directory with default benchmark parameters (`loopCount=1`; correctness additionally uses `inputCount=50`, `querySeed=1`). There is one fork and one JMH thread; overhead and correctness use one warmup and three measurements, and the property case uses two warmups and five measurements. The build-only heap/compiler settings do not override the benchmark JVM heap.

```shell
./gradlew :engine:runtime2:resolutionOverheadBenchmark --console=plain -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs='-Xmx3g -XX:MaxMetaspaceSize=1g' --max-workers=2
./gradlew :engine:runtime2:correctResolutionBenchmark --console=plain -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs='-Xmx3g -XX:MaxMetaspaceSize=1g' --max-workers=2
./gradlew :engine:runtime2:propertyTestBenchmark --console=plain -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs='-Xmx3g -XX:MaxMetaspaceSize=1g' --max-workers=2
```

Corpus checksums:

```text
7b0f5dcbde32ebc03d10c11606325b5fa936980724732f357bae3c520f3236e4  semantics/src/jmh/resources/semantics/benchmark/current-profile/queries.json
c132b1694c336baed8bd07c973b1c7f4bdaf12eb0ff3196e5372c4b23fa3424c  semantics/src/jmh/resources/semantics/benchmark/current-profile/registry.json
563e18f0c4a6220ab1250d066a186017340db178e9990ce0f5c0196768be4010  semantics/src/jmh/resources/semantics/benchmark/current-profile/schema.graphqls
b48e37e1c2f3030d9fcdeed463016ca17d3fd28ad82bb8cac3643709ea352f3b  semantics/src/jmh/resources/semantics/benchmark/property-test/provenance.txt
7d90bd953b1106983b9c2acf91839d825a995c96f22b1ccb771315a099bf7bd8  semantics/src/jmh/resources/semantics/benchmark/property-test/query.graphql
4c7df97332a61a9a7a056eecc19ef7d196ec160bd6e5dda6de2ca23ca4961415  semantics/src/jmh/resources/semantics/benchmark/property-test/registry.json
24176584cf87433838d61e3ba7f719097a2999c659e3fe2ddbd2805cc7039367  semantics/src/jmh/resources/semantics/benchmark/property-test/schema.graphqls
```
