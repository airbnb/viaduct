# Performance History

Source revisions in the historical upstream investigations are commits in the upstream [`airbnb/viaduct`](https://github.com/airbnb/viaduct) repository on github.com. Historical `qplan` paths refer to its development build layout; see the [upstream source context](README.md#upstream-source-context) before reproducing these runs.

This document preserves dated Runtime2 benchmark and profiling investigations. It is evidence, not current operating guidance: commands, paths, names, source layouts, and conclusions below describe the recorded revision and must not be silently modernized. Use [Performance Testing](../../testing/performance.md) for maintained benchmark and profiling instructions.

Compare measurements only when their recorded host, JVM, benchmark parameters, corpus, and semantic workload permit it. The linked round directories retain exported JFR summaries, hot methods, allocation sites, stacks, checksums, and supporting artifacts where available.

## Recorded Investigations

### 2026-10-12 — current Resolution overhead baseline

This reset establishes the workload for future measurements in [Treehouse PR #1154484](https://git.musta.ch/airbnb/treehouse/pull/1154484). The 100-query snapshot shares one schema and registry: its bulk partition preserves the existing size, depth and fragment distributions, while sixteen small deterministic sections each appear in three distinct queries. The ten added ordinary objects each contain two active and fourteen passive fields. All 64 observed feature categories meet the three-query minimum, including parent and checker-parent demand, supplied active descendants, general references, compound variable dependencies, exclusions, provider null/errors and field/type denials. This is a synthetic workload with sparse feature exercise, without production-frequency weighting or a comparison to a retired baseline.

Measured source: Treehouse `b3e18a962d6815eb47ad725f0dbb04f08fef3c0c`, worktree `~/repos/4ht/projects/viaduct/oss`, branch `rstata--baseline`; the tree was clean. Run started 2026-10-12T08:27:42.705474+00:00. Host `raymie-stata-codex`, Intel Xeon 6975P-C, 32 cores / 64 logical CPUs; Corretto 21.0.4+7-LTS, JMH 1.36, compiler Blackholes. Single-shot time uses five warmup iterations, eight measured iterations and three fresh forks, one JMH thread, one Resolution dispatcher thread, and `loopCount=1`. Local builds, tests, lint and push hooks finished before measurement; forks ran serially. Prepared callbacks, parsing, setup, correctness validation and reporting are untimed.

The score is **2.569303435 ± 0.009108442 s/op**, or **25.693034 ± 0.091084 ms/resolution**, with exactly 100 top-level resolutions per operation. Error is JMH's within-run 99.9% confidence error; host variance limits its interpretation. All three forks completed their untimed reporting and coverage gate; the report file below is the final fork's report.

```sh
java -jar dist/gradle/viaduct-oss/core/build/engine/runtime2/libs/engine-runtime2-2.1.0-SNAPSHOT-jmh.jar 'viaduct\.engine\.runtime2\.resolution\.ResolverBenchmark\.overhead' -wi 5 -i 8 -f 3 -t 1 -p loopCount=1 -foe true -rf json -rff /tmp/4ht-current-baseline.json -jvmArgsAppend '-Dviaduct.resolution.threadcount=1 -DresolverBenchmarkReportFile=/tmp/4ht-current-baseline-statistics.txt'
```

The command above runs from the Treehouse root; raw JSON, console log, report and identity manifest from this run are `/tmp/4ht-current-baseline.json`, `/tmp/4ht-current-baseline.log`, `/tmp/4ht-current-baseline-statistics.txt` and `/tmp/4ht-current-baseline-identities.json` on the recorded host. The following values preserve every measured sample and emitted statistic.

| Fork | Measured iterations (s/op) |
| --- | --- |
| 1 | 2.583649080, 2.561120634, 2.569157205, 2.581491821, 2.587747021, 2.557292531, 2.553612829, 2.580584568 |
| 2 | 2.585853270, 2.581275710, 2.575519612, 2.573173147, 2.578857575, 2.574854004, 2.572823038, 2.566860220 |
| 3 | 2.572155248, 2.567745434, 2.572118496, 2.552189885, 2.554811292, 2.552847528, 2.559294017, 2.548248270 |

SHA-256 identities (paths relative to the Treehouse root):

```text
36c7d608c19e291e3aefcd8510a5e63804ebc34c1f9e89e88379528a52a84b28  dist/gradle/viaduct-oss/core/build/engine/runtime2/libs/engine-runtime2-2.1.0-SNAPSHOT-jmh.jar
70d662d35ce271f1f5867cfbc60ea3fbdccf74a852433225448a62e10398f85e  projects/viaduct/oss/core/engine/runtime2/src/jmh/resources/viaduct/engine/runtime2/benchmark/current-profile/schema.graphqls
525f75fd9ec08b24c379bfdd86ae802d1a777cb186570eb015129da117e18a0e  projects/viaduct/oss/core/engine/runtime2/src/jmh/resources/viaduct/engine/runtime2/benchmark/current-profile/registry.json.gz
011606897dfc003647d1ee81290ede061223e554217fd6ba9b6ab3f99cf4d522  projects/viaduct/oss/core/engine/runtime2/src/jmh/resources/viaduct/engine/runtime2/benchmark/current-profile/queries.json
647ba29286a0d2d240f926662feaa404d16655ad1489d4906071b1fe0fbe16d5  projects/viaduct/oss/core/engine/runtime2/src/support/kotlin/viaduct/engine/runtime2/benchmark/ResolverBenchmarkFeaturePartition.kt
7121efa426a404ee9b2b0e2e2c431c735ad538c51d44c75e4e19c8888b683a72  projects/viaduct/oss/core/engine/runtime2/src/support/kotlin/viaduct/engine/runtime2/benchmark/ResolverBenchmarkFixture.kt
2ed215ba5492e044e837a1bcd95337b066be8d598242f541912f8dfc0660b7a5  projects/viaduct/oss/core/engine/runtime2/src/support/kotlin/viaduct/engine/runtime2/benchmark/ResolverBenchmarkCoverage.kt
e483b2ca91144f2d021d51e7ad94433572e9c3a915145ab3133b1b0b3208ab7a  projects/viaduct/oss/core/engine/runtime2/src/support/kotlin/viaduct/engine/runtime2/benchmark/ChinaActiveBenchmarkCheckers.kt
c52053002ce896b1c02e0d0def355828a5c65bc16d5535ff7f33f06f2414805c  projects/viaduct/oss/core/engine/runtime2/src/jmh/kotlin/viaduct/engine/runtime2/benchmark/CurrentProfileBenchmarkSupport.kt
```

```text
Resolver overhead corpus statistics (100 queries):
  fields returned: average=277.81, p90=382, max=449
  active fields returned: average=53.78, p90=71, max=84
  passive fields returned: average=224.03, p90=315, max=383
  passive fields per active field: average=4.20, p90=5.43, max=7.14
  resolvers executed: average=173.64, p90=235, max=267
  field checkers executed: average=231.07, p90=315, max=359
  type checkers executed: average=48.00, p90=68, max=79
  resolver executions with variable-bearing arguments: average=74.50, p50=73, max=125
  variable-bearing arguments per such resolver execution: average=1.08, p90=1, max=3
  maximum variable stack depth: average=2.02, p50=2, max=4
  result depth: average=10.00, p90=10, max=10
  named checker inputs: average=266.03, p90=367, max=423
  named field checker inputs: average=223.45, p90=306, max=351
  populated checker input roots: average=236.22, p90=332, max=371
Resolver benchmark registry statistics:
  active fields per non-Query object: average=1.96, p90=3, max=4
  passive fields per non-Query object: average=15.17, p90=17, max=20
  combined selections per object fragment: average=1.66, p90=2, max=48
  combined object fragment depth: average=0.65, p90=2, max=7
  bulk selections per object fragment: average=4.89, p90=22, max=48
  bulk object fragment depth: average=1.61, p90=5, max=7
Resolver benchmark feature counts:
  ordinary object types: 23
  total object types including policy helpers: 31
  installed field resolvers: 140
  checker profile: China active plus bounded prepared success/denial feature cases
  field checkers: 238
  type checkers: 15
  named inputs per field checker: average=1.04, p90=2, max=3
  named inputs per type checker: average=1.00, p90=2, max=2
  bulk field checker policies: {chinaNull=5, privacy=97, defaultBlock=152, chinaQuery=64, queryPrivacy=12}
  checker-only prepared Query resolvers: 14
  Query fragments: 16
  FromArgument variables: 14
  FromObjectField variables: 22
  FromQueryField variables: 18
  FromProvider variables: 27
Resolver benchmark activated feature coverage (distinct queries):
  SELECTIVE_DEMAND: 100
  NONSELECTIVE_CALLBACK: 48
  SYMBOLIC_LIST_ARGUMENT: 70
  SHARED_QUERY_ROOT: 99
  OBJECT_INPUT: 100
  QUERY_INPUT: 100
  FROM_ARGUMENT: 70
  FROM_OBJECT_FIELD: 95
  FROM_QUERY_FIELD: 89
  FROM_PROVIDER: 100
  NESTED_ARGUMENT: 18
  NULL_ARGUMENT_TRAVERSAL: 9
  NESTED_VARIABLE_EXPRESSION: 12
  INPUT_OBJECT: 15
  INPUT_LIST: 70
  SINGLETON_COERCION: 21
  ABSTRACT_PROVIDER: 12
  PASSIVE_DESCENDANT_VARIABLE: 12
  OWNER_DEPENDENCY_CHAIN: 3
  SYMBOLIC_CONVERGENCE: 12
  PARENT_SOURCE_OVERRIDE: 6
  PARENT_VARIABLE_MIX: 9
  PARENT: 9
  GREAT_GRANDPARENT: 9
  DIAGONAL_PARENT: 9
  SOMETIMES_PASSIVE: 3
  CONDITION_INCLUDED: 9
  CONDITION_EXCLUDED: 3
  NULL_PROVIDER_INTERMEDIATE: 3
  ERROR_PROVIDER_INTERMEDIATE: 3
  ARGUMENT_ERROR: 3
  NODE_REFERENCE: 100
  GENERAL_REFERENCE: 3
  REFERENCE_NAMESPACE: 3
  REFERENCE_TAIL: 3
  REFERENCE_LIST: 78
  REFERENCE_ENUM: 3
  REFERENCE_CONCRETE: 3
  REFERENCE_UNION: 3
  REFERENCE_TARGET_ARGUMENT_VARIABLE: 3
  REFERENCE_TARGET_QUERY_VARIABLE: 3
  REFERENCE_ABSTRACT: 100
  REFERENCE_OVERRIDE: 3
  REFERENCE_FALLBACK: 3
  FIELD_CHECK_SUCCESS: 100
  FIELD_CHECK_DENIAL: 3
  TYPE_CHECK_SUCCESS: 100
  TYPE_CHECK_DENIAL: 3
  CHECKER_PARENT_DEMAND: 9
  CHECKER_ARGUMENT_ERROR: 3
  CHECKER_PROVIDER_BOTH_INPUTS: 6
  CHECKER_ARGUMENT_VARIABLE: 6
  CHECKER_NESTED_PATH: 9
  CHECKER_RAW_RESOLVER_INPUT: 9
  CHECKER_COMPOSITE: 100
  CHECKER_DUPLICATE_INPUT: 100
  CHECKER_LIST_OCCURRENCE: 98
  CHECKER_SYMBOLIC_KEY: 100
  CHECKER_PASSIVE_FIELD: 100
  CHECKER_QUERY_OCCURRENCE: 99
  CHECKER_OBJECT_VARIABLE: 100
  CHECKER_QUERY_VARIABLE: 9
  CHECKER_PROVIDER_VARIABLE: 100
  NAMED_EMPTY_CHECKER_INPUT: 100
```

The exact Runtime2 full check passed with 2,955 tests, zero failures/errors and 168 skipped; `yak lint` passed, and the JMH JAR built. Each deterministic section passes independent result correctness, exact application accounting with its known supplied-field exemptions, and binding validation. The prepared full snapshot passes binding/activation checks, reporting checks, and the all-100 bulk client-value comparison. Whole-corpus correctness replay is not claimed: its pre-existing closed Query-OER checker validation encounters failed guards on non-invoked owners, as recorded in the maintained performance guide.

### 2026-10-08 — benchmark correctness repair on the corrected baseline

The [fresh repair validation](2026-10-08-full-benchmark-repair/README.md) carries the checker-owned Query-root repair onto the prepared-callback, production-informed checker baseline. The generated full benchmark records checker inputs, and development application and from-field-binding oracles enumerate distinct checker-associated Query roots. JMH failures propagate to the Gradle launchers. This repairs benchmark validation; it changes no production engine execution and is excluded from the engine-performance stack.

### 2026-10-09 — deferred cycle diagnosis at request deadlines

The [whole-change experiment](2026-10-09-deferred-cycle-detection/README.md) compares HEAD `1b0418ee3f26d` with an uncommitted edge collector and timeout integration on `rstata--2perf`, session `01a11ce9-fb33-7b50-aa1f-35ab16fbef2e`. Host `raymie-stata-codex`, KVM Intel Xeon 6975P-C (32 cores / 64 logical CPUs, approximately 495 GiB RAM, no swap), Corretto 21.0.4, JMH 1.36. Exact timing timestamps, commands, source patch/hashes and corpus/JAR identities are retained. The prepared callbacks and original fixed corpus are unchanged. Changes remain uncommitted for review.

Five warmups, eight measurements and three fresh forks give **14.623 ± 0.099 → 14.243 ± 0.126 ms/resolution**, **2.60% less time**, at one dispatcher thread; five threads give **6.686 ± 0.104 → 6.417 ± 0.046**, **4.03% less time**. Frozen property is **954.747 ± 14.809 → 947.049 ± 15.987 ms/case**, a 0.81% mean reduction with overlapping intervals and no established benefit. All six measurements run serially without build/test/diagnostic overlap. Within-run 99.9% confidence errors and shared-host variance limit attribution.

The candidate preserves 246,624 reads, 45,704 writers/tasks, 64,838 distinct edges and 102,340 pending index pairs, while eliminating all 64,838 eager searches, 131,788 node visits and 87,642 edge scans; successful requests perform no deadline scans. Candidate-only JFR records 954 execution samples: construction closure 24.63%, input materialization 20.34%, successor demand 19.81%, normalization 17.61%, cycle bookkeeping 4.93%, and resolver relation evaluation 3.35%, with overlapping inclusive shares. The requested deadline policy diagnoses any historical cycle as a violation of validated dependencies' acyclicity, without needing to prove it caused the particular timeout. Production timeout defaults to configurable 60 seconds; bounded Resolution retains 15 seconds. Direct unbounded startup retains eager checking.

The exact Runtime2 full check passed. The five-thread Resolution and wiring checks are included in the linked validation summary. No fixture or coverage guard is weakened, and no commit or push is performed.

### 2026-10-09 — consolidated cycle-check optimization

The [whole-change evidence](2026-10-09-cycle-check/README.md) compares baseline `b21ddcf895392` with one cycle-check optimization commit. Concurrent maps and sets are retained; the final change combines safe duplicate-search elimination, one monitor for slot/graph operations, pending indexing only for unknown writers, and immutable task-hash caching. Production edits are local to `CycleCheckState.kt`; the prepared callbacks and original fixed corpus are unchanged.

The controlled one-thread result is **15.000 ± 0.080 → 14.618 ± 0.057 ms/resolution**, **2.55% less time**. The collection contains paired five-thread, frozen-property and correctness controls, raw JSON/logs, all iterations, exact commands, source/JAR/corpus identities and host/JVM details. All runs execute serially with five warmups, eight measurements and three fresh forks; reported errors are within-run JMH 99.9% confidence errors. Shared-host variation limits generalization and finer attribution.

Exact counters preserve reads, tasks and distinct edges while reducing BFS searches 109,251 → 64,838, node visits 211,157 → 131,788, edge scans 131,143 → 87,642 and reader-index pairs 167,178 → 102,340. A 128-edge acyclic chain still visits 8,256 nodes; quadratic traversal is not repaired. No baseline missed-edge race was demonstrated, and no allocation-byte or monitor-contention saving is claimed. Focused tests pass 21/21; the five-thread Resolution suite reports 606 tests, 0 failures, 0 errors and 4 skips. The exact full check reports 2490 tests, 0 failures, 0 errors and 166 skips; its outcomes are preserved in the single collection. No push is performed.
### 2026-10-08 23:54–23:56 UTC — China-active overhead checker fixture

The [China-active round](2026-10-08-china-active-checkers/README.md) replaces duplicate correctness checker fragments in the overhead benchmark with one production-informed privacy/China shape, while preserving the fixed 100 client query sources and prepared callbacks. Worktree `~/repos/2ht/projects/viaduct/oss`, branch `rstata--tmp`, clean baseline `b21ddcf895392fb97fac5004c752a48e8df88215`, uncommitted candidate retained as complete patches. Session `01a11dd6-5044-74b1-905c-aa3e3e50222d`, host `raymie-stata-codex`, KVM Intel Xeon 6975P-C (32 cores / 64 logical CPUs), Corretto 21.0.4+7-LTS, JMH 1.36. Serial overhead runs use five warmups, eight measurements and three forks at 100 queries/op. Timing falls from 14.972 ± 0.093 to 11.050 ± 0.097 ms/query, a workload correction of 3.922 ms (26.20%), not an engine optimization. Field checker requests fall from 849.40 to 308.84 projections/query while field checker executions rise from 84.94 to 161.27 due to passive-field coverage. The new field policies include Query-plus-object-ID China fragments, nested/list lookup outputs, named-null policies, default block and privacy composites; type policies remain privacy-only. All client-selected values agree across the corpus; constructed field and resolver counts change because hidden demand changes.

The measured-body JFR contains 623 execution samples: construction closure 27.93%, selection normalization/merge 22.79%, successor entry 20.71%, input materialization 10.11%, and engine output projection 7.87%, inclusively and with overlap. Prepared fixture object construction is absent from samples. Demand/forest reuse and output projection remain candidate engine opportunities; this round implements only the approved benchmark fixture. Full check: 2,485 tests, one acknowledged aggregate type-checker coverage failure (`ERROR_ARGUMENT_IN_TYPE_INPUT`), 166 skipped; all seven fixture/preparation tests pass. The round retains commands, identities, hashes, iterations, statistics, JFR exports and test evidence. Changes remain uncommitted for review.

### 2026-10-08 22:46:00 UTC — prepared callbacks correct the overhead benchmark

Host: `raymie-stata-codex`; KVM guest, Intel Xeon 6975P-C, 32 cores / 64 logical CPUs, one NUMA node, 495 GiB RAM, no swap, `cpu.max=max 100000`. Corretto 21.0.4+7-LTS, JMH 1.36, compiler Blackholes, one JMH thread and one Resolution dispatcher thread. Session: `01a11ce9-fb33-7b50-aa1f-35ab16fbef2e`. Worktree: `~/repos/3ht/projects/viaduct/oss`, branch `rstata--1perf`. Clean baseline `c9b53de9df17bddd4ba25fdfd72361c9e9129862`; clean candidate `113b0e877626afd08ba07901e518e92bbc267106`. [Round evidence](2026-10-08-precomputed-callbacks/README.md) preserves source/JAR/corpus identities, commands, every measured iteration, unchanged workload statistics, validation, and final Resolution-overhead JFR exports.

The overhead benchmark now returns setup-built field outputs and prepared node-ID lookups, and skips checker fixture assertions/fingerprint comparisons. A separate discovery world collects IDs without warming the measured registry's successor cache. Correctness tests and other benchmarks retain ordinary input-sensitive programs and input assertions. Controlled single-shot JMH (five warmups, eight measurements, three forks, 100 resolutions/operation) changes from 1.899357 ± 0.013031 to 1.497617 ± 0.008029 s/op: **18.994 → 14.976 ms/resolution**, a **4.017 ms (21.15%)** measurement reduction. This corrects the harness; no production engine speedup is claimed. All emitted corpus statistics are byte-identical.

The final profile has 530 execution samples and no fixture object/hash/scalar construction or output/input fingerprint traversal samples. Inclusive resolver relation evaluation falls to 3.96%; construction closure is 23.02%, input materialization 22.08%, successor analysis 16.79%, normalization 15.09%, and cycle checking 8.68%. Shares overlap. Final focused tests and static checks pass; full checks encounter the known randomized type-checker coverage issue, with baseline/candidate replay evidence retained. No full-check pass is claimed.

### 2026-10-08 06:28:19 UTC

The [inclusion review-fix controls](2026-10-08-inclusion-review-fixes/README.md) compare master `67ec32572b997293ab0879a130292c15457131b9`, with only the shared benchmark harness and corpus backported at `bb5e7e7c70c2cb19eac5665c44d5bd84286f9fd5`, against the complete fixed stack at `c80bcc5e5e5207dbab1c55f8e69c0ec443dfea3b` on the same host and corpus. The control contains no production-source changes from master. Ordinary evaluation now memoizes completed shared nodes per invocation, and ordinary evaluation and asynchronous activation prune nested branches contradicted by explicit conjunct requirements without DNF expansion. The regression suite covers failed and pending bindings, binding order, operation counts, wide/shared graphs, and production cell exclusion. These repairs preserve the compact representation and the model/Resolution cancellation boundary.

The broad 100-query Resolution corpus measured 3.586 +/- 2.251 s/op before and 2.382 +/- 2.578 s/op after, or 35.860 and 23.820 ms/resolution. Its 33.6% lower mean is descriptive because three single-shot samples produce wide confidence intervals. The separate one-resolution guarded-checker diagnostic measured depth 3: 0.247 -> 0.122 ms/op; depth 6: 1.697 -> 0.249 ms/op; depth 9: 22.356 -> 0.423 ms/op; depth 12: 720.351 -> 0.679 ms/op, about 1,061 times faster. These are complete-stack comparisons against master, not PR2-only comparisons or isolated measurements of the latest fixes. The linked evidence records every iteration, JMH errors, host/JVM/parameters, resource hashes, and all emitted corpus statistics; both runs emitted identical statistics.

### 2026-10-06 20:02:52 UTC

Base revision: `bc623c504`. The non-distributing inclusion-condition DAG and compact closure changes described here were in the worktree.

This round repeated the focused guarded-checker diagnostic after replacing eager DNF distribution with a shared `And`/`Or` condition DAG. Construction closure now discovers deltas before selection merge erases additive occurrence provenance and locally merges each fixed resolver/checker fragment before guarding it. Activation incrementally propagates bounded outcome summaries through the DAG while preserving independently ready true alternatives and existing ordinary-error precedence.

The run used the same host, Corretto 21.0.4, JMH 1.36, one fork, one thread, three one-second warmup iterations, and five one-second average-time measurement iterations as the baseline. The command was `./gradlew :core:engine:runtime2:guardedCheckerResolutionBenchmark --console=plain`.

| Depth | Measured iterations (ms/op) | JMH score | Previous score |
| --- | --- | --- | --- |
| 3 | 0.144, 0.154, 0.177, 0.145, 0.145 | 0.153 +/- 0.053 ms/op | 0.220 +/- 0.007 ms/op |
| 6 | 0.327, 0.289, 0.320, 0.275, 0.297 | 0.302 +/- 0.083 ms/op | 1.440 +/- 0.047 ms/op |
| 9 | 0.471, 0.456, 0.465, 0.453, 0.457 | 0.460 +/- 0.029 ms/op | 15.486 +/- 1.592 ms/op |
| 12 | 0.813, 0.697, 0.688, 0.686, 0.691 | 0.715 +/- 0.211 ms/op | 203.556 +/- 39.410 ms/op |

The post-change series grows approximately linearly over this diagnostic range. At depth 12 it is about 285 times faster than the baseline, while the smaller improvement at depth 3 is consistent with fixed execution overhead dominating the shallow case.

### 2026-10-05 02:24:18 UTC

Base revision: `d9e75e5ac`. The benchmark-profile, generated-corpus, and focused-benchmark changes described here were in the worktree.

This round refreshed the Resolution-focused benchmark baseline before superlinear repairs. The arbitrary full profile and fixed corpus now cover resolver object and Query fragments, `FromArgument`, `FromObjectField`, `FromQueryField`, and `FromProvider` variables, singleton coercion, selection-aware nodes, and runtime-success field and type checkers. The corpus search requires actual activation of every variable-source family and both checker families. List fanout is fixed at two so large result lists do not dominate this investigation.

The fixed resources have SHA-256 hashes `6087a6ecf2e14215eade69b9e4a8448d6c98061cfee3974f5e811a549e1076c5` for `schema.graphqls`, `525f75fd9ec08b24c379bfdd86ae802d1a777cb186570eb015129da117e18a0e` for `registry.json.gz`, and `5157978ea78a8c51f6359f8f07b8e240e2675432e0ca590e3b81abfef41a308c` for `queries.json`.

The broad Resolution overhead control used Corretto 21.0.4 and JMH 1.36 with one fork, one thread, one warmup iteration, three single-shot measurement iterations, `loopCount=1`, and 100 resolutions per operation.

| Benchmark | Measured iterations | JMH score | Work per operation | Mean per unit |
| --- | --- | --- | --- | --- |
| Resolution overhead | 2.891, 2.827, 2.785 s/op | 2.834 +/- 0.971 s/op | 100 resolutions | 28.340 ms/resolution |

```text
Resolver overhead corpus statistics (100 queries):
  fields returned: average=296.39, p90=422, max=487
  active fields returned: average=115.54, p90=165, max=202
  passive fields returned: average=180.85, p90=260, max=291
  passive fields per active field: average=1.60, p90=1.92, max=2.56
  resolvers executed: average=280.27, p90=386, max=453
  field checkers executed: average=84.94, p90=114, max=133
  type checkers executed: average=46.48, p90=64, max=74
  resolver executions with variable-bearing arguments: average=48.00, p50=49, max=88
  variable-bearing arguments per such resolver execution: average=1.42, p90=3, max=3
  maximum variable stack depth: average=1.84, p50=2, max=2
  result depth: average=7.00, p90=7, max=7
Resolver benchmark registry statistics:
  active fields per non-Query object: average=1.92, p90=3, max=4
  passive fields per non-Query object: average=14.85, p90=17, max=19
  selections per object fragment: average=4.89, p90=22, max=48
  object fragment depth: average=1.61, p90=5, max=7
Resolver benchmark feature counts:
  field resolvers: 38
  field checkers: 26
  type checkers: 13
  Query fragments: 10
  FromArgument variables: 2
  FromObjectField variables: 14
  FromQueryField variables: 15
  FromProvider variables: 25
```

The focused `GuardedCheckerResolutionBenchmark` increases registry-defined guarded resolver depth and ends the chain at a field checker. It is deliberately a diagnostic of the known Boolean-alternative product, not a fixed-registry/query-scaled request witness. Default average-time measurements were depth 3: `0.220 +/- 0.007 ms/op`; depth 6: `1.440 +/- 0.047 ms/op`; depth 9: `15.486 +/- 1.592 ms/op`; and depth 12: `203.556 +/- 39.410 ms/op`.

The full `./gradlew :core:engine:runtime2:check` gate passed in 5m 27s.

### 2026-09-27 07:57:14 UTC

Host: `raymie-stata-codex`; one Intel Xeon Platinum 8375C socket, 32 physical cores / 64 logical CPUs, 495 GiB RAM, no swap, one NUMA node, and cgroup `cpu.max=max 100000` (no quota).

Session: `01a0e18b-2eca-7b02-9ff1-60262b09597f`. Clean runtime revision: [`a1057d3f1c8b76da638e1b50dbcc329fea3eefaf`](https://github.com/airbnb/viaduct/commit/a1057d3f1c8b76da638e1b50dbcc329fea3eefaf). `git status --short` was empty before the serial benchmark controls and remained empty afterward. Profile evidence, exact commands, corpus checksums, raw JMH output, stress coverage, and historical reproduction patches are in [`2026-09-27-f5-field-checks`](2026-09-27-f5-field-checks).

F5 adds symbolic/runtime field checks to Resolution. Its generated stress investigation used a narrow JFR recording of the failing denial case, exposing construction/parent closure, argument rebuilding, forest merging, and cycle-graph work. The delivered implementation restores cycle-aware successor memoization, compacts duplicate producer demand by concrete key while preserving inclusion correlation, caches immutable occurrence hashes, shares correctness-replay caches per result identity within one judgment, and reuses already-canonical concrete keys. The original depth-two success reproducer passes. The original depth-two denial reproducer remains limited by symbolic dependency-tree amplification: the counting probe observed 129,788 resolver invocations and 52,321 associated Query OERs over 15,379 ms including timeout cancellation. Broad runtime checker generators now use fragment depth one; the [scalability catalog](2026-09-27-f5-field-checks/scalability-catalog.md) records this input reduction separately from repairs. Timeouts, product sizes, exactness oracles, and activated coverage requirements remain unchanged.

All three closeout controls passed serially on the otherwise idle host using Corretto 21.0.4+7-LTS and JMH 1.36 with default benchmark parameters: one fork, one JMH thread, single-shot timing, `loopCount=1`; overhead and correctness use one warmup and three measurements, while the frozen property case uses two warmups and five measurements. Correctness uses `inputCount=50` and `querySeed=1`. The build used in-process Kotlin compilation, a 3 GiB Gradle heap, 1 GiB metaspace, and two Gradle workers; these are build-process settings, not benchmark JVM heap overrides. These fixed controls contain no field checkers and do not characterize the denial workload. No controlled before/after benchmark was captured, so no measured speedup is claimed or inferred from older log entries.

| Benchmark | Measured iterations (s/op) | JMH score and error (s/op) | Work per operation | Mean per unit |
| --- | --- | --- | --- | --- |
| Resolution overhead | 3.022, 2.977, 2.960 | 2.986 +/- 0.582 | 100 resolutions | 29.860 ms/resolution |
| `correctResolution` | 1.171, 1.147, 1.004 | 1.107 +/- 1.651 | 50 judgments | 22.140 ms/judgment |
| Frozen property test | 1.405, 1.400, 1.412, 1.403, 1.396 | 1.403 +/- 0.022 | 1 case / 12,763 expected resolver applications | 1.403 s/case |

All emitted overhead statistics for the unchanged 100-query corpus follow, with the original percentile labels. Result-shape counts should not be assumed equal to older semantic revisions; this round did not measure the pre-F5 revision to attribute any intervening change.

```text
Resolver overhead corpus statistics (100 queries):
  fields returned: average=244.24, p90=347, max=570
  active fields returned: average=71.60, p90=113, max=214
  passive fields returned: average=172.64, p90=241, max=356
  passive fields per active field: average=2.71, p90=3.68, max=5.76
  resolvers executed: average=68.73, p90=107, max=165
  resolver executions with variable-bearing arguments: average=5.72, p50=9, max=20
  variable-bearing arguments per such resolver execution: average=1.00, p90=1, max=1
  maximum variable stack depth: average=0.81, p50=1, max=1
  result depth: average=5.85, p90=6, max=6
Resolver benchmark registry statistics:
  active fields per non-Query object: average=1.88, p90=4, max=5
  passive fields per non-Query object: average=14.31, p90=18, max=18
  selections per object fragment: average=4.48, p90=18, max=39
  object fragment depth: average=1.63, p90=5, max=9
```

Validation passed 15,000 stress cases (2,500 checker success on one thread, 2,500 checker denial on 100 threads, and 10,000 unchanged deep cases). The final full check at seed `424242` passed in 5m 40s, reporting 2,140 regular tests with 226 existing skips and no failures or errors. Type-check discovery and execution-adapter checker integration remain subsequent milestones.

### 2026-09-04 00:22:58 UTC

Host: `raymie-stata-codex`; one Intel Xeon Platinum 8375C socket, 32 physical cores / 64 vCPUs, 495 GiB RAM, no swap, and one NUMA node.

Session: `01a06931-8c5a-7253-812b-fcba74510836`

Runtime revision: [`44e941921f6372ddb6a415c826ce35af4d8abbbc`](https://github.com/airbnb/viaduct/commit/44e941921f6372ddb6a415c826ce35af4d8abbbc); final test-only revision: [`6bb476427b4858a8f3d4a33db429e91f9ebfd64b`](https://github.com/airbnb/viaduct/commit/6bb476427b4858a8f3d4a33db429e91f9ebfd64b)

Profile evidence: [`2026-09-04-44e94192`](2026-09-04-44e94192)

This investigation retained the requested disabling of `FieldValueResolver.evaluateRelation`'s recursive `requireArgumentlessObjectFields()` validation, then profiled Resolution overhead and `correctResolution` with three prepared-workload repetitions on Corretto 21.0.4 and JMH 1.36. All benchmarks ran serially with default parameters. The initial no-check controls at [`2caa03b7a`](https://github.com/airbnb/viaduct/commit/2caa03b7a84a768639f079807a663e5789ce8fdd) scored 2.213 +/- 0.554 s/op for Resolution from iterations 2.199, 2.192, and 2.248, and 0.955 +/- 0.137 s/op for `correctResolution` from 0.960, 0.959, and 0.946. Against the checked [`9ff5f96f7`](https://github.com/airbnb/viaduct/commit/9ff5f96f72d647295530305f8146bb6750091ab2) controls of 2.573 and 1.119 s/op, commenting out the validation improved the two cases by 14.0% and 14.7%.

| Benchmark | Final measured iterations | Final JMH score | Work per operation | Final mean per unit | Change from checked [`9ff5f96f7`](https://github.com/airbnb/viaduct/commit/9ff5f96f72d647295530305f8146bb6750091ab2) |
| --- | --- | --- | --- | --- | --- |
| Resolution overhead | 1.901, 1.872, 1.897 s/op | 1.890 +/- 0.284 s/op | 100 resolutions | 18.900 ms/resolution | -26.5% |
| `correctResolution` | 0.846, 0.860, 0.845 s/op | 0.850 +/- 0.150 s/op | 50 judgments | 17.000 ms/judgment | -24.0% |
| Frozen property test | 0.786, 0.729, 0.765, 0.764, 0.762 s/op | 0.761 +/- 0.078 s/op | 1 property case / 12,763 expected resolver applications | 0.761 s/case | -3.8% |

Resolution overhead corpus statistics for 100 queries were unchanged:

```text
fields returned: average=301.52, p90=448, max=732
active fields returned: average=100.24, p90=169, max=295
passive fields returned: average=201.28, p90=297, max=437
passive fields per active field: average=2.25, p90=3.00, max=4.84
resolvers executed: average=68.73, p90=107, max=165
resolver executions with variable-bearing arguments: average=5.72, p50=9, max=20
variable-bearing arguments per such resolver execution: average=1.00, p90=1, max=1
maximum variable stack depth: average=0.81, p50=1, max=1
result depth: average=8.58, p90=9, max=9
active fields per non-Query object: average=1.88, p90=4, max=5
passive fields per non-Query object: average=14.31, p90=18, max=18
selections per object fragment: average=4.48, p90=18, max=39
object fragment depth: average=1.63, p90=5, max=9
```

The initial no-check profiles exposed three low-risk fixture-construction redundancies. [`5d6754a61`](https://github.com/airbnb/viaduct/commit/5d6754a61c1e892626f3e8c08bc7dbac3ead9f47) skips the `distinct()` allocation when an `ObjectValueScope.field` call has fewer than two arguments; its immediate controls improved Resolution from 2.213 to 2.085 s/op (5.8%) and `correctResolution` from 0.955 to 0.934 s/op (2.2%). [`2618bf2e3`](https://github.com/airbnb/viaduct/commit/2618bf2e3ea46a98a1c6a2ae08f1693c6b5606ca) adds a canonical-field overload and reuses the field that generated `ObjectPlan` materialization had already lowered instead of repeating source-coordinate recovery; its controls improved Resolution from 2.085 to 2.012 s/op (3.5%) and correctness from 0.934 to 0.884 s/op (5.4%). [`44e941921`](https://github.com/airbnb/viaduct/commit/44e941921f6372ddb6a415c826ce35af4d8abbbc) transfers the private factory-owned EOD value map into its private immutable implementation instead of immediately copying it; its controls improved Resolution from 2.012 to 1.890 s/op (6.1%) and correctness from 0.884 to 0.850 s/op (3.8%). Together, these three changes improve the no-check controls by 14.6% and 11.0%.

The final profiles are again dominated by schema-directed fixture-output work. Resolution CPU samples are led by `conformsToOutputSchemaType` at 19.26%, `GJSchema.lowerOrdinaryOutput` at 11.68%, and source-coordinate lowering at 7.38%; correctness reports 17.60%, 12.40%, and 7.20% respectively. Those operations enforce real type and source/lowered-schema boundaries, so no further validation traversal was removed. Final recorded GC pause totals were 63.3 ms for Resolution and 32.3 ms for correctness and do not dominate either workload.

Disabling `requireArgumentlessObjectFields()` intentionally weakens the selective resolver-output contract: argument-bearing fields supplied in resolver output are no longer rejected. The existing rejection test is preserved but explicitly ignored at [`6bb476427`](https://github.com/airbnb/viaduct/commit/6bb476427b4858a8f3d4a33db429e91f9ebfd64b). The full `:engine:runtime2:test :engine:runtime2:test :engine:runtime2:test` gate passed with that one deliberate skip. No corpus or benchmark code changed.

### 2026-09-03 21:57:03 UTC

Host: `raymie-stata-codex`; one Intel Xeon Platinum 8375C socket, 32 physical cores / 64 vCPUs, 495 GiB RAM, no swap, and one NUMA node. This is materially different hardware from the older entries that share the host name, so their absolute scores are not direct controls.

Session: `01a06931-8c5a-7253-812b-fcba74510836`

Baseline revision: [`bd96586f137d2b33f16ef5795102ed05a2e754e0`](https://github.com/airbnb/viaduct/commit/bd96586f137d2b33f16ef5795102ed05a2e754e0); post-rebase revision: [`9ff5f96f72d647295530305f8146bb6750091ab2`](https://github.com/airbnb/viaduct/commit/9ff5f96f72d647295530305f8146bb6750091ab2)

This investigation ran all four documented Resolution benchmarks serially with their default parameters on Corretto 21.0.4 and JMH 1.36. It then used the `resolutionOverheadProfile`, `correctResolutionProfile`, and `propertyTestProfile` targets with loop count three, controlled historical checkouts in a temporary worktree, and one temporary current-revision ablation. The checked-in worktree was not changed by the controls or ablation.

| Benchmark | Measured iterations | JMH score | Work per operation | Mean per unit |
| --- | --- | --- | --- | --- |
| Resolution overhead | 2.568, 2.531, 2.529 s/op | 2.543 +/- 0.398 s/op | 100 resolutions | 25.430 ms/resolution |
| `correctResolution` | 1.157, 1.114, 1.118 s/op | 1.130 +/- 0.428 s/op | 50 judgments | 22.600 ms/judgment |
| Frozen property test | 0.807, 0.783, 0.825, 0.779, 0.813 s/op | 0.801 +/- 0.076 s/op | 1 property case / 12,763 expected resolver applications | 0.801 s/case |
| Full generated workflow | 9.614, 9.228, 9.063 s/op | 9.302 +/- 5.159 s/op | 1,000 resolutions | 9.302 ms/resolution |

Resolution overhead corpus statistics for 100 queries:

```text
fields returned: average=301.52, p90=448, max=732
active fields returned: average=100.24, p90=169, max=295
passive fields returned: average=201.28, p90=297, max=437
passive fields per active field: average=2.25, p90=3.00, max=4.84
resolvers executed: average=68.73, p90=107, max=165
resolver executions with variable-bearing arguments: average=5.72, p50=9, max=20
variable-bearing arguments per such resolver execution: average=1.00, p90=1, max=1
maximum variable stack depth: average=0.81, p50=1, max=1
result depth: average=8.58, p90=9, max=9
active fields per non-Query object: average=1.88, p90=4, max=5
passive fields per non-Query object: average=14.31, p90=18, max=18
selections per object fragment: average=4.48, p90=18, max=39
object fragment depth: average=1.63, p90=5, max=9
```

The historical [`5193dec7b`](https://github.com/airbnb/viaduct/commit/5193dec7ba656b6c748d8a39bd28a431b31d60e2) closeout is not directly comparable because the host now exposes a different CPU topology. Same-session controls at that revision scored 2.300 +/- 0.325 s/op for Resolution overhead from iterations 2.320, 2.287, and 2.292; 0.931 +/- 0.043 s/op for `correctResolution` from 0.933, 0.929, and 0.929; and 0.721 +/- 0.083 s/op for the frozen property case from 0.734, 0.717, 0.749, 0.693, and 0.713. Against those controls, the current revision is 10.6% slower in Resolution overhead, 21.4% slower in `correctResolution`, and 11.1% slower in the frozen property case.

The new Query-fragment and selective field-relation features do not produce a material net regression in these workloads. The last pre-Query-fragment Resolution revision [`1a6620ea0`](https://github.com/airbnb/viaduct/commit/1a6620ea0a3a6758f3db05eba2bd57b7f9bea8cc) scored 2.729 s/op for overhead and 1.145 s/op for `correctResolution`; the last pre-selective-relation revision [`42bc98feb`](https://github.com/airbnb/viaduct/commit/42bc98feb8ae46b083aa55c450aeaf28083c879c) scored 2.655 and 1.107 s/op respectively. The selective-relation commit [`ae363f35a`](https://github.com/airbnb/viaduct/commit/ae363f35acd36ee7fd522fc57044a55ecaebdcc1) scored 2.587 and 1.164 s/op, and the tested revision closes at 2.543 and 1.130 s/op. The fixed benchmark profile intentionally contains no Query fragments, so those comparisons primarily rule out incidental overhead in the shared paths rather than measuring the new Query-fragment work itself.

The reproducible regression boundary is the earlier sometimes-passive resolver plumbing. The pre-plumbing revision [`32c9529ff`](https://github.com/airbnb/viaduct/commit/32c9529ff02883128af755e1ad0208d348228e31) scored 3.458 s/op for overhead and 0.957 s/op for `correctResolution`; [`9adb1a1a8`](https://github.com/airbnb/viaduct/commit/9adb1a1a88a9a58556bb68d9223f6e27a7bb7f22), which adds the plumbing, scored 3.897 and 1.148 s/op, regressions of 12.7% and 20.0%. Later work recovered the overhead score but retained most of the correctness cost.

The cost comes from `FieldValueResolver.evaluateRelation` calling `output.requireArgumentlessObjectFields()` on every resolver evaluation. The method recursively walks every returned object and list to enforce the selective-relation rule that resolver outputs cannot supply argument-bearing object fields; correctness reapplication repeats the same walk. In the current profiles, `QPlanEngineObjectDataImpl.getSelections` plus `requireArgumentlessObjectFields` account for 9.28% of Resolution CPU samples and 10.87% of `correctResolution` CPU samples. The property profile localizes the remaining property-test slowdown to `correctResolution`: its two steady recorded repetitions spent 326 and 297 ms there, versus 439 and 389 ms in Resolution itself.

A current-revision ablation that removed only the `output.requireArgumentlessObjectFields()` call reduced Resolution overhead to 2.209 +/- 0.192 s/op from iterations 2.220, 2.210, and 2.199, 13.1% below the unmodified current result. It reduced `correctResolution` to 0.964 +/- 0.124 s/op from 0.972, 0.959, and 0.961, 14.7% below the unmodified current result and close to the 0.957 s/op pre-plumbing control. The ablation is diagnostic and was discarded; removing the validation outright would weaken the relation contract. A safe optimization should validate or encode the argumentless-output invariant once when fixture outputs are constructed, or cache the validation on immutable qplan-owned output objects, instead of recursively proving it on every execution and correctness reapplication.

The branch was subsequently rebased onto `1rv` at [`9ff5f96f7`](https://github.com/airbnb/viaduct/commit/9ff5f96f72d647295530305f8146bb6750091ab2), whose final commit, `Move checking to init blocks where possible`, adds constructor-time invariant checks to frequently created resolver task objects. The complete four-benchmark suite was rerun serially on the same host, JVM, corpus, and default parameters, using the pre-rebase [`bd96586f1`](https://github.com/airbnb/viaduct/commit/bd96586f137d2b33f16ef5795102ed05a2e754e0) measurements above as the direct baseline.

| Benchmark | Post-rebase measured iterations | Post-rebase JMH score | Work per operation | Post-rebase mean per unit | Change from baseline |
| --- | --- | --- | --- | --- | --- |
| Resolution overhead | 2.579, 2.583, 2.558 s/op | 2.573 +/- 0.250 s/op | 100 resolutions | 25.730 ms/resolution | +1.2% |
| `correctResolution` | 1.122, 1.117, 1.119 s/op | 1.119 +/- 0.049 s/op | 50 judgments | 22.380 ms/judgment | -1.0% |
| Frozen property test | 0.792, 0.762, 0.810, 0.823, 0.769 s/op | 0.791 +/- 0.101 s/op | 1 property case / 12,763 expected resolver applications | 0.791 s/case | -1.2% |
| Full generated workflow | 9.492, 9.376, 9.130 s/op | 9.332 +/- 3.374 s/op | 1,000 resolutions | 9.332 ms/resolution | +0.3% |

The post-rebase corpus statistics were unchanged. All four deltas are small, mixed in direction, and well within ordinary run-to-run variation, so the added constructor checks do not show substantial overhead in these workloads. Because no regression was detected, the conditional rerun at [`a75792b5e`](https://github.com/airbnb/viaduct/commit/a75792b5e5fd6fa7a368724c3fdf797941ad39c5), immediately before the checking commit, was not performed.

No runtime changes were retained. The current JFR recordings are in `build/reports/resolver-benchmarks/` and remain ordinary ignored build outputs.

### 2026-08-22 17:49:08 UTC

Host: `raymie-stata-codex`; KVM guest with one Intel Xeon 6975P-C socket, 48 physical cores / 96 vCPUs, 371 GiB RAM, no swap, and two NUMA nodes.

Session: `01a02a6b-c8a2-75c3-a427-76c799e8d325`

Tested revision: [`5193dec7ba656b6c748d8a39bd28a431b31d60e2`](https://github.com/airbnb/viaduct/commit/5193dec7ba656b6c748d8a39bd28a431b31d60e2)

Profile evidence: [`2026-08-22-5193dec7`](2026-08-22-5193dec7)

The runtime tree was clean and committed before the final test, benchmark, and profile sequence. The final default benchmarks ran serially on an otherwise idle host with Corretto 21.0.4 and JMH 1.36. The evidence README records exact commands and corpus hashes. The final JFR profiles repeated each prepared workload three times and retain phase events, hot methods, allocation sites, GC pauses, aggregated execution and allocation stacks, and raw-recording checksums; the raw recordings remain outside Git and can be regenerated from the tested SHA.

| Benchmark | Measured iterations | JMH score | Work per operation | Mean per unit |
| --- | --- | --- | --- | --- |
| Resolution overhead | 1.760, 1.748, 1.747 s/op | 1.752 +/- 0.130 s/op | 100 resolutions | 17.520 ms/resolution |
| `correctResolution` | 0.740, 0.745, 0.742 s/op | 0.743 +/- 0.043 s/op | 50 judgments | 14.860 ms/judgment |
| Frozen property test | 0.547, 0.551, 0.653, 0.599, 0.531 s/op | 0.576 +/- 0.193 s/op | 1 property case / 12,763 expected resolver applications | 0.576 s/case |

The closeout Resolution result is 24.6% faster than the session baseline of 2.325 s/op, and the frozen property result is 9.3% faster than the ten-iteration baseline average of 0.635 s/case. The property closeout had two slower iterations; the immediately preceding retained-revision control was tighter at 0.547 +/- 0.020 s/op from 0.550, 0.546, 0.554, 0.546, and 0.540. The closeout `correctResolution` result is 25.5% faster than the session's 0.997 s/op diagnostic baseline. These comparisons all use the same host, JVM, corpus revisions, default benchmark parameters, and post-[`86b9683a7`](https://github.com/airbnb/viaduct/commit/86b9683a7287aabf73c10c1c98a8b57641d8955b) corrected resolver semantics.

Resolution overhead corpus statistics for 100 queries:

```text
fields returned: average=301.52, p90=448, max=732
active fields returned: average=100.24, p90=169, max=295
passive fields returned: average=201.28, p90=297, max=437
passive fields per active field: average=2.25, p90=3.00, max=4.84
resolvers executed: average=68.73, p90=107, max=165
resolver executions with variable-bearing arguments: average=5.72, p50=9, max=20
variable-bearing arguments per such resolver execution: average=1.00, p90=1, max=1
maximum variable stack depth: average=0.59, p50=1, max=1
result depth: average=8.58, p90=9, max=9
active fields per non-Query object: average=1.88, p90=4, max=5
passive fields per non-Query object: average=14.31, p90=18, max=18
selections per object fragment: average=4.48, p90=18, max=39
object fragment depth: average=1.63, p90=5, max=9
```

Four retained changes account for the recovery. [`f64af7878`](https://github.com/airbnb/viaduct/commit/f64af7878d13305ebe1feef6874df590ac466436) caches stable `ObjectCellStore.keys` snapshots; its immediate property control was effectively flat at 0.631 s/op, but it removed the prior repeated key-set copies. [`0b24f4a5f`](https://github.com/airbnb/viaduct/commit/0b24f4a5f3fa94b50702f59f21fc24e5fa1de2ea) removes duplicate fixture output validation and improved Resolution from 2.325 to 1.962 s/op (15.6%) and the property case from 0.635 to 0.600 s/op (5.5%). [`5435d107a`](https://github.com/airbnb/viaduct/commit/5435d107a23ed84448cd9c9cf63bfeb1e763c265) reuses one empty resolved-argument value and bypasses coercion for empty, default-free, optional argument definitions; its controls improved Resolution from 1.962 to 1.836 s/op (6.4%) and the property case from 0.600 to 0.556 s/op (7.3%). [`5193dec7b`](https://github.com/airbnb/viaduct/commit/5193dec7ba656b6c748d8a39bd28a431b31d60e2) constructs a missing-cell exception only when freeze encounters an unclaimed reader placeholder; its Resolution control improved from 1.836 to 1.803 s/op, while the stable property repeat improved from 0.556 to 0.547 s/op.

One attempted optimization was rejected. [`5bbfce6ad`](https://github.com/airbnb/viaduct/commit/5bbfce6ad7775ec3b08e283ae44643ec375745c6) eagerly cached each immutable resolver-input selection's first nested error; Resolution regressed from 1.962 to 2.190 s/op (11.6%) and the property case remained flat at 0.605 s/op, so [`55c2c424f`](https://github.com/airbnb/viaduct/commit/55c2c424f36f98b9b2b887584fc36d7faecae354) reverted it. Recursive error scans did not rank highly enough to justify eager cache construction.

The final profiles confirm the targeted allocation changes. In Resolution, `LinkedHashMap.sequencedEntrySet` fell from 50.24% of allocation pressure before empty-argument reuse to 0.52%; the remaining instances come from genuinely populated maps. Missing-cell `NoSuchElementException` construction no longer appears in any final top-100 allocation stack. Repeated `ObjectCellStore.keys` copies likewise remain absent.

The remaining Resolution CPU samples are led by `conformsToOutputSchemaType` at 20.35%, `HashMap.getNode` at 12.66%, and `GJSchema.lowerOrdinaryOutput` at 11.66%. The correctness profile is similarly led by output conformance at 16.75%, lowering at 16.26%, and source-coordinate lowering at 9.36%, because `conformsToResolvers` legitimately invokes fixture resolvers. These are broader fixture-output construction costs, not a newly isolated redundant pass after [`0b24f4a5f`](https://github.com/airbnb/viaduct/commit/0b24f4a5f3fa94b50702f59f21fc24e5fa1de2ea); changing them safely would require a stronger canonical-output construction boundary and is deferred. GC does not dominate: final recorded pause totals were 49.5 ms for Resolution, 18.8 ms for the property case, and 41.2 ms for `correctResolution`.

The property profile's three repetitions averaged 397.491 ms in Resolution, 128.353 ms in the application-identity oracle, 224.554 ms in `correctResolution`, 16.436 ms in object-path validation, 18.773 ms in request preparation, and 0.034 ms in witness snapshotting. The first repetition includes recording-start effects and was materially slower than the next two, so benchmark controls, not profile durations, remain the evidence for the retained speedups.

The full `:engine:runtime2:test :engine:runtime2:test` gate passed at the tested revision. Commit [`86b9683a7`](https://github.com/airbnb/viaduct/commit/86b9683a7287aabf73c10c1c98a8b57641d8955b) remains the semantic breadcrumb for the legitimate workload reduction caused by corrected resolver-input error propagation; this round does not treat that correction as a benchmark defect.

### 2026-08-22 17:12:04 UTC

Host: `raymie-stata-codex`; KVM guest with one Intel Xeon 6975P-C socket, 48 physical cores / 96 vCPUs, 371 GiB RAM, no swap, two NUMA nodes. The cloud instance type was not available from the guest.

Session: `01a02a6b-c8a2-75c3-a427-76c799e8d325`

Tested revision: [`91303870b87fe08cbb030ac4f28f4f7b0edbbe24`](https://github.com/airbnb/viaduct/commit/91303870b87fe08cbb030ac4f28f4f7b0edbbe24)

Profile evidence: [`2026-08-22-91303870`](2026-08-22-91303870)

This investigation used the existing frozen property-test and Resolution-overhead JMH benchmarks and the `propertyTestProfile`, `resolutionOverheadProfile`, and diagnostic `correctResolutionProfile` JFR targets. Profiles repeated their prepared workload three times with `propertyTestBenchmarkLoopCount=3`, `resolverBenchmarkLoopCount=3`, or `correctResolutionBenchmarkLoopCount=3`. The requested JMH benchmarks ran serially on an otherwise idle host with their default parameters on Corretto 21.0.4 and JMH 1.36. The property benchmark was repeated because its first result had wider iteration variance. The full four-benchmark closeout suite was not run because this investigation was scoped to the requested frozen property and Resolution benchmarks; `correctResolution` was added only as a diagnostic target.

| Benchmark | Measured iterations | JMH score | Work per operation | Mean per unit |
| --- | --- | --- | --- | --- |
| Resolution overhead | 2.305, 2.296, 2.375 s/op | 2.325 +/- 0.786 s/op | 100 resolutions | 23.250 ms/resolution |
| Frozen property test, run 1 | 0.610, 0.576, 0.750, 0.660, 0.574 s/op | 0.634 +/- 0.284 s/op | 1 property case / 12,763 expected resolver applications | 0.634 s/case |
| Frozen property test, run 2 | 0.593, 0.721, 0.631, 0.646, 0.592 s/op | 0.636 +/- 0.202 s/op | 1 property case / 12,763 expected resolver applications | 0.636 s/case |
| `correctResolution`, diagnostic | 0.976, 0.969, 1.047 s/op | 0.997 +/- 0.788 s/op | 50 judgments | 19.940 ms/judgment |

The two frozen-property runs average 0.635 s/case across their ten measured iterations, 7.8% slower than the last reported 0.589 s/case. The slowdown is reproducible, but the nominal Resolution improvement from 3.637 to 2.325 s/op is not comparable: error-propagation semantics shortened the fixed query workload from an average of 219.61 to 68.73 executed resolvers per query, a 68.7% reduction, while fields returned fell from 811.35 to 301.52. The diagnostic `correctResolution` result is likewise not comparable with its prior 1.530 s/op baseline because its prepared results are shortened by the same behavior.

Current Resolution overhead corpus statistics for 100 queries:

```text
fields returned: average=301.52, p90=448, max=732
active fields returned: average=100.24, p90=169, max=295
passive fields returned: average=201.28, p90=297, max=437
passive fields per active field: average=2.25, p90=3.00, max=4.84
resolvers executed: average=68.73, p90=107, max=165
resolver executions with variable-bearing arguments: average=5.72, p50=9, max=20
variable-bearing arguments per such resolver execution: average=1.00, p90=1, max=1
maximum variable stack depth: average=0.59, p50=1, max=1
result depth: average=8.58, p90=9, max=9
active fields per non-Query object: average=1.88, p90=4, max=5
passive fields per non-Query object: average=14.31, p90=18, max=18
selections per object fragment: average=4.48, p90=18, max=39
object fragment depth: average=1.63, p90=5, max=9
```

A controlled commit comparison on the same host and JVM located both changes at [`86b9683a7287aabf73c10c1c98a8b57641d8955b`](https://github.com/airbnb/viaduct/commit/86b9683a7287aabf73c10c1c98a8b57641d8955b) (`Propagate errors read from resolver inputs`). At [`ba3901a08fc5a3845fed3d80509dd7898996401e`](https://github.com/airbnb/viaduct/commit/ba3901a08fc5a3845fed3d80509dd7898996401e), the original Resolution workload scored 3.672 +/- 1.208 s/op from iterations 3.627, 3.748, and 3.642, matching the logged baseline. At [`9bd8cf4af689516e974075ab369a7480c5e2185e`](https://github.com/airbnb/viaduct/commit/9bd8cf4af689516e974075ab369a7480c5e2185e), the original workload scored 4.050 +/- 0.991 s/op from 4.008, 4.111, and 4.029, approximately 10.3% slower than [`ba3901a08`](https://github.com/airbnb/viaduct/commit/ba3901a08fc5a3845fed3d80509dd7898996401e); its frozen property case scored 0.595 +/- 0.095 s/op from 0.583, 0.568, 0.589, 0.601, and 0.633. At [`86b9683a7`](https://github.com/airbnb/viaduct/commit/86b9683a7287aabf73c10c1c98a8b57641d8955b), Resolution's workload changed to its current dimensions and scored 2.291 +/- 0.802 s/op from 2.274, 2.257, and 2.340, while the frozen property case regressed to 0.635 +/- 0.259 s/op from 0.613, 0.754, 0.593, 0.612, and 0.603. Propagated errors now terminate dependent resolver branches, which may be semantically correct, but the existing Resolution and `correctResolution` corpus timings no longer measure the workload represented by their logged baselines.

The archived and current property profiles agree with the JMH regression. Average steady-state Resolution time was effectively flat at 302.658 ms before and 300.035 ms now, while application-identity reconstruction rose from 110.699 to 129.268 ms, `correctResolution` rose from 190.856 to 218.183 ms, object-path validation rose from 10.566 to 15.738 ms, and request preparation rose from 6.844 to 8.351 ms. These phases together rose approximately 8.0%. Garbage collection does not explain the change: recorded property-profile pause time fell from 20.9 to 18.9 ms.

The clearest low-hanging allocation target is `ObjectCellStore.keys`: it returns `cells.keys.toSet()` on every access and accounts for 31.47% of current property-profile allocation pressure. The earlier profile attributed 31.68% to the underlying `LinkedHashMap.sequencedEntrySet` path, so this is longstanding overhead rather than the new regression. Correctness and witness traversals repeatedly request this complete copy. Caching a stable key set after `freeze()`, or replacing caller-side `key in keys` checks with direct membership and iteration APIs, should remove substantial allocation without changing semantics.

The current shortened Resolution profile attributes approximately 40% of CPU samples to generated-output coercion and validation: `conformsToOutputSchemaType` 14.85%, `coerceOutputValue` 13.48%, and `GJSchema.lowerOrdinaryOutput` 11.43%, with overlapping structural `HashMap.getNode` work at 11.09%. Generated canonical outputs currently pass through coercion, source-to-lowered traversal, and conformance, each recursively processing lists. An internal canonical-output construction path that proves these invariants once is the next strongest optimization candidate for the current workload.

The regression-introducing commit also added `firstErrorDataOrNull()` to every `QPlanEngineObjectDataImpl.get()`. It recursively scans list values whenever resolver input is read. Caching each immutable selection's first error would avoid repeated list scans and is a tightly scoped hypothesis for clawing back some of the commit-local cost, but this helper did not rank among the top CPU frames in the narrow profile and therefore needs a controlled benchmark before being treated as a demonstrated hotspot. Structural map hashing and lookup remain visible but are lower priority than eliminating complete key-set copies and duplicate output traversals.

No runtime code changed during this investigation. The reduced Resolution work is a legitimate consequence of corrected error-propagation semantics, not a benchmark defect, but it prevents a direct timing comparison with earlier runs. Future log entries should preserve the emitted workload statistics and identify semantic commits that materially change them so apparent discontinuities have an explicit explanation.

### 2026-08-21 14:33:31 UTC

Host: `raymie-stata-codex`; KVM guest with one Intel Xeon 6975P-C socket, 48 physical cores / 96 vCPUs, 371 GiB RAM, no swap, two NUMA nodes. The cloud instance type was not available from the guest.

Session: `01a0221d-5b61-76d2-9afc-13a06668c652`

Base revision: [`568dbc95d6b93342ed94b770814c95624d5fd291`](https://github.com/airbnb/viaduct/commit/568dbc95d6b93342ed94b770814c95624d5fd291); the profiling documentation and query-corpus changes described here were in the worktree.

Profile evidence: [`2026-08-21-568dbc95`](2026-08-21-568dbc95)

This session added the three narrow JFR targets documented above and the isolated `correctResolution` and frozen property-test JMH benchmarks. The frozen property workload serializes Resolution broad-campaign round 46's `symbolic-identity` case at historical coordinate `S=10 R=4 Q=3`, so its 12,763 expected resolver applications remain stable as generators evolve. The property profile's phase events made Resolution, application-identity reconstruction, `correctResolution`, and from-field binding validation independently visible.

Initial profiles identified repeated schema-coordinate recovery, output-type conformance checks, temporary required-argument sets, structural map hashing, and repeated property-oracle materialization as useful targets. Changes made during the session use direct required-argument checks, canonical qplan type identity when available, deterministic source-to-lowered coordinate navigation, direct `HMap` membership checks, and a streaming registered-resolver-occurrence traversal that carries canonical fields and accumulates identity counts without intermediate occurrence and grouping collections. Focused equivalence tests compare the streaming traversal with the prior sorted traversal, including complete paths, canonical fields, application keys, containing-object identity, binding outcomes, and fingerprint-bound behavior.

The frozen property case began the session at approximately 1.50 s per case and closed at 0.589 s per case, approximately 61% less elapsed time or 2.5 times the original throughput. That comparison uses the same serialized workload and host, but the original control was recorded earlier in the session rather than in the closing run below.

The overhead corpus now also checks in the exact ordered batch of 100 query sources generated with seed 1. The post-serialization run reproduced every pre-serialization corpus statistic exactly. Resolution moved from 3.664 to 3.637 s/op (0.7% faster); this sub-1% difference is not material and is consistent with run-to-run noise. Ordinary benchmark and profile tasks now load this snapshot, while explicit generation tasks own deliberate corpus replacement.

Closing benchmarks used JMH 1.36 on Corretto 21.0.4 and default parameters.

| Benchmark | Measured iterations | JMH score | Work per operation | Mean per unit |
| --- | --- | --- | --- | --- |
| Resolution overhead | 3.612, 3.690, 3.611 s/op | 3.637 +/- 0.823 s/op | 100 resolutions | 36.370 ms/resolution |
| `correctResolution` | 1.577, 1.495, 1.517 s/op | 1.530 +/- 0.776 s/op | 50 judgments | 30.600 ms/judgment |
| Frozen property test | 0.583, 0.554, 0.684, 0.568, 0.559 s/op | 0.589 +/- 0.207 s/op | 1 property case / 12,763 resolver applications | 0.589 s/case |

Resolution overhead corpus statistics for 100 queries:

```text
fields returned: average=811.35, p90=1535, max=2112
active fields returned: average=255.67, p90=513, max=718
passive fields returned: average=555.68, p90=1044, max=1394
passive fields per active field: average=2.38, p90=3.13, max=4.64
resolvers executed: average=219.61, p90=455, max=607
resolver executions with variable-bearing arguments: average=27.24, p50=25, max=113
variable-bearing arguments per such resolver execution: average=1.00, p90=1, max=1
maximum variable stack depth: average=0.81, p50=1, max=1
result depth: average=15.04, p90=15, max=18
active fields per non-Query object: average=1.88, p90=4, max=5
passive fields per non-Query object: average=14.31, p90=18, max=18
selections per object fragment: average=4.48, p90=18, max=39
object fragment depth: average=1.63, p90=5, max=9
```
