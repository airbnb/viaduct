# Parent construction demand — 2026-10-09 UTC

This round repairs the nonempty-parent dependency-path multiplication described in the [superlinear repair plan](https://slate.airbnb.tools/PD5wEIAqR6/Runtime2+superlinear+growth:+findings+and+repair+plan). The baseline is `6b692129d4b1088529dd2e997588cdd239f47c6d`, immediately above `rstata--3perf`, in `/home/raymie_stata/repos/2ht/projects/viaduct/oss`, branch `rstata--tmp`. The candidate is the enclosing commit; [provenance](provenance.json) retains the tested source hashes, exact source patch, host/JVM, corpus identities and JAR hashes. The baseline JAR is the preceding round's verified final candidate. Builds, tests, operation diagnostics and benchmarks run serially.

## Repair

Completed resolver/checker analyses formerly retained a list of parent requests for every discovery path. Memoizing those analyses did not stop their lists from expanding through a Fibonacci dependency diamond. Requests now join by canonical parent field within one lookahead computation. Checked and unchecked demand, root type-check provenance, exact argument keys and applicability remain independent. Nested requirements compact as contributions join, preserving inherited guards and their first-alternative failure order. Forest normalization is memoized by immutable forest identity only within this computation; it caches neither runtime values nor bindings. Recursion-cut analyses retain their existing nonreusable status.

Shared interface selections remain shared. Exact-key/applicability groups preserve distinct abstract and concrete alternatives until the occurrence's runtime type is known. A 128-implementation witness rejected the first draft's eager concrete copies and now verifies compact representation plus each implementation's argument defaults. The implementation is localized to `ParentConstructionDemand.kt`, the symbolic parent-lookahead helper also used by the grounded controls. This repairs representation and repeated work within the existing parent protocol; the maintained-family contracts and Resolver23 stress control validate that boundary.

## Deterministic growth evidence

The original disabled parent witness fails when enabled against the baseline; [red output](before-regression.txt) is retained. The regression now requires at most two raw seed selections, one per checking provenance. [Probe sources](ParentGrowthProbe.java) and [bytecode instrumentation](ParentWorkProbe.java) reproduce the original depth-5/10/15/20 counts and inspect both scalar and composite ancestor requirements. Counters are active only inside lookahead; schema and registry construction are excluded. Instrumentation adds no production counters. ASM 9.8 precedes the benchmark JAR, which bundles an older ASM.

| Diamond depth | Parent-request allocations before → after | Retained seed selections before → after | Forest elements submitted to candidate normalization |
| ---: | ---: | ---: | ---: |
| 5 | 122 → 21 | 28 → 2 | 24 |
| 10 | 1,470 → 41 | 321 → 2 | 54 |
| 15 | 16,469 → 61 | 3,570 → 2 | 84 |
| 20 | 182,861 → 81 | 39,602 → 2 | 114 |

At depth 20, request allocations fall **182,861 → 81 (99.956%)**, and seed selections fall **39,602 → 2 (99.995%)**. Candidate depths 40 and 80 allocate 161 and 321 requests and still retain exactly one checked and one unchecked seed. Scalar normalization submissions grow as 6d−6; composite submissions grow as 18d−18, with the two nested fields preserved. The normalization counter sums input forest sizes at method entry, including cache hits; it bounds submitted work rather than counting allocated bytes. The request counter measures actual constructor executions. These witnesses establish linear growth for repeated fixed requirements on the tested acyclic diamonds, without asserting a global linear bound for arbitrary distinct requirement sets or condition graphs. [All 20 results](operation-counts/operation-counts.json) are retained.

```sh
python3 run-operation-counts.py BASELINE.jar CANDIDATE.jar --output COUNTS
```

## Controlled parent-phase benchmark

[ParentDemandBenchmark](ParentDemandBenchmark.java) isolates one symbolic parent-lookahead call on the same field-checker diamond. Trial setup builds the schema, registry and input. Each measured call creates its ordinary computation-local analysis; resolver callbacks and query execution are outside this phase measurement. JMH 1.36 uses Corretto 21.0.4+7-LTS, one benchmark thread, three one-second warmups, five one-second measurements and three fresh forks per depth and variant, with a fixed 2 GiB heap. The GC profiler supplies normalized allocated bytes per call. Errors are JMH's within-run 99.9% confidence errors. [Raw JSON, logs and commands](parent-benchmark) preserve every measurement; shared-host variance limits attribution.

| Depth | Before ms/lift ± error | After ms/lift ± error | Time saved | Allocated bytes/lift before → after |
| ---: | ---: | ---: | ---: | ---: |
| 0 | 0.005810 ± 0.000053 | 0.002451 ± 0.000009 | 57.81% | 8,776 → 6,704 |
| 5 | 0.047058 ± 0.000459 | 0.015897 ± 0.000243 | 66.22% | 63,952 → 35,872 |
| 20 | 45.049490 ± 0.208585 | 0.061215 ± 0.000349 | 99.86% | 58,510,445 → 130,560 |

```sh
python3 run-parent-benchmark.py BASELINE.jar CANDIDATE.jar --output RESULTS
```

## Existing 100-query control

The fixed prepared-callback, China-active overhead corpus has no applied `@parent` fields. This control therefore checks the unchanged workload rather than establishing the parent repair's benefit. Five warmups, eight single-shot measurements and three fresh forks use one 100-query batch per operation and one Resolution dispatcher thread. Mean time is **9.879 ± 0.065 → 9.843 ± 0.047 ms/query**. No elapsed-time change in this parent-free corpus is attributed to the repair. All emitted workload statistics are byte-identical; raw JSON retains all 24 measurements per variant. [Comparison](comparison.json) preserves the arithmetic.

```sh
java -jar JAR 'viaduct\.engine\.runtime2\.resolution\.ResolverBenchmark\.overhead' -p loopCount=1 -wi 5 -i 8 -f 3 -foe true -rf json -rff RESULT.json -jvmArgsAppend '-DresolverBenchmarkReportFile=STATISTICS.txt'
```

## Correctness and stress validation

The stress-report parser requires `defusedxml` in the Python environment used to run `run-stress.py` (`python3 -m pip install defusedxml`). This preserves report contents while using hardened XML parsing.

The final full Runtime2 check passes: **2,530 tests, zero failures/errors, 165 skips**. The final five-thread parent/provenance/successor gate passes, and the full check includes exact resolver/checker applications, ancestor reentry, object/Query provenance, argument identity, cancellation and failure contracts. New directed witnesses cover nested requirements with resolver/field/type checkers, exact resolver application counts, conditional descendants, concrete alternatives, overlapping interface/concrete guards, argument defaults, empty checked projections and first-alternative failure order. [Full check](final-full-check-and-build.txt), [suite summary](final-full-check-summary.json), [focused gate](final-focused.txt), and [wide-interface red witness](wide-interface-before.xml) retain the results.

```sh
./gradlew :core:engine:runtime2:ktlintFormat :core:engine:runtime2:check :core:engine:runtime2:jmhJar --no-configuration-cache --console=plain
```

All ten final stress invocations pass with their coverage guards intact. [Validation](stress/validation.json) retains exact commands, UTC start/end times and suite outcomes; corresponding logs and XML are retained in `stress/`.

| Final stress workload | Cases / coordinates | Threads |
| --- | ---: | ---: |
| Parent-focused, seed 2026090403 | 1,000 | 5 |
| Resolution deep, seed 20260809 | 10,000 | 1 |
| Field checkers: success, denial, mixed, passive, root-reference; seed 424242 | 2,500 each | 5 |
| Type checkers: success, denial, mixed; seed 2026093001 | 2,500 each | 5 |
| Multithreaded: five broad round-1 profiles and three type-checker profiles | 10,000 broad + 7,500 checker | 100 |
| Grounded Resolver23 deep, seed 20260809 | 10,000 | 1 |

Preliminary validation is historical evidence, not the final candidate gate. The first five-thread unit sweep reported `SymbolicKeyIdentityTest`'s unsynchronized `frankApplications += 1` counter as 2 instead of 3; its parent-free schema bypasses the changed helper, and the test passes in the normal full check. The test fixture's existing concurrency limitation remains documented rather than changing unrelated instrumentation in this commit. [Original sweep](five-thread-focused.txt) records that failure. A preliminary full check and checker stresses passed before the shared-interface correction; the first 100-thread run was deliberately interrupted to validate the final representation. [Initial outcomes](initial-stress-validation.json) and [interruption log](initial-multithreaded-interrupted.txt) preserve that distinction. The final full check and all final stress runs above are successful.

Archival JSON omits redundant trailing blank lines, and overhead histogram logs omit trailing spaces; every numeric sample is preserved.

The raw JARs remain under `/tmp/runtime2-parent-demand-20261009`. [Source patch](source-changes.patch) uses Treehouse-relative paths and zero context (`git apply --unidiff-zero` from the Treehouse root). This repair, tests and evidence form one dedicated commit; no push is performed.
