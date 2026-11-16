# ND-Backed Selection Forest on Schema 4, 2026-11-16

This experiment measures a deferred, shared construction forest backed by graphql-java PR [#4562](https://github.com/graphql-java/graphql-java/pull/4562), pinned to `69c7bfbef920e5b95b7c295b60ef0652523eef3d`. All cases use the upstream schema-4 large query and the same PR jar. It follows the [eager conversion experiment](../2026-11-16-normalized-document-schema4/README.md). The new request path retains ND selection-set references, decodes each shared field key once per request, and collects child forests only when observed. It bypasses the client operation's `SpecSelection` and `MaterializeSelectionForest` intermediates.

## Results

Each of the three cases below creates a fresh forest and recursively visits all **187,215 logical selections**, returning their count. This includes deferred collection work. Errors are JMH's 99.9% confidence half-widths; allocation uses decimal MB/op.

| Path | Time, ms/op | Allocation, MB/op |
| --- | ---: | ---: |
| Existing AST conversion + traversal | 153.641 ± 9.099 | 236.819 |
| Eager ND conversion + traversal | 32.099 ± 0.378 | 51.395 |
| Shared ND forest + traversal | 4.616 ± 0.133 | 3.781 |

The shared representation reduces the mean time by **97.00%** and allocation by **98.40%** relative to the AST path. Relative to eager ND conversion, it reduces the mean time by **85.62%** and allocation by **92.64%**. These are preparation-plus-traversal results, not end-to-end execution improvements. The integration combines direct ND interpretation, key caching and shared deferred forests; the AST comparison does not isolate the data structure as a sole causal factor.

The separate `startNormalized` case measures only creation of the request-local root view: **77.58 ± 1.07 ns/op**, allocating **480 B/op**. It performs no field decoding and is not comparable with complete consumption or execution.

Parsing, schema construction, validation/ND creation, request variable coercion, resolver execution, demand closure and response completion are excluded. Field argument decoding and the full occurrence traversal are included in the three main cases. This is a synthetic stress query with extensive fragment reuse; the results do not establish production-workload latency or savings for other schemas.

## Structural Census

A separate, untimed [census](ForestShape.java) traversed the completed representations by reference identity. The [output](forest-shape.txt) was produced locally using the same measured benchmark jar. Selection identity here is a JVM allocation observation, not semantic selection equality.

| Representation | Distinct Selection objects | Distinct key objects | Logical selections |
| --- | ---: | ---: | ---: |
| AST | 187,215 | 187,215 | 187,215 |
| Eager ND | 187,215 | 3,014 | 187,215 |
| Shared ND | 8,314 | 3,014 | 187,215 |

The shared representation exposes **95.56% fewer Selection objects** while preserving every logical occurrence. The raw census also reports forest handles reachable through the selection API; those counts exclude private delegating collections and are not a complete JVM object census. ND itself stores 4,278 source fields and 1,410 selection sets; 3,014 source fields remain after Runtime2 erases introspection fields.

## Run And Verification

The Hetzner AMD Ryzen 5 3600 server ran OpenJDK 25.0.5.0.3, JMH 1.36, G1GC, `-Xms2g -Xmx8g`, one benchmark thread, and two forks per case. Every fork used two 30-second warmup iterations and three 60-second measurement iterations. The four cases scheduled exactly **32 minutes** of iteration time; JMH reported **00:33:57** elapsed including setup. The run began at 2026-11-15T22:43:11Z (2026-11-16 08:43:11 Australia/Brisbane). A separate one-second-per-case [smoke run](smoke.log) checked setup before the timed run; it is not included in these results. No full-run results were discarded or rerun.

Each fork's trial setup compared the AST, eager ND and shared forests recursively by canonical key, possible types, inclusion condition and subselections, and checked that creating the shared root decoded no fields. Every fork passed. The fixture exceeds default GJ complexity limits, so validation in setup uses `QueryComplexityLimits.NONE`. The benchmark itself does not execute Resolution; separate adapter tests exercise production Resolution, compare producer demand, and verify exact resolver invocation counts and mutation order.

The default `:core:engine:runtime2:check` passed with 4,460 tests, including 144 skips, and no failures or errors. Eight incremental-label-validation tests passed. The opt-in adapter/model tests passed all 57 cases, and lint, documentation-label and package-layer checks passed. [Default check summary](default-check-summary.json), [focused summary](normalized-tests-summary.json), [default log](default-check.log), [focused log](normalized-tests.log) and [lint log](normalized-lint.log) retain the evidence.

The runner exited with code 0. The [original host settings](results/host-original-state.json) and [restored settings](results/host-restored-state.json) match. A separate live check verified every saved governor and service state, an available benchmark lock, and no remaining Java processes.

## Reproduction And Scope

The measured Viaduct base is `a2483562b1ef964d9f5d2ad6206a771b78594dbf` plus the [source snapshot](source-snapshot.tar.gz). [Source metadata](source-metadata.json) records snapshot and jar hashes. The measured JMH jar SHA-256 is `ccc916d8a308f416396788e6f1d0b36107bbfd5fa1d52c81b7ddd717b360b92b`; the GJ PR jar SHA-256 is `b5c586084b49da2e87bc3fcb6e6518309a1b8d0457a917ffc2c9ce105c4d0cc9`. A [post-snapshot patch](post-snapshot.patch) captures additional tests/documentation and the execution provider's fallback for custom inclusion callbacks. Timed methods and their forest implementations are unchanged by that patch.

Use the [benchmark guide](../../../testing/normalized-document-benchmark.md) to build the opt-in jar and run `run-normalized-document-benchmark.sh JMH_JAR RESULT_DIRECTORY backed`. [Raw JMH JSON](results/results.json), [JMH log](results/jmh.log) and [run metadata](results/metadata.txt) are retained.

The [integration and common-graph design](../../../integration/normalized-document.md) explains activation and remaining work. The current change implements client construction demand backed by ND, with mutation and custom-inclusion-callback fallback. It does not replace resolver/checker materialization templates or eliminate successor-demand traversals. Both forest roles can share one underlying graph, but construction and materialization require distinct collection rules and projection roots. The pinned PR remains an opt-in dependency experiment because other Viaduct components use APIs that it removes.
