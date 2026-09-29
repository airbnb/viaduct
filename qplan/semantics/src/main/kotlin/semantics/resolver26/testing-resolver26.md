# Testing Resolver26

## Thread Count

Every Resolver26 test uses one externally configurable resolution thread count, including static contracts, generated properties, coordinate replays, deep stress, broad stress, and multithreaded campaigns. Ordinary tests default to one; the dedicated `resolver26MultithreadedStress` task defaults to 100.

The Gradle property, JVM property, and environment entry all use the durable configuration name `viaduct.resolution.threadcount`; the Gradle property is preferred in commands in this guide. The value must be a positive integer.

Direct-launcher campaign commands do not run their rounds under Gradle, so pass `viaduct.resolution.threadcount` through `env` for those commands.

The setting controls the fixed dispatcher inherited by all Resolver26 coroutines within a request. It does not make separate generated cases concurrent: cases are generated, resolved, and validated one at a time so a failure retains an exact seed and `S:R:Q` coordinate. Configuration is read once at each owning setup boundary rather than for every resolution.

`ResolutionDispatcherFactory.create(threadCount)` creates a fresh caller-owned fixed dispatcher and retains no pools. Ordinary JUnit tests reuse one configured dispatcher per concrete test class; each standalone property-test round and corpus tool owns one dispatcher for its complete computation; each JMH trial owns one dispatcher outside measured work; and the execution fixture owns its default dispatcher while an execution strategy borrows its embedding service's supplied context. Every owner closes its dispatcher after its lifetime. Threads are daemon-backed and named `resolver26-N-M`, where `N` is a process-local pool identifier and `M` is a thread identifier local to that pool. Neither number is the configured thread count. The names group thread-dump entries by pool and support profiler filtering and per-thread CPU sampling; they are diagnostic labels rather than observations or measurements.

## Concurrency Boundary

Everything invoked while a resolution is running must tolerate concurrent resolver applications. The application witness recorder uses a short synchronized append after constructing each immutable record, application counts and mutation-fixture caches use concurrent maps, and application ordinals use atomics.

Post-resolution validation is intentionally single-threaded. After `resolve` returns, the calling test coroutine snapshots instrumentation and serially evaluates application identities, structural coverage, `correctResolution`, from-field bindings, and any metamorphic comparison. Do not add synchronization to these pure snapshot consumers merely because resolution itself is concurrent.

Keep this division strict when adding instrumentation: capture concurrent events safely and cheaply during resolution, freeze or snapshot them after request quiescence, and perform expensive oracle work serially from the immutable snapshot. Never let a test-only recorder impose a scheduling dependency on Resolver26.

## Ordinary Runs

Run all non-stress Resolver26 tests on the default single thread:

```shell
../gradlew -p . :semantics:test --tests 'semantics.resolver26.*'
```

Run the same static, generated, witness, and mutation suite with five resolution threads:

```shell
../gradlew -p . :semantics:test --tests 'semantics.resolver26.*' -Pviaduct.resolution.threadcount=5
```

Run one class or test method by using the normal Gradle test filter and the same thread-count property:

```shell
../gradlew -p . :semantics:test --tests 'semantics.resolver26.SymbolicKeyIdentityTest' -Pviaduct.resolution.threadcount=2
```

Run the generated Resolver26 contracts under a fixed property seed:

```shell
../gradlew -p . :semantics:test --tests 'semantics.resolver26.ResolverGeneratedTest' -PresolverPropertySeed=424242 -Pviaduct.resolution.threadcount=5
```

Replay one exact generated coordinate with the same concurrency:

```shell
../gradlew -p . :semantics:resolverPropertyReplay -PresolverPropertyClass=semantics.resolver26.ResolverGeneratedTest -PresolverPropertyProfile=feature-interaction -PresolverPropertySeed=424242 -PresolverPropertyCase=2:2:1 -Pviaduct.resolution.threadcount=5
```

## Stress Runs

Run the recursive deep stress property with a fixed seed and optional case count:

```shell
RESOLVER26_STRESS_CASES=100000 ../gradlew -p . :semantics:resolver26Stress -Presolver26StressSeed=424242 -Pviaduct.resolution.threadcount=5
```

Resolver26 deep stress enables root-field references and fails unless it both generates and invokes at least one, so the usual `resolver26Stress` command cannot pass after exercising only the older feature set. Run the focused 250-case product when the root-reference interactions themselves are the subject:

```shell
../gradlew -p . :semantics:resolver26RootFieldReferenceFocused

# Optional seed override
../gradlew -p . :semantics:resolver26RootFieldReferenceFocused -Presolver26RootFieldReferenceFocusedSeed=2026091001
```

The focused task hard-requires observed namespace depths two, three, and four; zero-, one-, and four-argument targets; scalar, enum, concrete-object, interface, and union targets; list-element references; a three-hop reference tail; active fallback; registered-resolver override; extension resolver applications below published referenced results; and target Query-fragment applications using `FromArgument` and `FromQueryField`. Every ordinary Resolver26 broad profile also enables the fixed family and requires generated and activated references, so persisted broad campaigns retain a second mandatory coverage path.

Run one unfiltered broad product by choosing a directed profile, seed, and `S:R:Q` dimensions:

```shell
../gradlew -p . :semantics:resolver26BroadStress -Presolver26BroadStressProfile=multiple-owners -Presolver26BroadStressSeed=424242 -Presolver26BroadStressSize=20:10:50 -Pviaduct.resolution.threadcount=5
```

Every Resolver26 broad profile includes a forced great-grandparent path: its deepest resolver input selects `parent.parent.parent`, queries activate that resolver, and generated variables are never inserted directly beneath a parent selection. Generated resolver value plans also retain `@parent` fields, and the parent-enabled harness requires evidence that at least one resolver output supplies one. The dedicated parent-focused stress generates a `40:5:5` product and reports it as four consecutive 250-case, 10-schema slices. It supplements the fixed spine with independently shaped parent chains and records parent fields actually present in materialized resolver inputs, separating fixed-spine and random activations and reporting a consecutive parent-depth histogram. Its coverage analyzer attributes selected resolvers to every enclosing materialized parent selection set; reports exact variable-bearing argument selections in those resolvers' object and Query inputs by depth, fragment, and `FromArgument`/`FromObjectField`/`FromQueryField` source combination; and measures diagonal demand when a resolver selected beneath one parent independently starts another top-level parent chain. Exact registered-occurrence accounting also identifies source-supplied active fields whose skipped standard resolver has parent input demand, records their maximum parent depths, and hard-requires at least one such speculative-demand occurrence. Each slice prints an unambiguous `HIT` or `MISS` for nine criteria, and the combined report summarizes both how many slices completely hit each criterion and how many generated cases contributed any evidence, including per-slice instance counts: parent topology, resolver placement, variable sources, mixed source pairs, input locations, argument-selection depths, diagonal depths, variable-source/input-fragment combinations on diagonals, and sometimes-passive parent demand. Individual-slice misses remain diagnostic, but a miss in the combined four-slice coverage fails the test; resolution, binding, occurrence-accounting, the combined sometimes-passive-parent activation requirement, and forbidden direct-variable invariants remain independent assertions. `ParentQueryFragmentVariableResolverContract` deterministically covers Query-fragment variable use on diagonal parent demand for all three binding sources, independent of whether a random run reports a hit. Run the randomized profile with:

```shell
../gradlew -p . :semantics:resolver26ParentFocused

# Optional seed override
../gradlew -p . :semantics:resolver26ParentFocused -Presolver26ParentFocusedSeed=2026090403
```

Run one persisted five-profile campaign round:

```shell
env 'viaduct.resolution.threadcount=5' ./run-property-test-campaign.sh \
  classpath:/semantics/property-tests/campaigns/resolver26-broad-campaign-v1.json \
  81
```

Run the dispatcher-instrumented campaign with selected rounds and either each round's recorded dimensions or one overriding size:

```shell
../gradlew -p . :semantics:resolver26MultithreadedStress -Presolver26MultithreadedStressRounds=1,46,81,95 -Presolver26MultithreadedStressSize=campaign -Pviaduct.resolution.threadcount=10
```

With no overrides, the dedicated task runs round 1 at its recorded campaign dimensions: five profiles of 2,000 cases, for 10,000 cases total, on 100 threads:

```shell
../gradlew -p . :semantics:resolver26MultithreadedStress
```

The dedicated multithreaded task records continuation overlap and thread names. Its assertions are useful scheduling evidence, but external OS observation is the stronger check that those threads actually execute on multiple CPUs.

## Runtime Field-Checker Validation

`FieldCheckerGeneratedTest` runs success, denial, mixed, passive, and root-reference profiles through checker-aware correctness replay and independent duplicate-preserving invocation accounting. The broad profiles require activated `FromArgument`, `FromObjectField`, `FromQueryField`, `FromProvider`, and symbolic-key coverage alongside the shared F4 signatures. `SymbolicFieldCheckerTest`, `FieldCheckerLifecycleTest`, and the shared field-check contracts cover inclusion, local aliases, provider failures, raw-to-checked transitions, cycles, independent Query scopes, and cancellation deterministically.

The runtime checker distributions use `ResolverFragmentDepth=1`: two independently bound named pairs can multiply symbolic dependency trees, so copying depth-two fragments at every registered coordinate produced a case with over 129,000 resolver invocations before the 15-second request bound. This limit bounds random workload construction without changing request timeouts, per-case oracles, or required activation signatures. Fixed parent spines still exercise multilevel parent demand, and deterministic contracts retain nested fragment and named-provider combinations. The grounded Resolver23 distributions retain depth two.

Run the 2,500-case checker workload with a recorded seed; select 100 resolution threads for concurrent accounting:

```shell
../gradlew -p . :semantics:resolver26FieldCheckerStress -Presolver26FieldCheckerStressSeed=424242
../gradlew -p . :semantics:resolver26FieldCheckerStress -Presolver26FieldCheckerStressSeed=424242 -Presolver26FieldCheckerStressProfile=denial -Pviaduct.resolution.threadcount=100
```

Profiles accept `success`, `denial`, `mixed`, `passive`, or `root-reference`; `resolver26FieldCheckerStressSize` overrides the default `50:5:10` product. Replay failures through `resolverPropertyReplay` with class `semantics.resolver26.FieldCheckerGeneratedTest`, the reported profile and seed, the original `resolverPropertySize`, and the selected `resolverPropertyCase=S:R:Q`. The original size is essential because changing registry/query counts changes random-number consumption before the selected coordinate.

## CPU Parallelism Probe

Use a sufficiently deep run and at least two Resolver26 threads; very small cases can finish before sampling or offer too little runnable work. Run Gradle in the background, wait for its test worker, and sample that JVM from a second shell:

```shell
mkdir -p build/reports/resolver26-cpu-probe
../gradlew -p . :semantics:resolver26MultithreadedStress -Presolver26MultithreadedStressRounds=81 -Presolver26MultithreadedStressSize=20:10:10 -Pviaduct.resolution.threadcount=10 --rerun-tasks --console=plain >build/reports/resolver26-cpu-probe/run.log 2>&1 &
gradle_pid=$!
while ! worker_pid=$(jps -lv | awk '/GradleWorkerMain/ { print $1; exit }') || [[ -z $worker_pid ]]; do sleep 1; done
pidstat -t -p "$worker_pid" 1 8 | tee build/reports/resolver26-cpu-probe/pidstat.log
wait "$gradle_pid"
```

Reasonable evidence consists of the Gradle worker process exceeding `100%` CPU while multiple rows from one `resolver26-N-*` pool report nonzero CPU in the same samples. Pool identifier `N` is discovered from the thread names and is not the configured thread count. Process CPU over `100%` indicates use of more than one core; the named thread rows distinguish Resolver26 work from JIT, GC, and Gradle activity.

If `pidstat` is unavailable, use `top -H -p "$worker_pid"` for live per-thread CPU or `ps -L -p "$worker_pid" -o pid,tid,pcpu,comm` for repeated snapshots. This is evidence rather than a proof: OS accounting is sampled, thread names may be truncated, and brief runs can evade observation.

Avoid selecting an unrelated Gradle worker when other builds are active. Stop other builds, inspect `jps -lv`, or correlate the worker's start time and command with the run being probed.

## Canonical Million-Case Campaign

When a request says to run the Resolver26 one-million-query test, it means the complete checked-in `resolver26-broad-campaign-v1` campaign at one Resolver26 thread. From the `qplan` directory, run exactly:

```shell
env 'viaduct.resolution.threadcount=1' ./run-property-test-campaign.sh \
  classpath:/semantics/property-tests/campaigns/resolver26-broad-campaign-v1.json
```

The versioned campaign fixes all corpus inputs: rounds 1 through 100, five directed profiles per round, 2,000 cases per profile, each run's `S:R:Q` dimensions, and every seed. The result is exactly 10,000 cases per round and 1,000,000 cases total. The driver performs one incremental Gradle launcher install, lets Gradle exit, and then starts one fresh launcher JVM for each round. Do not add `clean`, regenerate resources, choose rounds, change the thread count, or otherwise alter this recipe unless the request explicitly asks for a different experiment.

Success means that the command exits zero after printing `Completed 100 round(s)`, every round reports `runs=5, completedCases=10000`, and `build/reports/resolver26-broad-campaign-v1` contains logs for all 100 rounds. Each run checks attempted, resolved, and completed accounting, resolution correctness, exact resolver-application identities, from-field bindings, and its required structural coverage. The driver stops at the first failed run or round and prints its replay command.

The driver's final wall-clock total covers the 100 launcher JVMs but excludes the initial Gradle install. To measure the complete command, including that one incremental install, use:

```shell
/usr/bin/time -p env 'viaduct.resolution.threadcount=1' \
  ./run-property-test-campaign.sh \
  classpath:/semantics/property-tests/campaigns/resolver26-broad-campaign-v1.json
```

## Canonical Performance Sample

When a request says to run the Resolver26 100,000-case or ten-round performance sample, use this fixed phase-weighted subset:

```shell
env 'viaduct.resolution.threadcount=1' ./run-property-test-campaign.sh \
  classpath:/semantics/property-tests/campaigns/resolver26-broad-campaign-v1.json \
  1 20 21 33 45 46 63 80 90 98
```

These ten persisted rounds contain exactly 100,000 cases and sample schema breadth, registry diversity, query interactions, and both large/deep variants in approximately their full-campaign proportions. This is a performance proxy, not a substitute for the canonical million-case correctness campaign. As above, the driver's total excludes the one incremental Gradle install; wrap the command with `/usr/bin/time -p env` when the measurement should include it.

## Designing Large Campaigns

A 100,000- to 1,000,000-case run should explore a broad state space rather than repeat one distribution. Split the budget across fresh JVM rounds, independent seeds, directed profiles, and different `S:R:Q` shapes; persist each round's command, seed, profile, dimensions, thread count, and log.

The checked-in million-case campaign varies schema breadth, registry diversity, query interaction count, and large/deep worlds. Its directed profiles emphasize balanced worlds, descendant variable uses, nullable and error providers, symbolic-key identity, and multiple from-field-variable owners. Keep all of those axes represented in future campaigns.

Favor cases that activate combinations of features, not registries that merely contain them. Important combinations include `FromObjectField` and `FromQueryField` with `FromArgument`, mixed object/Query provider chains, nested provider paths, passive and resolver-bearing descendants, lists containing symbolic resolver keys, node lowering and node arrays, many field resolvers with complex object and Query fragments, nullable or error intermediates, distinct symbolic expressions whose bindings resolve to equal values, multiple variable owners and owner dependencies, aliases, duplicate selections, deep selection sets, and high resolver density.

Bound list fanout and other multiplicative dimensions so large worlds do not collapse into a few resource explosions, but do not make the corpus shallow. Preserve registry diversity during query-heavy phases; many queries against one simple registry are not a substitute for varied resolver graphs.

Use low and high thread counts across the campaign. One thread preserves a deterministic baseline, two to ten threads exercise common interleavings, and a larger pool supplies additional scheduling pressure. The thread count changes scheduling, not the semantic corpus, so exact seeds and coordinates remain replayable at any count.

Audit both generated features and activated behavior. Track attempted and completed cases, resolver applications, variable-owner applications, provider-path depth, selection depth, list occurrences, equal visible symbolic arguments, and required structural signatures. A green run that never activates its target interaction is not evidence for that interaction.

When a case fails, first replay its exact profile, seed, coordinate, and thread count. Then replay at one and several thread counts, classify the failure as resolver, generator, oracle, campaign, or resource-envelope behavior, and reduce a real Resolver26 defect to a deterministic regression before changing the implementation.

## Improving The Corpus

Future million-case collections should spend cases according to information gained. Useful extensions include novelty-guided retention of rare structural fingerprints, pairwise or higher-order feature-interaction matrices, extra budget for rare activated signatures, and suppression of semantically duplicate generated cases.

Metamorphic variants can preserve a world while permuting selections, aliases, duplicate occurrences, and equivalent query structure. Keep extensional result, exact application witness, binding, and metamorphic oracles independent so agreement is not manufactured by shared implementation assumptions.

Record generated and activated feature vectors separately, then retain seeds that reach rare intersections or unusually deep paths. Stratify budgets over breadth, depth, registry count, query count, resolver density, and list fanout rather than maximizing one scalar size.

Scheduling perturbations such as deliberate yields may eventually expose additional races, but add them only with a reproducible seed and a reliable coordinate replay. A corpus whose failures cannot be localized is less useful than a slightly smaller one with exact forensic evidence.

## Open Testing Gaps

This appendix records known weaknesses in Resolver26's test infrastructure. They are not established Resolver26 implementation defects, but they limit what the current green suites prove and should be addressed before treating a large campaign as strong concurrency or interaction evidence.

### Restore Witness Coverage In Multithreaded Stress

- [ ] Run multithreaded stress with resolution-witness capture, plus a separate pass with count-only capture because those modes are intentionally mutually exclusive. Both recorders are already thread-safe, but `runResolver26MultithreadedStress` currently disables both, so the instrumented campaign checks only extensional correctness and from-field bindings.
- [ ] Audit and replace unsynchronized mutable application counters and lists throughout deterministic resolver contracts, including `EmptyObjectFragmentResolverContract.kt`, `ObjectFragmentResolverContract.kt`, `VariableSelectionIdentityResolverContract.kt`, `ObjectFragmentFromArgumentResolverContract.kt`, and `NodeResolverContract.kt`; multiple Resolver26 threads may invoke fixture resolvers concurrently, and assertions that depend on append order or ordinary integer increments are harness races rather than valid resolver checks.
- [ ] Add a focused concurrency regression for the fixture instrumentation itself, then rerun representative deterministic contracts at several thread counts to prove that recorded counts and observations are stable without imposing execution order.

### Make Structural Coverage Interaction-Local

This interaction-local accounting work is deliberately deferred to a follow-up PR. Until that lands, reviews should cite it as known accepted backlog rather than a newly discovered Resolver26 weakness.

- [ ] Make each structural signature application-local where its name claims an interaction; for example, `MIXED_BINDING_SOURCES` in `Resolver26StructuralCoverage.kt` can currently combine `FromArgument` and `FromObjectField` evidence from unrelated applications in one case.
- [ ] Replace the corpus-wide union in `ResolverBroadStressTest.kt` with per-case interaction records or explicit activation counts, so required signatures cannot be satisfied by unrelated cases distributed across the generated product.
- [ ] Give each directed profile an activation predicate tied to the exact resolver application, occurrence path, binding source, and result structure that constitute the intended interaction; retain aggregate signature counts only as diagnostics.

### Preserve Occurrence Identity In The Exact-Application Oracle

- [ ] Reconstruct expected demanded occurrences independently of the completed Resolver26 result. `registeredResolverApplicationIdentityCounts` currently discovers expected applications from resolver-bearing cells already present in that result, so an extra valid cell accompanied by an extra matching invocation can increase both expected and actual counts together and pass.
- [ ] Restore an independent supplied-demand oracle or replace the disabled `ResolverWitnessContract.generated supplied demand matches independently reconstructed successor demand` check with a narrower reconstruction that handles list-transparent continuation paths.

### Recently Closed Generator Reachability Gaps

- [x] Resolver26 profiles opt into list-target variables that admit scalar and shallower-list providers through singleton coercion across nested input-list layers, with directed generator and grounding evidence; earlier resolver profiles remain gated off.
- [x] Generated fields admit multiple arguments through the configurable `FieldArgumentCount` range.
- [x] Variable input plans retain their targets, and generation deliberately reuses one variable across multiple selections in the same fragment as well as across object and Query fragments.
- [x] `FromObjectField` variables generate literal/symbolic convergence and report it independently from `FromArgument` convergence.
- [x] Resolver26's default deep-stress configuration enables from-field variables and requires both generated and activated evidence.
- [x] Broad structural coverage records `ABSTRACT_PROVIDER_PATH` only from an activated owner and requires it in the balanced Resolver26 profile.
- [x] Resolver26 stress profiles generate and activate a variable-free great-grandparent `@parent` demand spine; a dedicated 1,000-case parent-focused run divides its 40 schemas into four reported 250-case slices, supplements the spine with randomized parent chains, records actual materialized-input activations by topology and consecutive parent depth, classifies variable-bearing resolver inputs beneath parents by exact argument occurrence and binding source, reports ordinary and recursively composed diagonal parent demand, and identifies exact source-supplied occurrences whose skipped standard resolver has parent demand against nine explicit combined-required coverage criteria.
