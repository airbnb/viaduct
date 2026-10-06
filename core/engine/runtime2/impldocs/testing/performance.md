# Performance Testing

Runtime2 performance testing separates stable JMH measurements from diagnostic JFR profiles. The full Resolution benchmark and fixed current-profile corpus exercise selective node and field resolvers, resolver object fragments, and `FromArgument` and `FromObjectField` variables. Their generation configurations disable named GraphQL fragments and leave resolver Query fragments and `FromQueryField` variables disabled. These Query workloads do not measure access-check overhead, ordered mutation effects, nested execution, or production dispatcher/service integration. The property-test benchmark uses its own frozen case; inspect that recipe before attributing a feature cost to its timings.

This document owns maintained benchmark, profiling, corpus, and reporting practice. Dated measurements and investigation findings live in the [performance history](../evidence/profiles/performance-history.md) beside their exported profile evidence.

## Running And Reporting

Run benchmarks from the repository root:

```shell
./gradlew :core:engine:runtime2:resolutionFullBenchmark --console=plain
./gradlew :core:engine:runtime2:resolutionOverheadBenchmark --console=plain
./gradlew :core:engine:runtime2:correctResolutionBenchmark --console=plain
./gradlew :core:engine:runtime2:propertyTestBenchmark --console=plain
```

The full benchmark measures the generated workflow, the overhead benchmark isolates Resolution over a fixed corpus, the correctness benchmark isolates `correctResolution`, and the property-test benchmark measures one frozen end-to-end generated case.

After every run, report each measured iteration, the final JMH score and error, units, the number of top-level resolutions, property cases, or correctness judgments in one JMH operation, and mean time per resolution, case, or judgment. For overhead runs, also report every emitted corpus statistic with its actual percentile label rather than describing all percentiles as P90.

Run closeout controls serially on an otherwise idle host with default parameters unless the investigation explicitly requires and records an override. Comparisons must identify the tested revision, host and relevant hardware, JVM and JMH versions, benchmark parameters, corpus revision or hashes, and any semantic change that altered the measured workload. Do not infer a speedup across different hosts, JVMs, parameters, or corpora.

## Choosing A Profiling Target

Use the narrowest JFR target that still contains the behavior under investigation:

| Target | Use it to investigate | Deliberately excluded |
| --- | --- | --- |
| `resolutionOverheadProfile` | Resolution itself over the fixed current-profile corpus | Query-resource loading and parsing, request preparation, correctness oracles, and statistics reporting |
| `correctResolutionProfile` | The `correctResolution` judgment over prepared diverse inputs | Resolution execution, query generation and parsing, witness preparation, fragment merging, and binding instantiation |
| `propertyTestProfile` | One frozen end-to-end Resolution property case, including its correctness oracles | Resource decoding and `TestWorld` assembly |

The full Resolution benchmark intentionally has no dedicated profiling task. It includes generation and validation and is useful as an end-to-end performance indicator, but it is too broad to explain most hotspots. Start with the property-test profile when the expensive phase is unknown, then move to the Resolution-overhead or `correctResolution` profile when its phase events identify one of those components.

Profile timings include JFR overhead and only one measured iteration. Use the matching JMH benchmark, not the profile duration, to report an improvement. Increase the target's loop-count property when more samples are needed without admitting setup work.

## Inspecting And Preserving Profiles

The JDK `jfr` command provides useful first-pass reports:

```shell
jfr summary core/engine/runtime2/build/reports/resolver-benchmarks/property-test.jfr
jfr view hot-methods core/engine/runtime2/build/reports/resolver-benchmarks/property-test.jfr
jfr view allocation-by-site core/engine/runtime2/build/reports/resolver-benchmarks/property-test.jfr
jfr view gc-pauses core/engine/runtime2/build/reports/resolver-benchmarks/property-test.jfr
jfr print --events qplan.PropertyTestPhase core/engine/runtime2/build/reports/resolver-benchmarks/property-test.jfr
```

Replace the path with the Resolution-overhead or `correctResolution` recording as appropriate. Java Mission Control is useful when call-tree, allocation, or timeline exploration needs more context than the command-line views provide. Each profiling task deletes its configured output before recording, so set its output property to a unique path before a comparison run when the previous recording must be retained.

Run benchmarks and profiles from a clean committed Runtime2 tree. Record the exact commit and confirm that `git status --short` is empty before measurement. If exceptional circumstances require profiling a dirty tree, preserve its complete patch and state explicitly that the recorded commit is insufficient to reproduce the run.

Create one checked-in directory under `impldocs/evidence/profiles` for each profiling round and add its dated interpretation to the [performance history](../evidence/profiles/performance-history.md). The history entry must record the timestamp, host and relevant hardware, agent session ID, tested revision, targets used, findings, retained or rejected changes, and controlled before/after results where available. The round README must record the commands, JVM, benchmark parameters, corpus hashes, and raw benchmark iterations or point to the history entry containing them. Export each JFR with:

```shell
core/engine/runtime2/export-resolver-profile.sh \
  RECORDING.jfr \
  core/engine/runtime2/impldocs/evidence/profiles/ROUND/TARGET
```

The exported bundle retains phase events, hot methods, allocation sites, GC pauses, aggregated execution and allocation stacks, and the raw recording checksum. Raw JFR files are optional; retain them outside Git until the investigation closes in case additional views are needed. Preserve emitted workload statistics and identify semantic commits that materially change them so timing discontinuities remain interpretable.

## Full Benchmark

`resolutionFullBenchmark` runs the complete generated property-testing workflow. One JMH operation generates and validates 100 schemas by two registries by five queries, for 1,000 property cases and top-level resolutions. Generation, world assembly, resolution-witness capture, resolution, and post-resolution validation are all timed.

## Overhead Benchmark

`resolutionOverheadBenchmark` loads one checked-in schema, registry, and ordered batch of 100 exact query sources. Before each measured invocation, JMH setup parses the fixed query batch against those shared static objects, creates a fresh immutable `Assumptions` value and `SharedOperationContext` for every resolution, and stores the prepared calls in an array. JMH excludes that setup; the measured method iterates the array, invokes the resolver, and consumes each result. Each resolver invocation obtains its canonical Query source through `ResolverRegistry.createRootQueryInput()` inside the measured call.

Control the repetition count with `-PresolverBenchmarkLoopCount=M`. One overhead JMH operation contains exactly `100 * M` resolver calls.

After the measured trial, an untimed reporting pass resolves the 100 queries once more with application observation enabled. It reports average, percentile, and maximum statistics for fields returned, resolvers executed, result depth, and three variable-workload measurements:

- Resolver executions with any variable-bearing arguments, using P50 because the corpus target is a median of at least 10 per query. Higher medians are preferred.
- Variable-bearing arguments per such resolver execution, using P90. An argument is variable-bearing when its open value recursively contains at least one variable.
- Maximum variable stack depth per query, using P50. A dependency edge connects an executed resolver application to another executed resolver application whose result supplies one of the first application's argument variables. Stack depth is the longest such chain, counted in dependency edges; independent or `FromArgument`-only applications have depth zero.

The report also separates active from passive result fields, reports their ratio, and describes the fixed registry's active/passive fields per non-Query object plus object-fragment recursive selection counts and depths.

For Resolution, each benchmark trial creates one configured dispatcher before any resolution-performing setup, reuses it across every iteration and resolution in the trial, and closes it at trial teardown. Dispatcher creation and cleanup remain outside measured work. Each measured call includes `runBlocking` on that trial-owned dispatcher, the 15-second request timeout, coroutine launch/join work, successor-demand computation, Query-source and result allocation, promise and access checks, and request-local cycle protection. Cycle protection registers each Cell writer and records reader-to-writer edges in concurrent maps before a potentially blocking read; it throws on a detected dependency cycle. The ordinary benchmark uses the operation's no-op `ResolverObserver`, but Resolution still constructs and submits each invocation observation. Variable-argument statistics are computed only when an instrumented consumer reads them. Statistics and full/property benchmark runs configure that same operation observer to collect compact invocation records and generated witnesses; there is no separate observed resolver entry point or model callback. Query-resource loading and parsing, `Assumptions` construction, resolution-witness capture, correctness validation, and statistics traversal are outside the measured method.

To profile only the Resolution measured body, run `./gradlew :core:engine:runtime2:resolutionOverheadProfile -PresolverBenchmarkLoopCount=M --console=plain`. The recording starts after invocation setup and stops immediately after the measured method, so it excludes query parsing, request preparation, the warmup, and the reporting pass. It is written to `core/engine/runtime2/build/reports/resolver-benchmarks/resolution-overhead.jfr`; override that location with `-PresolutionOverheadProfileOutput=PATH`.

`generateResolverBenchmarkQueries` deliberately replaces only the checked-in query snapshot by running the current query generator against the checked-in schema and registry. `generateResolverBenchmarkCorpus` writes a new schema, registry, and matching query snapshot together. Their query-generation controls are `-PresolverBenchmarkQueryCount=N` and `-PresolverBenchmarkQuerySeed=S`; ordinary benchmark and profile tasks never regenerate their inputs. Regenerating the snapshot changes the benchmark workload and must be recorded in the performance history.

## Correct Resolution Benchmark

`correctResolutionBenchmark` loads the checked-in benchmark schema and registry, generates 50 distinct queries from a fixed seed, and prepares one completed Resolution result, `SharedOperationContext` with completed variable bindings, and grounded root `ObjectSelectionForest` per query during trial setup. Setup also verifies each prepared judgment once. The measured method invokes only `correctResolution` over the prepared corpus and consumes each Boolean result; query generation, parsing, Resolution execution, witness capture, fragment merging, binding instantiation, and every other validation remain outside measurement.

Control the input corpus with `-PcorrectResolutionBenchmarkInputCount=N` and `-PcorrectResolutionBenchmarkQuerySeed=S`, and repeat the prepared corpus with `-PcorrectResolutionBenchmarkLoopCount=M`. One JMH operation contains exactly `N * M` correctness judgments. The prepared results and operation contexts are reused because `correctResolution` is read-only; changing that purity contract requires changing the benchmark setup.

To profile only the correctness judgments, run `./gradlew :core:engine:runtime2:correctResolutionProfile --console=plain`. This runs one unrecorded warmup iteration, then starts a JFR recording after trial setup and immediately before the single measured iteration. The recording therefore excludes query generation, Resolution execution, correctness-witness preparation, and the warmup. It is written to `core/engine/runtime2/build/reports/resolver-benchmarks/correct-resolution.jfr`; override that location with `-PcorrectResolutionProfileOutput=PATH`. Resolver and object-materialization frames can still legitimately appear: `conformsToResolvers` invokes each activated field resolver as part of the correctness judgment.

Inspect the recording with `jfr view hot-methods core/engine/runtime2/build/reports/resolver-benchmarks/correct-resolution.jfr`, `jfr view allocation-by-site core/engine/runtime2/build/reports/resolver-benchmarks/correct-resolution.jfr`, and `jfr view gc-pauses core/engine/runtime2/build/reports/resolver-benchmarks/correct-resolution.jfr`.

## Property Test Benchmark

`propertyTestBenchmark` replays one checked-in snapshot of Resolution broad campaign round 46's `symbolic-identity` case at `S=10 R=4 Q=3`. The resource records property seed `2026081300464`, the exact generated schema, executable registry recipe, and exact query source. It therefore remains the same workload when property-test generators, profiles, and random-consumption order change.

One measured property case creates a fresh immutable `Assumptions` value and semantic operation context, parses the frozen query, invokes Resolution, reconstructs and compares all 12,763 resolver-application identities, runs `correctResolution`, and validates from-field bindings. Resource decoding and `TestWorld` assembly happen once during trial setup. Two warmup cases precede five measured cases. Repeat the frozen case within each measured JMH operation with `-PpropertyTestBenchmarkLoopCount=M`; the default is one.

To profile the full measured property case, run `./gradlew :core:engine:runtime2:propertyTestProfile --console=plain`. This runs one unrecorded warmup case, then records one measured case to `core/engine/runtime2/build/reports/resolver-benchmarks/property-test.jfr`. Override the output with `-PpropertyTestProfileOutput=PATH`, and repeat the case inside the recording with `-PpropertyTestBenchmarkLoopCount=M`. The recording includes `qplan.PropertyTestPhase` duration events for request preparation, Resolution, witness snapshotting, application-identity reconstruction, `correctResolution`, and from-field binding validation.

`generatePropertyTestBenchmarkCorpus` is the provenance-preserving snapshot writer, not part of ordinary benchmark execution. It regenerates the historical coordinate through the current property generator and will intentionally fail if that generator no longer reproduces 12,763 applications. Do not regenerate the checked-in snapshot when generator evolution changes the coordinate; the benchmark's purpose is to retain the original serialized workload.

## Corpus Search

Run `./gradlew :core:engine:runtime2:generateResolverBenchmarkCorpus -PresolverBenchmarkCorpusSeed=S -PresolverBenchmarkCorpusSize=Schemas:Registries:Queries`. The task writes the winning schema and registry together with the exact overhead query snapshot selected by `-PresolverBenchmarkQueryCount=N` and `-PresolverBenchmarkQuerySeed=S`.

The default search evaluates 10 schemas, 5 registries per schema, and 10 random queries per pair. Search generation exposes controls for object-output frequency, scalar-biased nested query breadth, ordinary and long-tail object-fragment selection counts, and argument-field preference inside object fragments.

The search uses Resolution to measure actual expanded result size, depth, resolver applications, variable-bearing resolver activation, variable stack depth, owner dependencies, and query diversity. Registry eligibility targets roughly two active and fourteen passive fields per non-Query object, an overall passive/active field ratio between 4:1 and 7:1, object fragments averaging 3.5–5 recursive selections with P90 at least 10 and maximum at least 30, and both variable-source kinds. Workload eligibility requires at least 1,000 average result fields, at least 100 average resolver executions, activation of both variable-source kinds, and nonzero stacking; scoring then targets roughly 2,500 fields and 300 resolver executions while strongly rewarding a median of at least 10 variable-bearing resolver executions. Resolution timeouts and resolution-witness bound overflows disqualify a candidate. The winner is written under `src/jmh/resources/viaduct/engine/runtime2/benchmark/current-profile`; the SDL is human-readable, while Jackson stores the executable registry and query-generation recipe as explicit tree DTOs.

## Benchmark Workload Contract

- The current profile is evaluated with Resolution and covers selective node and field resolvers, resolver object fragments, `FromArgument` and `FromObjectField` variables, and no Query fragments.
- The expensive schema/registry search is offline and reproducible. It writes one winning GraphQL SDL schema and one JSON registry recipe as checked-in resources; benchmark invocations do not repeat that search.
- An ordered batch of 100 exact query sources is checked in. Each invocation parses and prepares that fixed corpus outside the measured method and executes it `M` times.
- Every measured invocation reuses one parsed schema and resolver registry across its `100 * M` resolutions, so static decoding and registry assembly are never part of per-resolution timing. Each resolution receives a fresh `SharedOperationContext`, whose variable bindings are monotonic per-operation state and must not leak across resolutions; setup currently also constructs a fresh immutable `Assumptions` value. The public resolver entry obtains a fresh root Query object from `ResolverRegistry.createRootQueryInput()`.
- The timed loop contains resolution and Blackhole consumption, including Resolution's ordinary runtime instrumentation described above. Query-resource loading and parsing, setup, witness capture, correctness validation, and statistics reporting remain untimed.
- Queries should be deep, with paths reaching about ten layers, and large enough to return roughly a thousand or more fields on average with a long list-derived tail, without making one JMH operation excessively long.
- A representative non-Query object should have about two active fields and fourteen passive fields. The accepted overall schema ratio is approximately five passive fields per active field, with the search currently allowing 4:1 through 7:1.
- Resolver object fragments should average about four recursive selections and have a long tail: P90 at least 10 and maximum at least 30. Fragment depth is measured separately.
- The workload must activate both `FromArgument` and `FromObjectField` variables in complex combinations. At least 10 resolver instances in the median query should have one or more variable-bearing arguments; the number of variable-bearing arguments per such resolver remains a reported characteristic rather than a hard target.
- The workload should execute a few hundred resolver instances per query rather than the earlier roughly 2,000-instance shape. Field count remains mostly list-derived, while non-list fields and result depth are reported to make that expansion legible.
- The report must include average, requested percentile, and maximum values for result fields, active and passive fields, passive/active ratio, resolver executions, variable-bearing resolver executions, variable-bearing arguments per such execution, stack depth, result depth, schema active/passive fields, and object-fragment selection count and depth.

## Known Workload Limit: Deeper Variable Stacking

The current fixed corpus reaches variable stack depth one but does not exercise a longer executed chain. A depth-two stack requires an application whose argument reads a value supplied by a second resolver application whose own argument, in turn, reads a value supplied by a third; merely having many variable-bearing arguments or large object fragments does not create this dependency topology.

Several current constraints make longer stacks uncommon. Keeping roughly two active fields among fourteen passive fields reduces possible resolver-to-resolver edges; scalar-biased query breadth preserves the passive-field ratio but activates fewer composite resolver chains; `FromArgument` variables improve variable coverage without adding a resolver-supplied dependency edge; and the registry generator's acyclic rank ordering prevents cycles but makes each additional dependency step progressively harder to place. A static owner dependency also helps only when one random query activates every resolver occurrence in the chain with compatible symbolic selections and variable instances.

Blindly increasing resolver density, object-fragment size, query branching, list size, or depth works against the other workload targets. Those changes distort the active/passive ratio and resolver count, and pathological combinations have already exceeded Resolution's 15-second bound, the resolution-witness fingerprint budget, or the corpus-search heap.

The next generator improvement should therefore construct an explicit acyclic owner-dependency chain of configurable length and make runtime activation of that chain an eligibility condition. It should preserve the existing active/passive, object-fragment distribution, result-size, resolver-count, variable-source, and bounded-generation constraints rather than trying to obtain deeper stacking through broader random generation.
