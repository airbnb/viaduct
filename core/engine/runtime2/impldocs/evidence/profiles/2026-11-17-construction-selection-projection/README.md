# Construction-Only Selection Projection Boundary

On 2026-11-17, the schema-4 large-query benchmark measured the incremental cost of making construction selections explicitly nonprojectable in the unified Runtime2 API. This comparison starts with the previous unified implementation, merged with current main. Both variants use published GraphQL Java 26.0 and identical benchmark source.

| Metric | Previous unified implementation | Null construction response keys | Mean change |
| --- | ---: | ---: | ---: |
| Uncached merge + collect, ms/op | 261.167 ± 53.084 | 248.832 ± 9.046 | -4.72% |
| Allocated MB/op | 455.761 ± 13.071 | 458.406 ± 0.077 | +0.58% |

**This run does not establish a speedup or the absence of a timing penalty.** The timing intervals overlap substantially, and the second baseline JVM fork is slower than the first. The allocation mean increases by 0.58%, also within the baseline fork variation. Errors are JMH's 99.9% confidence half-widths over six measured iterations; MB means decimal megabytes. One sequential baseline/candidate pair was run, without reversing the order or discarding samples.

## API Change

Source occurrences retain their alias or field name and directives. Construction selections carry `responseKey = null` and `fieldDirectives = null`. Singleton/reuse merge paths, `flatMerge`, the normalized construction factory and variable-demand partitioning all preserve that distinction. Source roots remain available for projection. Clearing metadata allocates a current-level construction node when necessary, reuses existing construction nodes and shares child forests; it does not recursively copy descendants.

`collect` rejects a null key even on an inapplicable occurrence. Materialization rejects construction input before filtering an excluded condition or waiting on unresolved variables. Synthetic factories default to construction-only nodes; source-producing callers supply a response key explicitly. The benchmark exercises uncached construction and projection collectors, not materializer error handling or variable-demand partitioning; regression tests cover those boundaries.

## Workload And Scope

`SelectionForestCollectionBenchmark.mergeAndCollect` starts from a prepared source forest containing 187,215 logical selections. Trial setup enumerates 43,240 nonempty selection sets and 163,374 selection-set/concrete-type pairs, yielding 406,050 construction groups and 406,090 projection groups. Both forks of both variants report these identical counts. Every operation creates a fresh wrapper per pair and calls `merge(type)` and `collect(type)` on that same original source wrapper, never projecting the merged result. Fresh wrappers prevent cached collector results from hiding first-use costs. Child source forests remain shared.

The timed scope includes wrapper/list allocation, current-level construction merging, response-key collection and projection-cache initialization. It excludes schema/query parsing, validation, initial source-forest construction, argument binding, resolver execution and value materialization. Every source occurrence is visited once when enumerating sites; this is a synthetic collector workload over possible concrete types, not a simulated execution or an end-to-end request. Do not add its timing differences to the earlier preparation result to infer net request savings.

The [2026-11-16 preparation comparison](../2026-11-16-selection-forest-unification/README.md) remains historical evidence for the initial unification, before this nullable-key follow-up. This run neither repeats nor replaces that preparation measurement.

## Revisions And Reproduction

- Baseline source: `817f3224c29b642626b6712b6b1736038bff14a7` (previous unified implementation plus current main and the benchmark).
- Candidate source: `01b1256a2d7461a3860eb12494500aaf5c145b18` (nullable keys, rejection boundaries and regression coverage).
- [Source metadata](source-metadata.json) records the benchmark, production source, dependency catalog, fixture and jar hashes. No measured-path source changed after building the candidate jar.
- Fixtures are the same licensed schema-4 large schema and query described in the earlier preparation evidence, from GraphQL Java commit `69c7bfbef920e5b95b7c295b60ef0652523eef3d`.

Build each revision with `./gradlew :core:engine:runtime2:jmhJar --no-configuration-cache`, retain the jars as `baseline.jar` and `candidate.jar`, and run `bash run-phase.sh baseline` followed by `bash run-phase.sh candidate` on the same idle server. The [runner](run-phase.sh) retains the exact command, a shared exclusive benchmark lock and a 15-minute timeout per variant. Do not run the phases concurrently.

The Hetzner host uses an AMD Ryzen 5 3600, Ubuntu 26.04 and OpenJDK 25.0.5.0.3. JMH 1.36 runs one thread, G1, `-Xms2g -Xmx8g`, two forks, two 30-second warmups and three 60-second measurements per fork, with the GC profiler. This schedules eight minutes per variant and 16 minutes total. JMH reported **8m 30s + 8m 31s = 17m 01s** including trial setup. Host and JVM metadata are retained per phase.

The [host helper](host-settings.py) records fresh governors and service states before each phase, selects the performance governor, stops active update services/timers and restores the recorded state on exit. Both phases exited zero. The independent [verification script](verify-host.py) and [verification result](host-verification.json) confirm restoration to both original snapshots, a free benchmark lock and no remaining Java process.

## Every Measurement

| Fork | Iteration | Baseline ms/op | Candidate ms/op | Baseline allocated MB/op | Candidate allocated MB/op |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 243.138337 | 246.434239 | 451.505530 | 458.380810 |
| 1 | 2 | 243.286878 | 245.651896 | 451.505530 | 458.380810 |
| 1 | 3 | 245.311597 | 245.651151 | 451.505530 | 458.380810 |
| 2 | 1 | 279.685984 | 251.212814 | 460.015730 | 458.430970 |
| 2 | 2 | 277.722700 | 252.190409 | 460.015730 | 458.430970 |
| 2 | 3 | 277.859094 | 251.850141 | 460.015730 | 458.430970 |

Raw evidence: [baseline JSON](baseline/results.json), [candidate JSON](candidate/results.json), [baseline log](baseline/jmh.log), [candidate log](candidate/jmh.log), [comparison](comparison.json), [baseline metadata](baseline/metadata.txt) and [candidate metadata](candidate/metadata.txt). Logs retain every warmup measurement.

## Local Validation

The final root `test` and `:core:engine:runtime2:check` passed with published dependencies. The reports cover **15,265 tests including 215 skips, zero failures and zero errors** across 46 test tasks. Runtime2 reports 4,611 tests including 157 skips. The initial focused run passed 80 tests; a later focused run including the corrected cycle fixtures passed 330 tests. These focused counts overlap with the full suite and are not additive.

```sh
./gradlew test :core:engine:runtime2:check --no-configuration-cache --console=plain --continue
```

Regression coverage checks metadata clearing on every merge path, child sharing and construction-node reuse, null preservation across transformations/partitioning/instantiation, unchanged source aliases and directives, and rejection by production/reference materializers before waiting or reserving cells. Cycle fixtures that reused merged demand as projection templates now use original source selections. An unrelated Gradle application TestKit test passed unchanged after archiving its disposable worktree-local build cache; the first run had received `FROM_CACHE` where it expects `SUCCESS`.

[Local validation](local-validation.json) records commands, task counts and final task states. Runtime2 lint, detekt, package/production boundaries and documentation-label checks passed. The standalone HTML tour validator still reports 349 pre-existing diagnostics on both the baseline and candidate, with no new diagnostic signatures. Newly moved citations were corrected.
