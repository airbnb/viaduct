# Unified Selection Forest Preparation

On 2026-11-16, a fresh comparison on the Hetzner performance server measured the intermediate Runtime2 source-forest unification independently of GraphQL Java NormalizedDocument. Both variants use published GraphQL Java **26.0**, the same benchmark source and the same schema-4 large-query fixture.

| Metric | Separate forests | Unified forest | Reduction |
| --- | ---: | ---: | ---: |
| Preparation + traversal, ms/op | 148.802 ± 2.713 | 133.219 ± 4.560 | 10.47% |
| Allocated MB/op | 238.148 ± 2.949 | 177.789 ± 0.560 | 25.35% |

The unified forest reduced mean preparation time by **10.47%** and allocation per operation by **25.35%**. The saving comes from eliminating the separate source forest and recursive construction-view copy; the remaining AST/spec work is still performed.

Time errors are JMH's 99.9% confidence half-widths over six measurements, from two fresh JVM forks per variant. Allocated MB uses decimal megabytes. These are preparation costs for this large query, not end-to-end request latency or a prediction for small operations. One sequential baseline/candidate pair was run; the order was not reversed and no samples were discarded.

## Workload And Scope

`SelectionForestUnificationBenchmark.createAndConsume` starts with a validated AST, creates a fresh selection forest and recursively consumes all **187,215 logical selection occurrences**. Both variants eagerly expand the query's named fragments. The baseline builds the materialization source forest and recursively copies it into the construction forest; the candidate builds the common source forest directly. The traversal and return value force both variants to expose every logical selection.

The timed operation includes AST-to-spec conversion, field argument decoding, named-fragment expansion, flattening, source-forest allocation and the complete consuming traversal. Schema parsing, query parsing, GraphQL validation, schema lowering and request-variable coercion happen once per trial outside measurement. Resolver execution, concrete-type construction merging, response-key collection and materialization of result values are excluded. Consequently this benchmark does not measure downstream cache or execution changes.

Trial setup validates the query using `QueryComplexityLimits.NONE` and checks the logical selection count. It does not compare every key or projection between variants; the model, parser, materialization, mutation, reference-family and engine suites provide semantic coverage. The unification removes a recursive copy but retains Viaduct's AST/spec traversal; adoption of GraphQL Java NormalizedDocument is separate work.

## Source And Fixture Provenance

- Upstream base: `a2483562b1ef964d9f5d2ad6206a771b78594dbf`.
- Baseline source: `666744c13361cf7dd59961f4d8e5934290f1b947` (base plus benchmark and fixtures).
- Candidate source: `a67771769507fa4ee518169d00dc2de5a3f9eee5`, with the small [measured-source patch](measured-source.patch) described below.
- Fixtures: `src/test/resources/large-schema-4.graphqls` and `large-schema-4-query.graphql` from GraphQL Java commit `69c7bfbef920e5b95b7c295b60ef0652523eef3d`, stored as gzip resources beside the benchmark with the upstream license.
- [Source metadata](source-metadata.json) records SHA-256 hashes for the benchmark, Runtime2 production sources, dependency catalog, compressed fixtures and both measured jars. The jar hashes were checked on the server before launch.

After building the measured candidate jar, the final source retained an existing ground-key reuse shortcut in `SelectionVariables.kt`. That helper is not called by the measured parser/flattening/traversal path. Applying `measured-source.patch` to the candidate source commit reconstructs the measured source hashes; the final PR keeps the shortcut. No measured-path source changed after the candidate jar was built. The full local checks below cover the final implementation.

## Environment And Timing

The server uses an AMD Ryzen 5 3600 (6 cores / 12 hardware threads), Ubuntu 26.04 and OpenJDK 25.0.5.0.3. JMH is **1.36**, with G1, `-Xms2g -Xmx8g`, one benchmark thread, average-time mode and the GC profiler. Each variant has two forks, two 30-second warmups and three 60-second measurements per fork: **8 scheduled minutes per variant, 16 minutes total**, plus trial setup and process overhead. JMH reported 00:08:29 and 00:08:28 (16m 57s total) for baseline and candidate, respectively.

The exact command and a 15-minute timeout per variant are retained in [run-comparison.sh](run-comparison.sh). A shared server lock excludes another cooperating benchmark. The runner records the fresh CPU governors and update-service states, selects the performance governor, stops active update services/timers and restores the recorded state on exit. The [host helper](host-settings.py), [original state](host-original-state.json), [restored state](host-restored-state.json) and independent [post-run verification](host-verification.json) are retained. Both runs exited successfully; restoration matched the original state, the lock was free and no Java process remained after completion.

## Every Measurement

| Fork | Iteration | Baseline ms/op | Unified ms/op | Baseline allocated MB/op | Unified allocated MB/op |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 148.845754 | 132.173995 | 237.187785 | 177.607009 |
| 1 | 2 | 149.897038 | 131.543255 | 237.187785 | 177.607009 |
| 1 | 3 | 150.039536 | 131.591811 | 237.187785 | 177.607009 |
| 2 | 1 | 148.214688 | 134.667157 | 239.107737 | 177.971393 |
| 2 | 2 | 147.941062 | 134.236748 | 239.107737 | 177.971393 |
| 2 | 3 | 147.872976 | 135.101185 | 239.107737 | 177.971393 |

Raw records: [baseline JSON](baseline/results.json), [candidate JSON](candidate/results.json), [baseline JMH log](baseline/jmh.log), [candidate JMH log](candidate/jmh.log) and [host/JVM metadata](metadata.txt). The logs also retain all warmup scores.

## Local Validation

The root `test` task and `:core:engine:runtime2:check` passed with published dependencies, covering **14,973 reported tests including 202 skips, zero failures and zero errors** across 46 test tasks. Runtime2 accounts for **4,462 tests including 144 skips**. The final commands were:

```sh
./gradlew :core:engine:runtime2:ktlintFormat test :core:engine:runtime2:check \
  --no-configuration-cache --console=plain --continue
```

Runtime2 checks include ktlint, detekt, package layers, production boundaries and documentation labels. The earlier focused run covered 632 tests including one skip. [Local validation](local-validation.json) records task-level counts and cache/execution states. Added regression tests cover shared construction demand with alias-specific child projections, independent projection roots with conflicting response names, source order, metadata preservation, child-reference reuse and projection-cache reuse.

The standalone HTML tour validator is outside Gradle's `check` task. It reports 372 existing diagnostics at the base and 333 after this change, with no new diagnostic signatures. The affected semantic explanations and newly broken citations were updated; pre-existing unrelated tour diagnostics remain.

## Reproduction

Build the two source revisions in separate worktrees using `./gradlew :core:engine:runtime2:jmhJar --no-configuration-cache`. For exact measured-source reconstruction, apply `measured-source.patch` only in the candidate worktree. Copy the resulting jars into a new directory as `baseline.jar` and `candidate.jar`, along with `run-comparison.sh` and `host-settings.py`, then run the script on the same server with no competing workload. A fresh directory is required so the host-state file cannot overwrite a prior run's restoration state. Locally built jar bytes may differ; verify the recorded source and fixture hashes before interpreting the comparison.

For a single variant without host-setting changes:

```sh
java -cp candidate.jar org.openjdk.jmh.Main \
  'viaduct.engine.runtime2.schema.SelectionForestUnificationBenchmark.createAndConsume' \
  -f 2 -t 1 -wi 2 -w 30s -i 3 -r 60s -prof gc -rf json -rff results.json
```
