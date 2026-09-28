# Singular Query-Scope Resolution Handoff

## Purpose

This handoff is the reviewer entry point for the completed `single-query-oer` branch in `/home/raymie_stata/repos/2rv`. The branch replaces resolver-owned fresh Query OER expansion with singular, shared Query scopes. The motivating walkthrough and the exponential, linear-root, and singular alternatives are in [`examples.md`](./examples.md#fresh-query-oer-resolution-strategies); use that example as the compact behavioral specification rather than reproducing it here.

All implementation and validation commands recorded here ran from `/home/raymie_stata/repos/2rv/qplan`. The `1rv` worktree was used only as a read-only architecture reference where explicitly noted. Everst has no qplan information and was not used.

## Reviewer Navigation

Use [`examples.md`](./examples.md#fresh-query-oer-resolution-strategies) first for the old exponential behavior and the implemented singular policy. Then review the branch in commit order: shared preparation and demand closure; Resolver01–08 implementation, validation, and stress evidence; Resolver21–23 implementation and validation; and Resolver26 implementation, validation, and the final cross-family boundary coverage. The rewritten commit identities are recorded in the status section below.

The commit stack maps to the implementation sequence as follows:

| Commit range | Implementation sequence | Significance |
| --- | --- | --- |
| `fa0be4e4e^..fa0be4e4e` | Step 1 | Important preparation that should be understood on its own merits. |
| `fa0be4e4e..c751e7909` | Steps 2–4 | Resolver01–08 were changed together because these steps could not be cleanly separated. These three central commits contain the bulk of the work. |
| `0f824f8ef^..0f824f8ef` | Step 5 | Resolver21–23 implementation and validation. |
| `HEAD^..HEAD` | Step 6 | Resolver26, the production-shaped algorithm, and therefore a particularly important layer. |

Steps 7–8 do not have separate commits. Their cross-family finalization, documentation, and validation work is incorporated into the earlier commits.

The central implementation seams are the shared `SharedOERContext` and `ResolverInputConstructionDemand` carriers, the grounded and symbolic `ConstructionDemandClosure.kt` implementations, each family's orchestration and field-resolver-task pair, and owner-local Query materialization in each family's `FieldResolutionLogic`. The most direct acceptance specification is `QueryFragmentResolverContract`; `RootFieldReferenceResolverContract`, `ParentFieldResolverContract`, the cycle tests, correctness replay, occurrence witnesses, and the seeded stress suites cover the consequential boundaries around it.

Review especially that one orchestration owns distinct object and associated Query OERs; Query-side resolvers close both fragments back into the same Query scope; every owner retains its response-keyed projection; descendant object and list occurrences retain separate containing scopes; and `ctx.query()` and root-field-reference targets remain independently rooted. Resolver01–08 intentionally retain dependency-bound synchronous ordering, while Resolver21–26 retain promise-based asynchronous readiness. Checker Query-scope migration is explicitly outside this branch.

## Current Status And Remaining Sequence

This section is authoritative when older planning language below conflicts with the implementation. The committed branch completed Steps 1–4 in `fa0be4e4e`, `1befe7648`, `0e62483b5`, and `c751e7909`; `0f824f8ef` completes Step 5 for Resolver21–23, and branch `HEAD` completes Step 6 for Resolver26. The Step 7 cross-family boundary contracts are folded into `0e62483b5`, and the final Step 8 documentation and validation record are folded into `HEAD`.

- `OrchestrationConstructionDemand` represents object- and Query-rooted demand, each retaining checked and unchecked provenance. The grounded closure used by Resolver01–23 closes the pair in one fixed point, including transitive Query-side resolver inputs and parent lifting.
- `ResolverInputConstructionDemand` is the shared object-/Query-fragment pair contributed by newly expanded resolver occurrences; the grounded and symbolic closures retain different expansion bookkeeping around that common value.
- `SharedOERContext` carries an OER occurrence, passive source, and closed construction demand. Shared orchestration and publication state use non-null `objectOER` and `queryOER` contexts.
- Resolver02–03, Resolver07–08, Resolver22–23, and Resolver26 implement singular Query OERs. Resolver01, Resolver06, and Resolver21 remain empty-fragment compatibility baselines while sharing their families' paired-OER machinery.
- Every maintained orchestration optimistically allocates one real associated Query OER. If closure discovers no Query demand, that context has empty `closedValueSelections`, `isDemanded()` is false, and it is harmlessly frozen and discarded.
- Resolver21–23 jointly close object- and Query-rooted demand, prepare all ordinary publications on both OERs before their dispatch, materialize each ordinary owner's local projection from the associated Query OER, and freeze both OERs from one orchestration dispatch. Promise readiness and coroutine suspension remain their execution mechanism; they do not acquire the depth-first families' dependency ordering.
- Resolver26 now applies the same ownership rule to symbolic demand. Its paired closure retains occurrence-local inclusion alternatives and bindings, provider reads carry their absolute object- or Query-OER root, and ordinary owners materialize from the associated Query OER. Independently rooted reference-target execution remains separate.
- Resolver22/23 validate Query-rooted parent lifting through a directed ancestor/descendant interaction: lifted demand activates one ancestor resolver with its own Query fragment, every involved resolver runs once, and Query-side cells do not leak into the client result.
- Resolver23 uses the depth-27 expansion witness and completes with 28 resolver invocations rather than 832,039. Shared-production failure and cancellation tests cover multiple owners waiting on one producer.
- `queryOERDepth` retains the same meaning throughout Resolver01–08 and remains useful to the Resolver06–08 reactor. Resolver21–23 report their real Query OERs without inventing a depth metric their scheduler neither tracks nor uses.
- Root-field-reference targets remain independently rooted nested executions. Declared Query fragments reached within such an execution use the singular policy internally for every maintained resolver.
- Boundary contracts require every reference hop and sibling occurrence to retain a distinct invocation root, require nested reference-target execution to use singular sharing internally, and require returned object, list-element, and abstract concrete occurrences to own the correct separate containing scopes.
- Declarative direct and mutual Query-fragment recursion is rejected during registry construction. Runtime exact-cycle coverage remains in the depth-first sibling-ordering and coroutine cycle-checker contracts. Dynamically returned infinite root-reference tails retain ordinary tenant-code recursion semantics, like recursive `ctx.query()`, and have no reference-specific detector.
- Observation and correctness replay record each associated Query OER once, associate every nonempty owner with it, validate its exact unioned closed demand once, and validate owner projections separately.

Current validation evidence is:

- The final focused Resolver01–08 acceptance run passed 295 tests with two expected skips. Seeded 10,000-case Resolver03 and Resolver08 stress runs each generated 20,385 Query fragments, included 1,200 fragment-free cases, activated 60,406 fragment-bearing applications, included 7,473 cases with and 2,527 cases without demanded Query OERs, and observed demanded depths `{1=34179, 2=10118, 3=709, 4=9}`.
- The focused Resolver21–23 lifecycle, contract, and seed-1 generated acceptance run passed 207 tests with one expected skip and no failures. It includes Resolver21's undemanded/frozen context, Resolver22/23 parent integration, Resolver23's depth-27 witness, shared failure and cancellation, and all generated profiles for the three versions.
- `./gradlew :semantics:resolver23Stress -Presolver23StressSeed=424242` passed all 10,000 requested cases: 26,185 generated Query fragments, 760 fragment-free cases, 31,214 activated Query-fragment applications, 6,540 cases with demanded Query OERs, and 3,460 without. `demandedQueryOERDepthCounts` is intentionally empty because coroutine resolvers do not fabricate the depth-first scheduler metric.
- The full non-stress Resolver26 package passed 244 tests with one expected skip. It includes the shared Query-fragment contract and depth-27 expansion witness, parent-demand interaction, symbolic variables and providers, lifecycle/cancellation behavior, correctness replay, and occurrence witnesses.
- After aligning the grounded and symbolic closure APIs and sharing `ResolverInputConstructionDemand`, a focused run of both closure tests, the Resolver03/08/23 contracts, and the full Resolver26 package passed 444 tests with one expected skip.
- `./gradlew :semantics:resolver26Stress -Presolver26StressSeed=424242` passed all 10,000 requested cases in 4m 8s: 32,910 generated Query fragments, 445 fragment-free cases, 7,180 activated Query-fragment applications, 3,595 cases with demanded Query OERs, and 6,405 without. It also exercised 3,715 object-path and 7,180 Query-path variable applications, 145,661 root-field references, and 85,342 sometimes-passive occurrences.
- Singular sharing required the resolver-witness oracle to traverse each observed Query result once by identity: multiple owners may associate the same Query OER with distinct projections. The focused and stress results validate that behavior across all maintained execution families.
- The final boundary run passed all seven fragment-capable resolver contracts: 500 tests, three expected skips, and no failures. The focused registry suite also passed with direct and mutual Query-fragment recursion cases.
- `./gradlew check -PresolverPropertySeed=1` passed the exact final tree in 5m 7s: 134 tasks, 52 executed and 82 up-to-date. Its regular suites reported 1,910 tests, 226 skips, and no failures or errors. No opt-in stress campaign was rerun during Step 8; the earlier seeded 10,000-case Resolver03/08/23/26 evidence above remains the stress acceptance evidence.
- Post-review fixes preserve checked parent-demand provenance, isolate Resolver26 transitive inputs from unrelated owner guards, retain the two-argument observer compatibility callback, and validate each shared Query owner against its concrete OER/key address. Before history rewriting, the resulting tree passed the regular seeded qplan check and was recorded as snapshot `de60bd262`.
- A follow-up adversarial review found three ownership-gate false positives rather than runtime defects: wrapper allocation could masquerade as distinct semantic scopes, a Query-side owner could introduce another associated Query root, and an ordinary owner could claim the independent-reference role. The accepted fixes use semantic OER addresses, prohibit associated Query roots from becoming new containing scopes, and justify independent owners with source-replayed root-field-reference evidence. The focused ownership suite passed, the full regular semantics suite passed 1,040 tests with five skips, and the full qplan check plus JMH compilation passed 135 tasks before the accepted tree was recorded as snapshot `f240c2948`.
- Every rewritten commit was then built independently across all discovered qplan source sets: main and test for `arbitrary`; main, test fixtures, and test for `execution` and `model`; and main, test fixtures, test, and JMH for `semantics`. Focused tests at the changed ownership layers covered the retained adversarial regressions. The reconstructed final implementation and test tree was byte-for-byte equal to `f240c2948` before this commit-identity documentation refresh. The full suite and stress campaigns were not rerun during the history rewrite.

Steps 1–8 are acceptance-complete. The branch is ready for deep review.

## Implemented Decision

The branch implements the singular approach, not the intermediate linear-root approach.

The semantic rule is:

> One orchestration task owns its object OER and one associated resolver Query OER context. Resolver Query fragments contribute demand to that associated Query OER. Resolver occurrences discovered on the Query side contribute their own object and Query fragments back into the same Query OER rather than creating another one. Joint preparation reaches one finite fixed point and installs each exact key once; each field-resolver task materializes its occurrence-local input from the associated Query OER. An orchestration with no Query demand retains an undemanded context with empty closed demand.

“Singular” does not mean one process-global or request-global Query OER. It means one Query OER for a defined containing occurrence or query-scope boundary, with transitive root-Query dependencies closed inside it. The settled boundaries for nested object occurrences and root-field-reference invocations are called out below.

For the scalar-root example in `examples.md`, the required behavior is unambiguous: `Q0.field0` creates `Q1`; `Q1` contains `field1`, `field2`, and the transitively required `field3`; resolvers running on `Q1` read their declared Query inputs from projections of `Q1`; no `Q2` is created. Depth 8 therefore performs nine resolver invocations rather than 88, and depth 27 performs 28 rather than 832,039.

## Why This Is A Semantic Change, Not Merely Memoization

The pre-change algorithm gave every active resolver occurrence with a nonempty Query fragment a fresh root identity. Equal coordinates, arguments, and paths in different roots remained distinct work. That policy unfolded a dependency graph into a tree. The generated Resolver23 timeout exposed that even an acyclic graph could then perform Fibonacci-shaped duplicate work: the problem case was finite, but resource use became prohibitive before resolution finished.

A shared Query scope represents the dependency graph directly. Equal exact keys in the same scope use one cell and one producer. Each field-resolver task still materializes its own response-keyed projection, so sharing production must not flatten aliases or merge resolver input values.

The same representation gives recursive Query dependencies the right failure mode. Consider two resolvers whose declared Query fragments require one another:

```text
A requires B
B requires A
```

Fresh per-occurrence roots produce `A@Q0 -> B@Q1 -> A@Q2 -> B@Q3 -> ...`; a one-child-per-generation policy produces the same recurrence as an infinite linear chain. A singular scope represents `A` and `B` once and exposes `A <-> B` as a dependency cycle.

This does not reject a useful GraphQL response shape. GraphQL permits recursive schemas, while the operation chooses a finite traversal depth. Declared Query fragments should likewise describe a finite dependency closure. Repeatedly allocating new Query roots to evade a repeated semantic dependency lets registry-driven work grow deeper than the operation or any finite fragment tree requested. Resolver code may perform arbitrary tenant-owned work internally, but qplan's declarative resolver-input plan should remain finite, one-shot, and cycle checked.

The earlier acyclic example remains acyclic under the singular policy because every dependency advances to a different exact key. Sharing must not report a cycle merely because two owners project overlapping values from the same scope.

## Required Semantic Invariants

Treat the following as implementation requirements rather than incidental test expectations.

1. **Finite scope closure.** All resolver Query demand assigned to one scope reaches a fixed point before the scope's OER key set is frozen and before any producer depends on a missing cell.
2. **One producer per exact key per scope.** Equal field coordinates with equal grounded or symbolically equal arguments in the same scope share one cell and one resolver application. Different arguments remain different keys and different work.
3. **Owner-local projection.** Each resolver receives exactly its declared Query materialization shape, including aliases, directives, inclusion conditions, null/error/list structure, and occurrence-owned variable identities, even though production is shared.
4. **No accidental primary-root reuse.** A containing result OER and its associated resolver Query OER remain distinct occurrences owned by the same orchestration task. The change is from many resolver-owned Query roots to one associated Query OER, not to reading the client operation root directly.
5. **Query-scope self-closure.** A Query-root resolver discovered in a scope contributes its Query fragment to that scope. It must not recursively allocate another root merely because the contributing resolver itself is running on Query.
6. **Cycle visibility.** Reads between producers in one scope use the ordinary exact cycle graph. A repeated semantic dependency must become a `ResolverReadCycleException` or the corresponding depth-first dependency-cycle failure, not another root allocation or a timeout.
7. **One-shot installation.** Every required cell, value promise, variable binding, and writer edge is installed or declared before producers race to read it. Do not restore late mutable demand acceptance as a shortcut.
8. **Observer truthfulness.** Observation should associate every nonempty resolver Query fragment with the shared scope from which its input is materialized. Multiple owners may therefore report the same OER identity. An observation callback remains evidence, not the owner of execution state.
9. **Nested `ctx.query()` remains separate.** This project changes declared resolver Query fragments. A runtime `ctx.query()` call is a resolver-requested child execution with different ownership and must not silently join the declared-input scope.
10. **Root-field references are nested executions.** Each emitted root-field-reference occurrence starts an independently rooted Query execution analogous to `ctx.query()`. It does not contribute its target's Query demand to the caller's resolver-owned scope; resolver Query fragments reached inside the nested execution still use the singular policy internally.
11. **Field checkers are out of scope initially but not architecturally separate.** Checker-owned raw Query inputs may continue to use their current roots on the first resolver-only branch. Design the scope and demand carriers so field and type checkers can later join the same per-containing-occurrence Query scope while retaining unchecked input semantics.

## Scope-Boundary Decisions

The singular policy uses a per-containing-object-occurrence boundary. The root-scalar example exercises only the Query-root occurrence, but the same ownership rule applies when a Query-fragment field returns an object containing further resolver fields.

The rule is:

- Resolver occurrences on the object OER owned by one orchestration task contribute to that task's one associated Query OER.
- Resolver occurrences on the root Query object of that scope contribute back to the same root, producing the fixed point needed by the regression example.
- A distinct nested object OER occurrence gets its own orchestration task and associated Query OER
  context; the latter remains undemanded when none of the object's fields declare Query fragments.
- Nested object occurrences already inside a Query result must not be conflated merely because their overall root identity is the same; list index and containing path remain part of occurrence identity.

This rule preserves the existing meaning of `OEROccurrence` and avoids reopening a frozen Query root when a nested object is discovered only after a producer returns. Registry validation conservatively rejects declarative dependencies that recurse through a Query root and returned descendant object. Distinct runtime descendant occurrences must not be merged or rejected by an ancestry guard merely because they repeat a coordinate; dynamically unbounded tenant output retains ordinary tenant-code nontermination semantics.

Root-field references use a different boundary. Treat each emitted reference occurrence as an independently rooted nested Query execution, analogous to `ctx.query()`, rather than contributing the target's demand to the caller's resolver Query scope. The nested execution itself uses singular resolver Query scopes, so declared fragments reached while resolving the reference cannot recover the old Fibonacci expansion internally. This rule may be implemented by reusing the literal nested-query machinery or by preserving the current direct reference invocation with equivalent root and scope ownership; the semantic boundary matters more than code reuse.

This reference rule does not itself introduce algorithmic superlinear growth. A direct reference tail emits at most one next hop, and each emitted list reference starts one nested execution. Work is therefore linear in the number of reference occurrences supplied by tenant results, multiplied by the cost of their nested queries. A tenant can return a huge or branching collection of references, just as any GraphQL list can be large, but that fanout belongs to the returned data rather than duplicate work invented by Query-root allocation. The eventual value is resolved under the selection and demand of the position into which the reference was inserted. If tenant code nevertheless dynamically returns an infinite reference tail, qplan treats it like recursive `ctx.query()` or any other infinitely recursive resolver behavior rather than imposing a reference-specific detector.

When field- and type-checker work is rebased, checker owners on one containing OER occurrence should use the same Query scope as resolver owners on that occurrence. A field checker belongs to its field's containing OER occurrence. A type checker belongs to the checked base-object occurrence, which therefore supplies its containing scope boundary. Separate object or list-element occurrences retain separate scopes. Sharing the Query OER coalesces value production; it does not change which consumer enforces checker results.

## Existing Architecture To Reuse

The repository already has most of the mechanisms needed for closure and exact execution. The new work should reorganize ownership rather than introduce a second scheduler.

### `Demand` as the future two-axis demand algebra

The field-checker architecture in the `1rv` worktree is useful “architecture of the future” even though checker execution is not part of the first singular-scope implementation. Inspect [`/home/raymie_stata/repos/1rv/qplan/semantics/src/main/kotlin/semantics/shared/Demand.kt`](/home/raymie_stata/repos/1rv/qplan/semantics/src/main/kotlin/semantics/shared/Demand.kt) in that worktree. It represents checked and unchecked construction demand separately, preserves both components through union, guards, and concrete-type merging, and exposes their value union only where provenance no longer matters.

Singular scopes add an independent root-location axis. Do not overload `checked` to mean object-rooted or `unchecked` to mean Query-rooted. The paired construction demand belongs to the orchestration task rather than to any singular resolver, so use `OrchestrationConstructionDemand` consistently with the repository's construction-demand terminology:

```kotlin
internal class OrchestrationConstructionDemand<out S : SelectionForest>(
    val objectRooted: Demand<S>,
    val queryRooted: Demand<S>,
)
```

Conceptually this gives four forests:

| | Object occurrence | Query scope |
| --- | --- | --- |
| Checked consumers | `objectRooted.checked` | `queryRooted.checked` |
| Unchecked consumers | `objectRooted.unchecked` | `queryRooted.unchecked` |

Ordinary client and resolver inputs contribute checked demand. Field- and type-checker inputs contribute unchecked demand. If unchecked demand reaches an active value resolver, that resolver's own object and Query inputs contribute checked demand, preserving the existing rule that unchecked checker reads are not transitive. The same selection may legitimately appear in both provenance components: it still owns one value cell, while checked demand additionally requires the applicable checker slots and unchecked materialization reads only the raw value.

Object- and Query-rooted closure run independently against different OERs, but they should use the same fixed-point vocabulary and operations. Parent-induced additions remain in the component from which they were lifted: checked object demand lifts to checked object demand, unchecked Query demand lifts within unchecked Query demand, and so on. Parent lifting is not a fifth provenance category.

Use this shape to guide naming and APIs on the resolver-only branch, but do not pull checker task execution into the branch merely to instantiate all four cells. Initially, resolver construction will primarily populate `objectRooted.checked` and `queryRooted.checked`; retaining the product shape prevents the implementation from baking in a resolver-only assumption that makes the later checker rebase invasive.

The intended checker end state is one shared Query scope per containing OER occurrence across value resolvers, field checkers, and type checkers. Resolver Query fragments add checked Query demand; checker Query fragments add unchecked Query demand. Overlap produces one Query value cell and, when checked demand exists, the relevant checker-result work. Each resolver materializes a checked projection, while each checker materializes a raw projection from the same cell. This replaces the current per-checker fresh-root policy when the checker branch is integrated; it is not safe physical batching under distinct logical roots, but a deliberate singular-scope semantic change.

### Grounded construction-demand closure

[`semantics/resolvers/ConstructionDemandClosure.kt`](/home/raymie_stata/repos/2rv/qplan/semantics/src/main/kotlin/semantics/resolvers/ConstructionDemandClosure.kt) computes a fixed point for Resolver01–23. It grounds current demand, discovers newly required resolver keys, binds `FromArgument` variables, adds object-fragment construction demand, lifts parent demand, and repeats until no unexpanded exact key remains.

A grounded orchestration preparation closes the task's object-rooted demand and its possibly empty Query-rooted demand as two components of one fixed point. Resolvers active on the object side add object-fragment demand to the object OER and Query-fragment demand to the Query OER. Resolvers active on the Query side add both fragments back to the Query OER. Keep separate expanded-key or expanded-key/inclusion bookkeeping so each exact resolver occurrence contributes fixed inputs once. Do not recursively call the existing fresh-root entry point from inside this closure.

The active field occurrences discovered on the object side provide the initial Query demand. Every maintained orchestration allocates its Query OER optimistically before closure; if demand remains empty, the resulting context simply has empty closed demand.

### Resolver26 construction-demand closure

[`semantics/resolver26/ConstructionDemandClosure.kt`](/home/raymie_stata/repos/2rv/qplan/semantics/src/main/kotlin/semantics/resolver26/ConstructionDemandClosure.kt) retains object- and Query-rooted symbolic demand, exact resolver occurrences, variable definitions, inclusion alternatives, root-field-reference occurrences, and both fragments' provider reads in `ClosedConstructionDemandContext`.

Resolver26 prepares Query-fragment path reads during orchestration closure. Every binding is declared before the Query OER freezes, and each owner reads provider paths from the shared Query result with its own reader identity and inclusion condition.

### One-shot orchestration

Treat orchestration as two phases. Preparation closes demand and installs or declares everything that must exist before execution; launch starts the required field work. One orchestration task performs both phases for its object OER and its associated Query OER. The task is prepared once and dispatched once, and its launch logic handles both OERs. Owner field-resolver tasks should only materialize their projections and await already-installed Query work. An undemanded Query OER follows the same lifecycle with an empty key set.

The shared coroutine `CoroutineOrchestrationTaskBase` now carries both OER contexts and freezes both after field installation. Resolver21–23 close the pair jointly and use one uniform prepare-all-before-dispatch pass across both sides, so installation, freezing, dispatch, failure, and cancellation do not diverge merely because one OER holds resolver Query input. `queryOER.isDemanded()` remains available only where skipping expensive empty work is useful; the carrier is non-null.

For Resolver21–23, `FieldResolutionLogic.runFieldResolver` now materializes ordinary resolver Query input from the prepared shared scope. `CoroutineOperationContext.startResolve` remains the fresh-root entry only for independently rooted nested executions, including `invokeRootFieldResolver`; that path retains `launchIndependentQueryFragmentProducer` and the nested orchestration applies singular sharing internally.

For the fragment-capable depth-first versions, `DepthFirstFieldResolverTask.resolveQueryFragment` recursively creates `DepthFirstResolve`. Replace this with materialization from the task's prepared Query OER. Query-side resolver dependencies must participate in local sibling ordering. Resolver01 and Resolver06 share these task classes but do not support nonempty Query fragments; use them as regression checks for the no-Query-demand path.

For Resolver26, the private `FieldResolutionLogic.materializeQueryFragment` materializes each ordinary owner's input from its prepared associated Query OER. `FieldValueResolver.resolveQueryFragment` remains only for independently rooted reference-target invocations.

### Cycle checking

[`semantics/shared/CycleCheckState.kt`](/home/raymie_stata/repos/2rv/qplan/semantics/src/main/kotlin/semantics/shared/CycleCheckState.kt) associates exact result cells with writer paths and records materializer/provider reader-to-writer edges. Sharing a scope root and cells makes ordinary resolver-input materialization expose same-scope cycles to that existing graph.

Depth-first Resolver01–08 do not use the runtime cycle checker for resolver input readiness. [`SiblingDependencyLogic.kt`](/home/raymie_stata/repos/2rv/qplan/semantics/src/main/kotlin/semantics/resolvers/resolver01/SiblingDependencyLogic.kt) topologically orders unresolved sibling keys using object-fragment dependencies. For a Query scope it must also consider same-scope Query-fragment dependencies. Preserve object-only behavior on ordinary OERs.

The test `ResolverRegistry` already builds a conservative coordinate-level dependency graph from both object and Query fragments and rejects many direct `A <-> B` registries during assembly. Keep that validation. Runtime exact cycle checking is still required for occurrence-, argument-, reference-, and binding-sensitive edges that conservative registry validation cannot precisely classify.

## One Orchestration Task Owns Both OERs

Extend the existing object orchestration task so its unit of responsibility contains:

- the object OER, its source, and its closed object-rooted demand;
- one associated Query OER context with an empty source and closed Query-rooted demand, which may be
  empty when no resolver declares Query demand.

Preparation handles the pair together. It first discovers or closes object-side demand, uses active owners to seed Query-side demand, and closes Query-side demand transitively. Query-side resolvers contribute both their object and Query fragments to that same Query component, so there is no recursive creation of another Query OER. No Query demand means an undemanded Query OER context with an empty key set.

Launch also handles the pair together. The orchestration task is dispatched once and launches the prepared work for the object OER and the demanded work, if any, for the Query OER. Factor the per-OER installation and launch mechanics into helpers and use the same helpers for both sides. The semantic difference belongs in how demand is routed during closure, not in a separate task type, scheduler path, lifecycle, or state machine.

The primary client Query OER is simply the object side of its orchestration task. Its associated internal Query OER remains distinct. A descendant object occurrence gets its own orchestration task, which likewise owns that object OER and one associated Query OER context.

The shared task state carries non-null `objectOER` and `queryOER` `SharedOERContext` values. Grounded-family demand uses the future-compatible checked/unchecked product described above; Resolver26 retains its symbolic per-side closure contexts and prepares Query-side provider reads and binding declarations before dispatch.

Owner-local materialization remains a field-resolver-task responsibility. Each field-resolver task already represents a particular resolver occurrence and therefore has the selections, aliases, arguments, conditions, paths, and variable identities needed to materialize its declared Query fragment. Orchestration makes the prepared associated Query OER available to that task; it does not own a second collection of owner-specific projection selections.

The shared object/Query preparation state belongs on the orchestration task. The owner-local selections remain on the resolver occurrence and field-resolver task rather than being copied into immutable `Assumptions`, `ResolverRegistry`, or another orchestration-owned structure.

## Proposed Implementation Sequence

### 1. Generalize Resolver01–23 construction-demand closure — implemented

Begin below the resolver execution families in the construction-demand closure shared by Resolver01–23. Introduce `OrchestrationConstructionDemand<S>` as the object-rooted and Query-rooted pair of `Demand<S>` values, then update closure to compute both components in one fixed point. This first change should establish the data model and routing rules without waiting for an end-to-end resolver to own and launch both OERs.

Commit `fa0be4e4e` also performs a deliberate carrier and task-seam preparation that is part of Step 1, not an unrelated refactor. It introduces `SharedOERContext` as the durable bundle of one `OEROccurrence`, its passive source, and its closed construction demand; replaces the corresponding loose triple on `SharedOrchestrationTask` with `objectOER`; threads that context through the depth-first, coroutine, and Resolver26 orchestration implementations; and makes `SharedPassiveValueResolutionLogic.materializePassiveFields` consume an OER context independently of a particular task. At this commit the execution families still orchestrate only `objectOER`. Step 2 adds the paired `queryOER` task property, `isDemanded()`, and the temporary undemanded-Query factory while activating the already prepared closure. Keeping the context migration in Step 1 lets the next commit focus on Query ownership, routing, scheduling, and materialization rather than mixing those semantics with a repository-wide carrier rewrite.

The paired closure in `fa0be4e4e` is intentionally exercised directly by unit tests but not yet used by ordinary resolver execution. Its `closeConstructionDemand` compatibility entry point continues the pre-existing object-only lifecycle and deliberately omits Query-rooted demand until Step 2 makes every orchestration own a real associated Query OER. A reviewer should not interpret that adapter as an attempt to partially execute the new policy.

The closure rules are:

- an active resolver discovered through object-rooted demand contributes its object fragment to `objectRooted` and its Query fragment to `queryRooted`;
- an active resolver discovered through Query-rooted demand contributes both fragments to `queryRooted`;
- each exact resolver occurrence contributes its fragments once even when reached through overlapping demand;
- checked and unchecked provenance remains independent inside each rooted component;
- parent lifting preserves both axes: it returns to the same object/Query rooted component and the same checked/unchecked component from which the parent demand arose.

Add focused unit tests to [`semantics/resolvers/ConstructionDemandClosureTest.kt`](/home/raymie_stata/repos/2rv/qplan/semantics/src/test/kotlin/semantics/resolvers/ConstructionDemandClosureTest.kt) before changing resolver execution. Cover empty Query demand, initial Query seeding, transitive Query-side closure, exact-key coalescing, and separate checked/unchecked contributions. Add parent cases in which object-rooted and Query-rooted demand independently lift through `@parent`, lifted demand activates another resolver, that resolver's fragments enter the correct components, and multiple closure iterations are required. The tests should assert the generated component forests directly rather than relying only on a final resolved value.

This unit-test rung does not make Resolver01–21 support `@parent`; their public input domains remain unchanged. It puts the parent-aware closure structure in place early. Retrospectively, later integration did not discover a flaw in Step 1's fixed-point, fragment-routing, checked/unchecked, or parent-lifting rules. The final Resolver26 commit removes the now-unused nullable compatibility path and replaces the grounded closure's private fragment-pair helper with shared `ResolverInputConstructionDemand` so the grounded and symbolic closures use the same vocabulary; neither change alters the Step 1 closure result. Later execution, scheduling, lifecycle, and witness fixes belong to their respective steps rather than being retroactive repairs to this closure.

### 2. Establish the shared semantics and evidence through Resolver02 — implemented

After the shared closure rung, start end-to-end execution with Resolver02. It is the simplest maintained implementation that supports nonempty object and Query fragments and `FromArgument`, while avoiding selective successor demand, explicit reactor scheduling, coroutine suspension, field checkers, and Resolver26's symbolic bindings. Use it to establish the new orchestration shape and the oracle, witness, and observation semantics before adding those concerns, fixing closure bugs uncovered by real execution as needed.

[`QueryFragmentResolverContract.kt`](/home/raymie_stata/repos/2rv/qplan/semantics/src/testFixtures/kotlin/semantics/contract/QueryFragmentResolverContract.kt) currently codifies the old policy in test names and assertions such as “do not share OERs,” `assertNotSame`, and a transitive test that expects the middle resolver to own a second observed Query result. Revise the contract and first make its new expectations pass through Resolver02.

The revised contract should require:

- two resolver owners on one containing OER associate with the same Query OER;
- aliases and owner-local materialization shapes remain distinct;
- different grounded arguments occupy different keys in the associated Query OER;
- equal exact dependencies coalesce to one application;
- a transitive root-Query dependency associates the outer and inner owners with the same OER;
- empty Query fragments leave the optimistically allocated associated Query OER undemanded and
  materialize an empty Query value;
- Query-fragment failures become the appropriate owner input error without corrupting unrelated projections;
- no owner observes fields outside its declared materialization projection.

Add a small diamond test in which two owners request the same exact Query key and assert one producer application plus two correct owner projections. Add the argument variant where the owners request the same coordinate with different arguments and assert two keys and two applications in the same Query OER. Adapt the resolver-only expansion fixture so Resolver02 can prove that depth 8 performs nine resolver invocations, each `fieldN` exact key is invoked once, and the client result remains unchanged.

Update observation and correctness evidence as part of this Resolver02 milestone. `ResolverObserver.onQueryFragmentPrepared` should still emit once per nonempty owner, but several owner IDs may carry the same `ObjectEngineResult`. `CorrectnessResolverObserver` and every `queryFragmentResults(...).single()` consumer must tolerate that shared identity. Validate the associated Query OER once against its unioned closed demand and validate each field-resolver task's projection separately; do not globally permit unexplained extra cells.

Implement the paired-OER lifecycle in the shared `DepthFirstOrchestrationTask` and `DepthFirstFieldResolverTask` machinery, but use Resolver02 as the first semantic target. The depth-first task must know the complete dependencies of both OERs before launch. Extend `SiblingDependencyLogic` so a resolver on the Query side depends on exact keys selected by both its object fragment and its Query fragment. Repeated exact dependencies must become dependency cycles rather than recursive root creation.

Revisit `DepthFirstQueryFringeOrderingContract`. It currently proves that an independent Query fragment uses its own dispatcher and cannot consume the enclosing fringe. Under singular sharing, require the one orchestration task to drain both prepared OERs according to their complete dependency order without stealing unrelated work from the containing result traversal.

Resolver01 shares the modified recursive task implementation but does not support nonempty Query fragments. Its full contract is a compatibility check: the optimistically allocated Query OER must remain undemanded and its ordinary object orchestration must remain unchanged.

### 3. Add Resolver03 selective successor demand — acceptance complete

Once Resolver02 passes, run the same shared Query-fragment contract through Resolver03. This should be a small semantic step because Resolver03 shares the recursive orchestration and field-resolver-task implementation.

The specific new concern is selective resolution. Successor demand must be computed for resolver invocations on both the object OER and the associated Query OER, including transitive Query-side invocations discovered during joint closure. Assert that combining those invocations does not lose required successor demand, activate undemanded output, or duplicate a resolver application. Retain Resolver03's selective output and one-shot witness expectations.

### 4. Carry the implementation to Resolver06–08 — acceptance complete

Next apply the proven recursive semantics to the explicit depth-first task/reactor family. Resolver06 is a compatibility baseline only: like Resolver01, it does not support nonempty Query fragments but shares the task machinery and must preserve the no-Query-demand path. Resolver07 should match Resolver02's complete-output Query-fragment behavior, and Resolver08 should match Resolver03's selective successor-demand behavior.

Use this stage to expose any assumption that worked only because Resolver02/03 executed recursively. The reactor must enqueue and drain work for both OERs through one orchestration task while retaining exact task identity, ordering, publication, and fringe boundaries.

### 5. Carry the implementation to Resolver21–23 — acceptance complete

Resolver21 remains the empty-fragment compatibility check for the shared coroutine orchestration lifecycle: its real Query context remains undemanded, is frozen with the object OER, and leaves existing publication behavior unchanged. Resolver22 establishes complete-output singular behavior and validates the parent-aware closure structure introduced in Step 1. Resolver23 adds selective successor demand and confirms that checker-free selective resolution behaves like Resolver03/08.

Resolver21–23 `CoroutineOrchestrationTask` now replaces the temporary undemanded Query context with real Query-side closed state, prepares both sides' ordinary publications before their dispatch, and dispatches them from one dispatch. Each `GroundedFieldPublicationOccurrence` receives the prepared Query OER, and its field-resolver task uses the resolver occurrence's existing fragment information to materialize its own projection without creating or dispatching ordinary Query production.

Resolver22/23 retain the full `ParentFieldResolverContract` and add a directed interaction in which Query-rooted parent-lifted demand activates an ancestor resolver with a nonempty Query fragment. Query-rooted closure traverses the parent structure without leaking cells into the client result, ancestor/descendant re-entry does not freeze an OER early, and every producer runs once.

Resolver23's original resolver-only timeout reproduction now runs at depth 27 and completes within the ordinary timeout with 28 resolver invocations rather than 832,039; every exact `fieldN` key is invoked once. The coroutine-specific contract also proves that cancellation terminates one shared producer and every owner waiting on it.

Within joint closure for every family, route fragments according to the side on which the resolver occurrence was discovered: object-side object fragments remain on the object side, while object-side Query fragments and both fragments of Query-side resolvers enter the Query side. This is demand routing inside one orchestration lifecycle, not a second task kind.

### 6. Migrate Resolver26 only after Resolver01–23 are stable — acceptance complete

Resolver26 adds symbolic keys, `FromObjectField`, `FromQueryField`, `FromProvider`, inclusion conditions, provider-read cycles, and request-owned concurrent execution. Its migration retained those capabilities while adopting the shared-scope ownership established in earlier families.

`ClosedConstructionDemandContext` now retains the object- and Query-rooted demand components prepared by one orchestration task, including every contributing resolver occurrence, its inclusion alternatives, and both object- and Query-fragment provider reads. During closure, newly discovered Query-side resolver occurrences contribute guarded object and Query construction selections back into the Query component.

Resolver21–23 and Resolver26 each keep shared-Query projection in a private `FieldResolutionLogic.materializeQueryFragment` helper, preserving the symbolic/grounded distinction and each family's explicit materializer dependencies.

Every binding needed by both closed demand components is declared before dispatch. A `FromQueryField` binding reads from the associated Query OER but remains owned by one resolver occurrence; equal produced values do not merge variable instances. A `FromObjectField` binding used in a Query fragment still reads the owner's object OER. Provider reads retain parallel readiness so neither fragment has artificial global precedence.

Although the Resolver26 materializer can reserve symbolic cells, orchestration determines and installs the complete symbolic key domain on both OERs before dispatching any producer and freezing either result.

### 7. Address references and nested objects deliberately — acceptance complete

After root-scalar and ordinary owner sharing work, add tests for:

- a root-field-reference target that starts an independently rooted nested Query execution;
- a target with a nonempty declared Query fragment whose nested execution uses singular sharing internally;
- two emitted reference occurrences that remain distinct nested executions rather than coalescing through the caller's resolver Query scope;
- direct reference tails retaining tenant-code recursion semantics rather than acquiring a reference-specific cycle detector;
- a Query field returning an object whose descendant resolver declares a Query fragment;
- list elements producing separate containing occurrences;
- abstract result types whose concrete descendant resolvers contribute different Query demand.

Use those tests to enforce the settled scope boundaries described earlier. Do not infer correctness solely from the scalar regression, and do not classify tenant-provided list fanout as resolver Query-scope duplication.

### 8. Update documentation and then run broad validation — acceptance complete

The final cross-cutting audit includes the following documents. The Resolver26-local and family-comparison descriptions were brought current during Step 6, but Step 8 must check the complete set together:

- [`design-principles.md`](/home/raymie_stata/repos/2rv/qplan/design-principles.md), especially occurrence identity and the statement that every independently executed Query fragment has a fresh root;
- [`semantics/README.md`](/home/raymie_stata/repos/2rv/qplan/semantics/README.md), including correctness, depth-first execution, coroutine execution, observer timing, and declared Query-fragment ownership;
- [`resolver-versions.md`](/home/raymie_stata/repos/2rv/qplan/resolver-versions.md), which says the maintained families resolve independent Query fragments through fresh dispatchers;
- [`semantics/testing-contracts.md`](/home/raymie_stata/repos/2rv/qplan/semantics/testing-contracts.md), especially `QueryFragmentResolverContract` and any exact root-identity expectations;
- [`examples.md`](/home/raymie_stata/repos/2rv/qplan/examples.md), promoting singular resolution from a candidate to the implemented policy once true;
- [`handoff.md`](/home/raymie_stata/repos/2rv/qplan/handoff.md), with the branch point, test evidence, remaining scope decisions, and performance result.

Do not implement checker scope migration on the first resolver-only branch. `access-check-semantics.md` currently gives checker occurrences separate raw Query-root boundaries; when the field-checker work is rebased, revise that policy intentionally so resolver, field-checker, and type-checker Query inputs on one containing occurrence share production while their checked and unchecked demand remains separate.

## Cycle Tests And Failure Semantics

Cycle behavior deserves its own acceptance layer because it is part of the rationale for singular scopes.

Add at least these cases:

1. A finite increasing-key chain closes and resolves without a cycle.
2. A diamond with one repeated exact dependency coalesces and resolves without a cycle.
3. Direct same-key Query recursion fails deterministically before timeout.
4. Mutual exact-key Query recursion fails deterministically before timeout.
5. The same coordinate with different arguments does not become a false cycle merely because the coordinate repeats.
6. The same coordinate in genuinely different containing object occurrences remains distinct unless the chosen scope rule explicitly joins them.
7. A cycle involving an object-fragment read and a same-scope Query-fragment read is rejected.
8. Resolver26 provider-path cycles remain rejected with the same or stronger attribution.

The fixture registry may reject cases 3 or 4 statically because its conservative coordinate graph already includes Query-fragment edges. That is acceptable and useful, but it does not replace a runtime exact-cycle test. If ordinary fixtures cannot construct a runtime-only cycle, use the narrowest custom registry or reference/binding scenario that bypasses only the conservative false-positive boundary; do not weaken registry validation merely to exercise runtime logic.

Errors should identify the participating resolver tasks and shared scope paths. Preserve `ResolverReadCycleException` for coroutine families where possible. Depth-first families may retain their deterministic dependency-cycle exception, but tests should assert the semantic failure rather than an incidental collection order.

## Validation Plan

Run focused tasks from the qplan directory after each stage. Use explicit seeds for generated validation.

Start with the new regression and the family being changed:

```shell
./gradlew :semantics:test \
  --tests 'semantics.resolvers.resolver23.QueryFragmentExpansionTest' \
  --tests 'semantics.resolvers.resolver23.ResolverContractTest'
```

Then run every implementation of the shared Query-fragment contract:

```shell
./gradlew :semantics:test \
  --tests 'semantics.resolvers.resolver02.ResolverContractTest' \
  --tests 'semantics.resolvers.resolver03.ResolverContractTest' \
  --tests 'semantics.resolvers.resolver07.ResolverContractTest' \
  --tests 'semantics.resolvers.resolver08.ResolverContractTest' \
  --tests 'semantics.resolvers.resolver22.ResolverContractTest' \
  --tests 'semantics.resolvers.resolver23.ResolverContractTest' \
  --tests 'semantics.resolver26.ResolverContractTest'
```

After oracle changes, run generated profiles that activate Query fragments and variables with a stable seed. Consult `semantics/testing-contracts.md` for the current profile IDs rather than guessing task names. Coordinate failures should be replayed with `:semantics:resolverPropertyReplay` and reduced to deterministic scope tests.

Before handoff or merge, run:

```shell
./gradlew check -PresolverPropertySeed=1
```

Record exact task counts, elapsed time, skipped tests, and any opt-in campaigns not run. Do not claim the timeout fixed solely because depth 27 completes; assert the expected application count so a faster machine cannot hide continued duplicate work.

## Expected Test And Oracle Breakage

The following failures are expected during the migration and should be interpreted as old-policy assumptions, not patched around blindly:

- `QueryFragmentResolverContract` explicitly asserts distinct OER identities for separate owners.
- Its transitive-resolution test expects an inner owner to create another observed Query root.
- `CorrectnessResolverObserver` consumers assume one independently correct root per owner.
- `correctResolution` recursively validates per-owner Query roots and may reject shared supersets or revisit the same scope.
- Depth-first fringe-ordering tests assume a recursive dispatcher per Query fragment.
- Generated application accounting may expect duplicate resolver occurrences distinguished only by Query-root identity.
- Observer documentation and tests may assume one preparation event means one newly allocated root.
- Root-field-reference tests may assume target Query fragments always own independent roots.

Change the semantic expectation once, in shared contracts and shared oracle code. Avoid resolver-version-specific compatibility flags that preserve the old identity policy in some families unless a version's documented capability genuinely requires it.

## Risks And Things Not To Do

- Do not implement the singular policy as a request-global cache keyed by field and arguments. Containing occurrence, path, variable ownership, inclusion context, and scope lifetime matter.
- Do not reuse the primary client Query OER as the resolver Query scope. A distinct scope preserves separation between client result demand and internal resolver construction demand.
- Do not reopen frozen OERs when later demand appears. If the chosen scope cannot be fully prepared, revisit the scope boundary or closure algorithm.
- Do not treat argument grounding as a source of additional selection occurrences. Each instantiated selection occurrence produces at most one exact key; arguments only determine whether independently generated occurrences coalesce.
- Do not weaken aliases or response-keyed materialization to make shared cells easy. Production may be shared while projections remain different.
- Do not suppress cycle checking because the fixture registry already rejects simple coordinate cycles. Runtime exact identities still matter.
- Do not fold nested `ctx.query()` into declared fragment closure.
- Do not fold root-field-reference targets into the caller's resolver Query scope. Their nested executions use the singular policy internally but retain separate roots.
- Do not generalize to field-checker Query demand in the first pass. Resolver-only correctness and liveness should be independently green first.
- Do not design a resolver-only demand carrier that conflates root location with checked provenance. The checker integration needs the full object/Query by checked/unchecked product even if the first branch populates only its checked row.
- Do not revive Resolver10-style late readiness rescanning or persistent mutable demand acceptance. The desired result is a finite prepared scope, not a more dynamic scheduler.
- Do not stop after the Resolver02/03 reference implementation. Completion requires Resolver07/08, Resolver22/23, and Resolver26 to satisfy the same shared contract; Resolver01/06/21 must remain green on their empty-fragment capability boundary.

## Resolved Decisions From Steps 6–7

1. Resolver26 closes every satisfiable inclusion alternative structurally, installs its possible work before dispatch, and lets occurrence-local conditions suppress excluded invocation and reads at runtime.
2. Resolver26 declares the complete binding domains for both closed demand components before dispatch; object-, Query-, and provider-rooted readers retain their absolute roots and concurrent promise readiness.
3. Conservative registry validation rejects declarative direct and mutual Query recursion. Runtime exact-cycle evidence remains in `SiblingDependencyLogicTest`, `CoroutineResolverContract`, provider-path validation, and `CycleCheckStateTest`; registry validation was not weakened merely to manufacture another runtime case.
4. Root-field references retain direct invocation. Each hop receives a fresh invocation identity and independently rooted Query execution, whose internal ordinary fragments use singular sharing.
5. No ancestry guard is added across distinct returned object occurrences. Registry validation rejects declarative cross-scope recursion, while repeated coordinates in genuinely different runtime occurrences remain semantically distinct.
6. Grounded and symbolic families share `SharedOERContext`, `ResolverInputConstructionDemand`, paired orchestration roles, and lifecycle contracts. Their closure bookkeeping, installed-cell strategy, bindings, and readiness mechanisms remain intentionally family-specific.

## Follow-Up: Pre-Existing Problems Found During Review

### Resolver26 contract-fixture accounting is unsafe with multiple workers

An adversarial review ran the ordinary Resolver26 package with five resolution workers:

```shell
./gradlew :semantics:test \
  --tests 'semantics.resolver26.*' \
  -PresolverPropertySeed=424242 \
  -Pviaduct.resolution.threadcount=5
```

Two unchanged `ObjectFragmentResolverContract` cases failed only in their invocation-accounting
assertions after their resolved-value and output-shape assertions had passed:

- `object outputs vary by input and arguments at equal-key list occurrences`;
- `preserves arguments and occurrence-distinct list output shapes`.

The fixtures record concurrently executing resolver bodies in ordinary mutable lists and increment
ordinary mutable integer counters. Those operations are not thread-safe, so lost list entries or
counter increments are possible. The observed failures each omitted one expected application entry
and are consistent with this pre-existing fixture race. The affected cases do not declare Query
fragments, so this is not direct evidence of a singular Query-OER defect. The new scheduling may
change timing and make the old race easier to expose, but the review did not reproduce the failure
on the preceding revision and therefore did not attribute it to this project.

Leave this outside the singular Query-OER commit stack. A future fix should use concurrent
collections and atomic counters in these fixtures, then rerun the Resolver26 package with five
workers, preferably repeatedly. Any value, shape, or application-accounting failure that remains
after making the fixture observations thread-safe should be investigated as a possible Resolver26
runtime defect.

## Completion Boundary

The singular Query-scope work is complete when all resolver families that advertise declared Query-fragment support satisfy the revised shared contract; the checker-free depth-27 graph executes one resolver per exact field key; simple recursive Query dependencies fail deterministically rather than expanding; aliases, arguments, variables, references, failures, cancellation, and observer/correctness replay remain valid; documentation describes shared scopes rather than independent per-owner roots; and the full seeded qplan check passes.

Field-checker Query-scope migration is not part of that completion boundary. Preserve a clean resolver-only commit stack so the F4 field-checker branch can be rebased afterward. The planned integration should place resolver, field-checker, and type-checker Query demand in the same per-containing-occurrence scope using separate checked and unchecked components; make that an explicit follow-up rather than an accidental merge-conflict resolution.
