# Testing Resolution

## Purpose

Production Resolution is tested through deterministic feature and lifecycle contracts, generated worlds, independent correctness replay, exact resolver and checker application witnesses, variable-binding validation, mutation tests, and directed stress campaigns. This document defines the production-specific concurrency boundary, commands, campaign expectations, and limits of the resulting evidence. The shared evidence layers and oracle boundaries are defined in [Testing Strategy](strategy.md).

## Thread Count

Model-level Resolution fixtures use one externally configurable resolution thread count, including static contracts, generated properties, coordinate replays, deep stress, broad stress, and multithreaded campaigns. These fixtures default to one; the dedicated `resolutionMultithreadedStress` task defaults to 100. Focused `ExecutionTestFixture` tests use the same dispatcher factory unless they supply their own coroutine context. Service-backed `Engine2` tests and copied feature tests execute on `Dispatchers.Default`; this thread-count property does not control them.

The Gradle property, JVM property, and environment entry all use the durable configuration name `viaduct.resolution.threadcount`; the Gradle property is preferred in commands in this guide. The value must be a positive integer.

Direct-launcher campaign commands do not run their rounds under Gradle, so pass `viaduct.resolution.threadcount` through `env` for those commands.

The setting controls the fixed dispatcher inherited by all Resolution coroutines within a request. It does not make separate generated cases concurrent: cases are generated, resolved, and validated one at a time so a failure retains an exact seed and `S:R:Q` coordinate. Configuration is read once at each owning setup boundary rather than for every resolution.

`ResolutionDispatcherFactory.create(threadCount)` creates a fresh caller-owned fixed dispatcher and retains no pools. Ordinary JUnit tests reuse one configured dispatcher per concrete test class; each standalone property-test round and corpus tool owns one dispatcher for its complete computation; each JMH trial owns one dispatcher outside measured work; and the execution fixture owns its default dispatcher while an execution strategy borrows its embedding service's supplied context. Every owner closes its dispatcher after its lifetime. Threads are daemon-backed and named `resolution-N-M`, where `N` is a process-local pool identifier and `M` is a thread identifier local to that pool. Neither number is the configured thread count. The names group thread-dump entries by pool and support profiler filtering and per-thread CPU sampling; they are diagnostic labels rather than observations or measurements.

## Concurrency Boundary

Everything invoked while a resolution is running must tolerate concurrent resolver applications. The application witness recorder uses a short synchronized append after constructing each immutable record, application counts and mutation-fixture caches use concurrent maps, and application ordinals use atomics.

Post-resolution validation is intentionally single-threaded. After `resolve` returns, the calling test coroutine snapshots instrumentation and serially evaluates application identities, structural coverage, `correctResolution`, from-field bindings, and any metamorphic comparison. Do not add synchronization to these pure snapshot consumers merely because resolution itself is concurrent.

Keep this division strict when adding instrumentation: capture concurrent events safely and cheaply during resolution, freeze or snapshot them after request quiescence, and perform expensive oracle work serially from the immutable snapshot. Never let a test-only recorder impose a scheduling dependency on Resolution.

## Ordinary Runs

Run all non-stress model-level Resolution tests with the default single-thread dispatcher:

```shell
./gradlew :core:engine:runtime2:test --tests 'viaduct.engine.runtime2.resolution.*'
```

Run the same static, generated, witness, and mutation suite with five resolution threads:

```shell
./gradlew :core:engine:runtime2:test --tests 'viaduct.engine.runtime2.resolution.*' -Pviaduct.resolution.threadcount=5
```

Run one class or test method by using the normal Gradle test filter and the same thread-count property:

```shell
./gradlew :core:engine:runtime2:test --tests 'viaduct.engine.runtime2.resolution.SymbolicKeyIdentityTest' -Pviaduct.resolution.threadcount=2
```

Run the generated Resolution contracts under a fixed property seed:

```shell
./gradlew :core:engine:runtime2:test --tests 'viaduct.engine.runtime2.resolution.ResolverGeneratedTest' -PresolverPropertySeed=424242 -Pviaduct.resolution.threadcount=5
```

Replay one exact generated coordinate with the same concurrency:

```shell
./gradlew :core:engine:runtime2:resolverPropertyReplay -PresolverPropertyClass=viaduct.engine.runtime2.resolution.ResolverGeneratedTest -PresolverPropertyProfile=feature-interaction -PresolverPropertySeed=424242 -PresolverPropertyCase=2:2:1 -Pviaduct.resolution.threadcount=5
```

## Stress Runs

Run the recursive deep stress property with a fixed seed and optional case count:

```shell
RESOLUTION_STRESS_CASES=100000 ./gradlew :core:engine:runtime2:resolutionStress -PresolutionStressSeed=424242 -Pviaduct.resolution.threadcount=5
```

Resolution deep stress enables root-field references and fails unless it both generates and invokes at least one, so the usual `resolutionStress` command cannot pass after exercising only the older feature set. Run the focused 250-case product when the root-reference interactions themselves are the subject:

```shell
./gradlew :core:engine:runtime2:resolutionRootFieldReferenceFocused

# Optional seed override
./gradlew :core:engine:runtime2:resolutionRootFieldReferenceFocused -PresolutionRootFieldReferenceFocusedSeed=2026091001
```

The focused task hard-requires observed namespace depths two, three, and four; zero-, one-, and four-argument targets; scalar, enum, concrete-object, interface, and union targets; list-element references; a three-hop reference tail; active fallback; registered-resolver override; extension resolver applications below published referenced results; and target Query-fragment applications using `FromArgument` and `FromQueryField`. Every ordinary Resolution broad profile also enables the fixed family and requires generated and activated references, so persisted broad campaigns retain a second mandatory coverage path.

Run one unfiltered broad product by choosing a directed profile, seed, and `S:R:Q` dimensions:

```shell
./gradlew :core:engine:runtime2:resolutionBroadStress -PresolutionBroadStressProfile=multiple-owners -PresolutionBroadStressSeed=424242 -PresolutionBroadStressSize=20:10:50 -Pviaduct.resolution.threadcount=5
```

Every Resolution broad profile includes a forced great-grandparent path: its deepest resolver input selects `parent.parent.parent`, queries activate that resolver, and generated variables are never inserted directly beneath a parent selection. Generated resolver value plans also retain `@parent` fields, and the parent-enabled harness requires evidence that at least one resolver output supplies one. The dedicated parent-focused stress generates a `40:5:5` product and reports it as four consecutive 250-case, 10-schema slices. It supplements the fixed spine with independently shaped parent chains and records parent fields actually present in materialized resolver inputs, separating fixed-spine and random activations and reporting a consecutive parent-depth histogram. Its coverage analyzer attributes selected resolvers to every enclosing materialized parent selection set; reports exact variable-bearing argument selections in those resolvers' object and Query inputs by depth, fragment, and `FromArgument`/`FromObjectField`/`FromQueryField` source combination; and measures diagonal demand when a resolver selected beneath one parent independently starts another top-level parent chain. Exact registered-occurrence accounting also identifies source-supplied active fields whose skipped standard resolver has parent input demand, records their maximum parent depths, and hard-requires at least one such speculative-demand occurrence. Each slice prints an unambiguous `HIT` or `MISS` for nine criteria, and the combined report summarizes both how many slices completely hit each criterion and how many generated cases contributed any evidence, including per-slice instance counts: parent topology, resolver placement, variable sources, mixed source pairs, input locations, argument-selection depths, diagonal depths, variable-source/input-fragment combinations on diagonals, and sometimes-passive parent demand. Individual-slice misses remain diagnostic, but a miss in the combined four-slice coverage fails the test; resolution, binding, occurrence-accounting, the combined sometimes-passive-parent activation requirement, and forbidden direct-variable invariants remain independent assertions. `ParentQueryFragmentVariableResolverContract` deterministically covers Query-fragment variable use on diagonal parent demand for all three binding sources, independent of whether a random run reports a hit. Run the randomized profile with:

```shell
./gradlew :core:engine:runtime2:resolutionParentFocused

# Optional seed override
./gradlew :core:engine:runtime2:resolutionParentFocused -PresolutionParentFocusedSeed=2026090403
```

Run one persisted five-profile campaign round:

```shell
env 'viaduct.resolution.threadcount=5' core/engine/runtime2/run-property-test-campaign.sh \
  classpath:/viaduct/engine/runtime2/property-tests/campaigns/resolution-broad-campaign-v1.json \
  81
```

Run the dispatcher-instrumented campaign with selected rounds and either each round's recorded dimensions or one overriding size:

```shell
./gradlew :core:engine:runtime2:resolutionMultithreadedStress -PresolutionMultithreadedStressRounds=1,46,81,95 -PresolutionMultithreadedStressSize=campaign -Pviaduct.resolution.threadcount=10
```

With no overrides, the dedicated task runs round 1 at its recorded campaign dimensions (five checker-free profiles of 2,000 cases) plus the success, denial, and mixed type-checker profiles (2,500 coordinates each, with both query permutations), all on 100 threads. The checker profiles use seed `2026093001` by default; `resolutionTypeCheckerStressSeed` and `resolutionTypeCheckerStressSize` override their seed and dimensions independently of the broad campaign.

```shell
./gradlew :core:engine:runtime2:resolutionMultithreadedStress
```

The dedicated multithreaded task records continuation overlap and thread names for the broad campaign and separately for each checker profile. Every checker profile retains independent correctness replay, exact checker accounting, and all activation guards; an invocation-free or provider-free run cannot pass. Its assertions are useful scheduling evidence, but external OS observation is the stronger check that those threads actually execute on multiple CPUs.

## Runtime Field-Checker Validation

`FieldCheckerGeneratedTest` runs success, denial, mixed, passive, and root-reference profiles through checker-aware correctness replay and independent duplicate-preserving invocation accounting. The broad profiles require activated `FromArgument`, `FromObjectField`, `FromQueryField`, `FromProvider`, and symbolic-key coverage alongside the shared F4 signatures. `SymbolicFieldCheckerTest`, `FieldCheckerLifecycleTest`, and the shared field-check contracts cover inclusion, local aliases, provider failures, raw-to-checked transitions, cycles, independent Query scopes, and cancellation deterministically.

The runtime checker distributions use `ResolverFragmentDepth=1`: two independently bound named pairs can multiply symbolic dependency trees, so copying depth-two fragments at every registered coordinate produced a case with over 129,000 resolver invocations before the 15-second request bound. This limit bounds random workload construction without changing request timeouts, per-case oracles, or required activation signatures. Fixed parent spines still exercise multilevel parent demand, and deterministic contracts retain nested fragment and named-provider combinations. The grounded Resolver23 distributions retain depth two.

Run the 2,500-case checker workload with a recorded seed; select 100 resolution threads for concurrent accounting:

```shell
./gradlew :core:engine:runtime2:resolutionFieldCheckerStress -PresolutionFieldCheckerStressSeed=424242
./gradlew :core:engine:runtime2:resolutionFieldCheckerStress -PresolutionFieldCheckerStressSeed=424242 -PresolutionFieldCheckerStressProfile=denial -Pviaduct.resolution.threadcount=100
```

Profiles accept `success`, `denial`, `mixed`, `passive`, or `root-reference`; `resolutionFieldCheckerStressSize` overrides the default `50:5:10` product. Replay failures through `resolverPropertyReplay` with class `viaduct.engine.runtime2.resolution.FieldCheckerGeneratedTest`, the reported profile and seed, the original `resolverPropertySize`, and the selected `resolverPropertyCase=S:R:Q`. The original size is essential because changing registry/query counts changes random-number consumption before the selected coordinate.

## Runtime Type-Checker Campaigns

`TypeCheckerGeneratedTest` runs `resolution-type-checker-success`, `resolution-type-checker-denial`, and `resolution-type-checker-mixed`. Each coordinate executes a randomized schema/registry/query world with mixed runtime field checks and type-owned variables sourced from object paths, Query paths, and callback providers. Type checkers reuse eligible sampled fragment/provider plans with their own target identity; a conservative transitive type order avoids type/value wait cycles. The profiles retain the normal argument-error generation weight and require error-valued arguments in type-checker inputs, actual consumption of completed path bindings from both provider roots and callback-provider bindings on each input root, and variable use in both input fragments alongside the existing type-checker activation guards. Both query permutations pass independent correctness replay and duplicate-preserving checker application accounting. The ordinary 150-case profiles record nested provider-path activation without requiring a random hit; `GeneratedTypeCheckerVariableCoverageTest` requires a fixed executed two-segment path and rejects registration-only and missing-binding evidence, while the size-overridden 2,500-case stress profiles additionally require randomized nested-path activation. `RuntimeTypeCheckerWitnessTest` separately retains the small deterministic named-provider and conditional-exclusion regression; it is no longer repeated at every stress coordinate.

Run all three profiles at 2,500 coordinates each, or just the checker portion of the instrumented 100-thread task:

```shell
./gradlew :core:engine:runtime2:resolutionTypeCheckerStress -PresolutionTypeCheckerStressSeed=424242
./gradlew :core:engine:runtime2:resolutionMultithreadedStress --tests '*generated * type checker worlds resolve correctly' -PresolutionTypeCheckerStressSeed=2026093001
```

`resolutionTypeCheckerStressProfile` accepts `all` (the default), `success`, `denial`, or `mixed`; `resolutionTypeCheckerStressSize` defaults to `50:5:10`. Replay a random case at its original size and coordinate:

```shell
./gradlew :core:engine:runtime2:resolverPropertyReplay -PresolverPropertyClass=viaduct.engine.runtime2.resolution.TypeCheckerGeneratedTest -PresolverPropertyProfile=resolution-type-checker-mixed -PresolverPropertySeed=424242 -PresolverPropertySize=50:5:10 -PresolverPropertyCase=1:1:1
```

`SymbolicTypeCheckerTest`, `TypeCheckerLifecycleTest`, and the explicitly composed shared contracts are the deterministic runtime gates. The existing field-checker stress task remains a separate regression gate. Infinite callback suspension is tested with a short external bound and explicit cancellation; declarative cycles must fail through cycle detection, independently of the finite generated workload bounds.

## CPU Parallelism Probe

Use a sufficiently deep run and at least two Resolution threads; very small cases can finish before sampling or offer too little runnable work. Run Gradle in the background, wait for its test worker, and sample that JVM from a second shell:

```shell
mkdir -p core/engine/runtime2/build/reports/resolution-cpu-probe
./gradlew :core:engine:runtime2:resolutionMultithreadedStress -PresolutionMultithreadedStressRounds=81 -PresolutionMultithreadedStressSize=20:10:10 -Pviaduct.resolution.threadcount=10 --rerun-tasks --console=plain >core/engine/runtime2/build/reports/resolution-cpu-probe/run.log 2>&1 &
gradle_pid=$!
while ! worker_pid=$(jps -lv | awk '/GradleWorkerMain/ { print $1; exit }') || [[ -z $worker_pid ]]; do sleep 1; done
pidstat -t -p "$worker_pid" 1 8 | tee core/engine/runtime2/build/reports/resolution-cpu-probe/pidstat.log
wait "$gradle_pid"
```

Reasonable evidence consists of the Gradle worker process exceeding `100%` CPU while multiple rows from one `resolution-N-*` pool report nonzero CPU in the same samples. Pool identifier `N` is discovered from the thread names and is not the configured thread count. Process CPU over `100%` indicates use of more than one core; the named thread rows distinguish Resolution work from JIT, GC, and Gradle activity.

If `pidstat` is unavailable, use `top -H -p "$worker_pid"` for live per-thread CPU or `ps -L -p "$worker_pid" -o pid,tid,pcpu,comm` for repeated snapshots. This is evidence rather than a proof: OS accounting is sampled, thread names may be truncated, and brief runs can evade observation.

Avoid selecting an unrelated Gradle worker when other builds are active. Stop other builds, inspect `jps -lv`, or correlate the worker's start time and command with the run being probed.

## Canonical Million-Case Campaign

When a request says to run the Resolution one-million-query test, it means the complete checked-in `resolution-broad-campaign-v1` campaign at one Resolution thread. From the repository root, run exactly:

```shell
env 'viaduct.resolution.threadcount=1' core/engine/runtime2/run-property-test-campaign.sh \
  classpath:/viaduct/engine/runtime2/property-tests/campaigns/resolution-broad-campaign-v1.json
```

The versioned campaign fixes all corpus inputs: rounds 1 through 100, five directed profiles per round, 2,000 cases per profile, each run's `S:R:Q` dimensions, and every seed. The result is exactly 10,000 cases per round and 1,000,000 cases total. The driver performs one incremental Gradle launcher install, lets Gradle exit, and then starts one fresh launcher JVM for each round. Do not add `clean`, regenerate resources, choose rounds, change the thread count, or otherwise alter this recipe unless the request explicitly asks for a different experiment.

Success means that the command exits zero after printing `Completed 100 round(s)`, every round reports `runs=5, completedCases=10000`, and `core/engine/runtime2/build/reports/resolution-broad-campaign-v1` contains logs for all 100 rounds. Each run checks attempted, resolved, and completed accounting, resolution correctness, exact resolver-application identities, from-field bindings, and its required structural coverage. The driver stops at the first failed run or round and prints its replay command.

The driver's final wall-clock total covers the 100 launcher JVMs but excludes the initial Gradle install. To measure the complete command, including that one incremental install, use:

```shell
/usr/bin/time -p env 'viaduct.resolution.threadcount=1' \
  core/engine/runtime2/run-property-test-campaign.sh \
  classpath:/viaduct/engine/runtime2/property-tests/campaigns/resolution-broad-campaign-v1.json
```

## Canonical Performance Sample

When a request says to run the Resolution 100,000-case or ten-round performance sample, use this fixed phase-weighted subset:

```shell
env 'viaduct.resolution.threadcount=1' core/engine/runtime2/run-property-test-campaign.sh \
  classpath:/viaduct/engine/runtime2/property-tests/campaigns/resolution-broad-campaign-v1.json \
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

When a case fails, first replay its exact profile, seed, coordinate, and thread count. Then replay at one and several thread counts, classify the failure as resolver, generator, oracle, campaign, or resource-envelope behavior, and reduce a real Resolution defect to a deterministic regression before changing the implementation.

## Improving The Corpus

Future million-case collections should spend cases according to information gained. Useful extensions include novelty-guided retention of rare structural fingerprints, pairwise or higher-order feature-interaction matrices, extra budget for rare activated signatures, and suppression of semantically duplicate generated cases.

Metamorphic variants can preserve a world while permuting selections, aliases, duplicate occurrences, and equivalent query structure. Keep extensional result, exact application witness, binding, and metamorphic oracles independent so agreement is not manufactured by shared implementation assumptions.

Record generated and activated feature vectors separately, then retain seeds that reach rare intersections or unusually deep paths. Stratify budgets over breadth, depth, registry count, query count, resolver density, and list fanout rather than maximizing one scalar size.

Scheduling perturbations such as deliberate yields may eventually expose additional races, but add them only with a reproducible seed and a reliable coordinate replay. A corpus whose failures cannot be localized is less useful than a slightly smaller one with exact forensic evidence.

## Limits Of Current Evidence

These limits describe what the alpha suite does not yet prove. They are not established Resolution defects.

### Multithreaded Witness Coverage

`runResolutionMultithreadedStress` currently disables both full resolution-witness capture and count-only capture, which are intentionally mutually exclusive modes. The instrumented multithreaded campaign therefore establishes extensional correctness, from-field bindings, continuation overlap, and worker-thread use, but not the same exact ordinary resolver-application witness used by the single-threaded broad profiles.

Some deterministic feature fixtures also retain ordinary mutable counters or lists. Those tests are reliable in their normal single-threaded contract configuration but cannot be treated as concurrency evidence until their recorders are made thread-safe without imposing event order and the instrumentation itself has focused concurrency coverage.

### Interaction-Local Structural Coverage

Some broad structural signatures are aggregated more coarsely than their names imply. For example, `MIXED_BINDING_SOURCES` can combine `FromArgument` and `FromObjectField` evidence from different applications in one case, and corpus-wide required-signature unions can be satisfied by different cases. Directed deterministic contracts remain the stronger evidence for the exact interaction. A broad profile proves that its component behaviors occurred in the corpus unless its activation predicate explicitly joins the resolver application, occurrence path, binding source, and result structure.

### Preserve Occurrence Identity In The Exact-Application Oracle

`registeredResolverApplicationIdentityCounts` reconstructs expected applications from resolver-bearing cells already present in the completed result. An extra valid cell accompanied by an extra matching invocation can therefore enlarge both expected and actual counts together. The oracle strongly detects missing, duplicate, and wrong-root/path applications for the occurrences it reconstructs, but it does not independently establish absolute minimality of the demanded occurrence set.

Supplied-demand witnesses cover selected contracts and profiles but are not a complete independent reconstruction across list-transparent continuation paths. Claims about minimal selective demand require a focused supplied-demand assertion in addition to completed-result correctness and exact observed applications.

## GraphQL Mutation And Nested Execution Tests

The focused mutation gate covers response-key collection, the full maintained-family ladder, engine/service integration, independent `ctx.query()` and `ctx.mutation()`, continued effects after nullable and non-null failures, response null propagation through namespaces and payloads, inactive payload dependencies, cancellation before task entry and during suspended mutations, and sequencing of suspended work. These are tests of GraphQL mutation execution; the deliberate corruption tests described in [Testing Strategy](strategy.md#correctness-oracles) serve a different purpose.

```shell
./gradlew :core:engine:runtime2:test --tests '*MutationSelectionParsingTest' --tests '*MutationObjectEngineResultTest' --tests '*MutationRegistryTest' --tests '*MutationResolutionTest' --tests '*MutationOrchestrationTaskTest' --tests '*MutationExecutionTest' --tests '*ResolverStartTest' --tests '*RequestScopeOwnershipTest'
```

`MutationResolutionTest` asserts effect traces and completed payload values independently of Query replay. Its delayed payload resolver checks that an active mutation's output finishes before the next mutation changes shared state. Mutation lifecycle tests use controlled scheduling to verify that later mutations remain undispatched while the preceding task is suspended and that cancellation terminates all synchronously prepared cells.

Run the service-backed nested execution and lifetime gate with:

```shell
./gradlew :core:engine:runtime2:test --tests 'viaduct.engine.runtime2.execution.viaductfeaturetests.SubqueryExecutionTest*' --tests 'viaduct.engine.runtime2.execution.viaductfeaturetests.SubquerySchemaTest' --tests 'viaduct.engine.runtime2.execution.Engine2RequestLifetimeTest'
```

The subquery suites exercise nested Query and Mutation results, serial effects within each mutation call, concurrent independent mutation calls, parallel Query namespace work from a mutation resolver, variable isolation, selective materialization, and scoped schemas with full-schema resolver inputs. `Engine2RequestLifetimeTest` checks public-future and suspending-call cancellation, nested execution cancellation, mutation completion waiting, and concurrent request isolation. `QPlanDeferTest` separately checks incremental publisher lifetime. The `ALTERNATIVE` coroutine cases use structured Kotlin coroutines because Runtime2 supplies explicit invocation-local execution capabilities rather than the thread-local context required by the old engine's `scopedAsync` helper.
