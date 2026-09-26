# Singular Query-Scope Resolution Handoff

## Purpose

This handoff is for the implementation branch already created in `/home/raymie_stata/repos/2rv`. Its first objective is to replace resolver-owned fresh Query OER expansion with singular, shared Query scopes. The motivating walkthrough and the exponential, linear-root, and singular alternatives are in [`examples.md`](/home/raymie_stata/repos/2rv/qplan/examples.md#fresh-query-oer-resolution-strategies); use that example as the compact behavioral specification rather than reproducing it here.

The user has already created and selected the target branch in the `2rv` worktree and will copy this handoff and the revised `examples.md` there. Treat that existing branch as the starting state; do not recreate it, change its base, or copy other changes from `1rv` unless the user directs otherwise.

All implementation work belongs in `/home/raymie_stata/repos/2rv/qplan`, and every Gradle command must run from that directory. The `1rv` worktree is a read-only architecture reference only where this handoff explicitly points to it; do not modify it from the implementation session. Everst has no qplan information and must not be used.

## Current Status And Remaining Sequence

This section is authoritative when older planning language below conflicts with the implementation. The committed branch completed Steps 1–4 in `9180ff29f`, `1e444d77d`, `8696e8a0f`, and `029f7753c`; the current `HEAD` commit completes Step 5 for Resolver21–23.

- `OrchestratorConstructionDemand` represents object- and Query-rooted demand, each retaining checked and unchecked provenance. The grounded closure used by Resolver01–23 closes the pair in one fixed point, including transitive Query-side resolver inputs and parent lifting.
- `SharedOERContext` carries an OER occurrence, passive source, and closed construction demand. Shared orchestration and publication state use non-null `objectOER` and `queryOER` contexts.
- Resolver02–03, Resolver07–08, and Resolver22–23 implement singular Query OERs. Resolver01, Resolver06, and Resolver21 remain empty-fragment compatibility baselines while sharing their families' paired-OER machinery.
- Every Resolver01–23 orchestration optimistically allocates one real associated Query OER. If closure discovers no Query demand, that context has empty `closedDemand`, `isDemanded()` is false, and it is harmlessly frozen and discarded. Resolver26 alone retains a temporary undemanded placeholder and its independent ordinary Query-fragment producers until Step 6.
- Resolver21–23 jointly close object- and Query-rooted demand, install all publications on both OERs before dispatching any field task, materialize each ordinary owner's local projection from the shared Query OER, and freeze both OERs from one orchestration dispatch. Promise readiness and coroutine suspension remain their execution mechanism; they do not acquire the depth-first families' dependency ordering.
- Resolver22/23 validate Query-rooted parent lifting through a directed ancestor/descendant interaction: lifted demand activates one ancestor resolver with its own Query fragment, every involved resolver runs once, and Query-side cells do not leak into the client result.
- Resolver23 uses the depth-27 expansion witness and completes with 28 resolver invocations rather than 832,039. Shared-production failure and cancellation tests cover multiple owners waiting on one producer.
- `queryOERDepth` retains the same meaning throughout Resolver01–08 and remains useful to the Resolver06–08 reactor. Resolver21–23 report their real Query OERs without inventing a depth metric their scheduler neither tracks nor uses.
- Root-field-reference targets remain independently rooted nested executions. Declared Query fragments reached within such an execution use the singular policy internally for Resolver01–23.
- Observation and correctness replay record each shared Query OER once, associate every nonempty owner with it, validate its exact unioned closed demand once, and validate owner projections separately.

Current validation evidence is:

- The final focused Resolver01–08 acceptance run passed 295 tests with two expected skips. Seeded 10,000-case Resolver03 and Resolver08 stress runs each generated 20,385 Query fragments, included 1,200 fragment-free cases, activated 60,406 fragment-bearing applications, included 7,473 cases with and 2,527 cases without demanded Query OERs, and observed demanded depths `{1=34179, 2=10118, 3=709, 4=9}`.
- The focused Resolver21–23 lifecycle, contract, and seed-1 generated acceptance run passed 207 tests with one expected skip and no failures. It includes Resolver21's undemanded/frozen context, Resolver22/23 parent integration, Resolver23's depth-27 witness, shared failure and cancellation, and all generated profiles for the three versions.
- `./gradlew :semantics:resolver23Stress -Presolver23StressSeed=424242` passed all 10,000 requested cases: 26,185 generated Query fragments, 760 fragment-free cases, 31,214 activated Query-fragment applications, 6,540 cases with demanded Query OERs, and 3,460 without. `demandedQueryOERDepthCounts` is intentionally empty because coroutine resolvers do not fabricate the depth-first scheduler metric.
- Singular sharing required the resolver-witness oracle to traverse each observed Query result once by identity: multiple owners may associate the same Query OER with distinct projections. The focused and stress results validate that behavior across both grounded execution families.
- An unseeded full `./gradlew check` passed before the final `ObjectEngineResult.materializeInput` refactor. The exact current tree has not yet had the final seeded full check.

Steps 1–5 are acceptance-complete. Remaining work follows the original sequence: migrate Resolver26 in Step 6, add the deliberate reference and nested-object boundary coverage in Step 7, then complete the remaining cross-cutting documentation and broad seeded validation in Step 8.

## Recommendation

Implement the singular approach, not the intermediate linear-root approach.

The semantic rule should be approximately:

> One orchestration task owns its object OER and one associated resolver Query OER context. Resolver Query fragments contribute demand to that shared Query OER. Resolver occurrences discovered on the Query side contribute their own object and Query fragments back into the same Query OER rather than creating another one. Joint preparation reaches one finite fixed point and installs each exact key once; each field-resolver task materializes its occurrence-local input from the shared Query OER. An orchestration with no Query demand retains an undemanded context with empty closed demand.

“Singular” does not mean one process-global or request-global Query OER. It means one Query OER for a defined containing occurrence or query-scope boundary, with transitive root-Query dependencies closed inside it. The exact boundary for nested object occurrences and root-field-reference invocations is consequential and is called out below; settle it explicitly before generalizing the first root-scalar implementation.

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

This rule preserves the existing meaning of `OEROccurrence` and avoids reopening a frozen Query root when a nested object is discovered only after a producer returns. A recursive dependency crossing a Query root, a returned object occurrence, and another Query scope can still unfold across scope boundaries; add a directed test and use an ancestry-level recursion guard if the ordinary exact cycle graph cannot see the recurrence. Do not merge distinct descendant occurrences merely to obtain cycle detection.

Root-field references use a different boundary. Treat each emitted reference occurrence as an independently rooted nested Query execution, analogous to `ctx.query()`, rather than contributing the target's demand to the caller's resolver Query scope. The nested execution itself uses singular resolver Query scopes, so declared fragments reached while resolving the reference cannot recover the old Fibonacci expansion internally. This rule may be implemented by reusing the literal nested-query machinery or by preserving the current direct reference invocation with equivalent root and scope ownership; the semantic boundary matters more than code reuse.

This reference rule does not itself introduce algorithmic superlinear growth. A direct reference tail emits at most one next hop, and each emitted list reference starts one nested execution. Work is therefore linear in the number of reference occurrences supplied by tenant results, multiplied by the cost of their nested queries. A tenant can return a huge or branching collection of references, just as any GraphQL list can be large, but that fanout belongs to the returned data rather than duplicate work invented by Query-root allocation. A cyclic reference tail can still fail to terminate as a linear recurrence; detect or bound that separately rather than joining references to the caller's resolver Query scope.

When field- and type-checker work is rebased, checker owners on one containing OER occurrence should use the same Query scope as resolver owners on that occurrence. A field checker belongs to its field's containing OER occurrence. A type checker belongs to the checked base-object occurrence, which therefore supplies its containing scope boundary. Separate object or list-element occurrences retain separate scopes. Sharing the Query OER coalesces value production; it does not change which consumer enforces checker results.

## Existing Architecture To Reuse

The repository already has most of the mechanisms needed for closure and exact execution. The new work should reorganize ownership rather than introduce a second scheduler.

### `Demand` as the future two-axis demand algebra

The field-checker architecture in the `1rv` worktree is useful “architecture of the future” even though checker execution is not part of the first singular-scope implementation. Inspect [`/home/raymie_stata/repos/1rv/qplan/semantics/src/main/kotlin/semantics/shared/Demand.kt`](/home/raymie_stata/repos/1rv/qplan/semantics/src/main/kotlin/semantics/shared/Demand.kt) in that worktree. It represents checked and unchecked construction demand separately, preserves both components through union, guards, and concrete-type merging, and exposes their value union only where provenance no longer matters.

Singular scopes add an independent root-location axis. Do not overload `checked` to mean object-rooted or `unchecked` to mean Query-rooted. The paired construction demand belongs to the orchestrator rather than to any singular resolver, so use `OrchestratorConstructionDemand` consistently with the repository's construction-demand terminology:

```kotlin
internal class OrchestratorConstructionDemand<out S : SelectionForest>(
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

The active field occurrences discovered on the object side provide the initial Query demand. Every Resolver01–23 orchestration allocates its Query OER optimistically before closure; if demand remains empty, the resulting context simply has empty closed demand. Resolver26 temporarily uses an undemanded placeholder until its paired symbolic preparation is implemented.

### Resolver26 construction-demand closure

[`semantics/resolver26/ConstructionDemandClosure.kt`](/home/raymie_stata/repos/2rv/qplan/semantics/src/main/kotlin/semantics/resolver26/ConstructionDemandClosure.kt) retains symbolic keys, exact resolver occurrences, variable definitions, inclusion alternatives, root-field-reference occurrences, and object-fragment provider reads in `ClosedConstructionDemandContext`. It is the likely eventual home for symbolic Query-scope closure, but it should not be the first implementation target.

Resolver26 currently prepares Query-fragment path reads inside each owning `FieldResolverTask`. The orchestration task will need those reads in its prepared Query-side state so every binding is declared before the Query OER freezes and so each owner reads provider paths from the shared Query result with its own reader identity and inclusion condition.

### One-shot orchestration

Treat orchestration as two phases. Preparation closes demand and installs or declares everything that must exist before execution; launch starts the required field work. One orchestration task performs both phases for its object OER and its associated Query OER. The task is prepared once and dispatched once, and its launch logic handles both OERs. Owner field tasks should only materialize their projections and await already-installed Query work. An undemanded Query OER follows the same lifecycle with an empty key set.

The shared coroutine `CoroutineOrchestrationTask` now carries both OER contexts and freezes both after field installation. Resolver21–23 close the pair jointly and use one uniform prepare-all-before-dispatch pass across both sides, so installation, freezing, dispatch, failure, and cancellation do not diverge merely because one OER holds resolver Query input. `queryOER.isDemanded()` remains available only where skipping expensive empty work is useful; the carrier is non-null.

For Resolver21–23, `FieldResolutionLogic.runFieldResolver` now materializes ordinary resolver Query input from the prepared shared scope. `CoroutineOperationContext.startResolve` remains the fresh-root entry only for independently rooted nested executions, including `invokeRootFieldResolver`; that path retains `launchQueryFragmentProducer` and the nested orchestration applies singular sharing internally.

For the fragment-capable depth-first versions, `DepthFirstFieldResolverTask.resolveQueryFragment` recursively creates `DepthFirstResolve`. Replace this with materialization from the task's prepared Query OER. Query-side resolver dependencies must participate in local sibling ordering. Resolver01 and Resolver06 share these task classes but do not support nonempty Query fragments; use them as regression checks for the no-Query-demand path.

For Resolver26, `FieldResolver.resolveQueryFragment` in [`FieldResolverTask.kt`](/home/raymie_stata/repos/2rv/qplan/semantics/src/main/kotlin/semantics/resolver26/FieldResolverTask.kt) creates and dispatches the per-owner Query OER. This is the eventual seam, but its binding, inclusion, cancellation, and symbolic-cell behavior make it a later stage.

### Cycle checking

[`semantics/shared/CycleCheckState.kt`](/home/raymie_stata/repos/2rv/qplan/semantics/src/main/kotlin/semantics/shared/CycleCheckState.kt) already keys tasks by task kind, Query-root identity, and exact path, and keys reads by exact result slot. Once two Query-dependent resolvers truly share a scope root and cells, ordinary resolver-input materialization should register the necessary reader-to-writer edges. Verify this with a focused test before adding special-purpose cycle logic.

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

The shared task state now carries non-null `objectOER` and `queryOER` `SharedOERContext` values, while
object- and Query-rooted demand use the future-compatible checked/unchecked product described above.
Resolver26 still needs to replace its undemanded placeholder with prepared Query-side provider reads
and binding declarations.

Owner-local materialization remains a field-resolver-task responsibility. Each field-resolver task already represents a particular resolver occurrence and therefore has the selections, aliases, arguments, conditions, paths, and variable identities needed to materialize its declared Query fragment. Orchestration makes the prepared shared Query OER available to that task; it does not own a second collection of owner-specific projection selections.

The shared object/Query preparation state belongs on the orchestration task. The owner-local selections remain on the resolver occurrence and field-resolver task rather than being copied into immutable `Assumptions`, `ResolverRegistry`, or another orchestration-owned structure.

## Proposed Implementation Sequence

### 1. Generalize Resolver01–23 construction-demand closure — implemented

Begin below the resolver execution families in the construction-demand closure shared by Resolver01–23. Introduce `OrchestratorConstructionDemand<S>` as the object-rooted and Query-rooted pair of `Demand<S>` values, then update closure to compute both components in one fixed point. This first change should establish the data model and routing rules without waiting for an end-to-end resolver to own and launch both OERs.

The closure rules are:

- an active resolver discovered through object-rooted demand contributes its object fragment to `objectRooted` and its Query fragment to `queryRooted`;
- an active resolver discovered through Query-rooted demand contributes both fragments to `queryRooted`;
- each exact resolver occurrence contributes its fragments once even when reached through overlapping demand;
- checked and unchecked provenance remains independent inside each rooted component;
- parent lifting preserves both axes: it returns to the same object/Query rooted component and the same checked/unchecked component from which the parent demand arose.

Add focused unit tests to [`semantics/resolvers/ConstructionDemandClosureTest.kt`](/home/raymie_stata/repos/2rv/qplan/semantics/src/test/kotlin/semantics/resolvers/ConstructionDemandClosureTest.kt) before changing resolver execution. Cover empty Query demand, initial Query seeding, transitive Query-side closure, exact-key coalescing, and separate checked/unchecked contributions. Add parent cases in which object-rooted and Query-rooted demand independently lift through `@parent`, lifted demand activates another resolver, that resolver's fragments enter the correct components, and multiple closure iterations are required. The tests should assert the generated component forests directly rather than relying only on a final resolved value.

This unit-test rung does not make Resolver01–21 support `@parent`; their public input domains remain unchanged. It puts the parent-aware closure structure in place early. Expect Resolver22, the first end-to-end parent-capable implementation, to expose and fix integration bugs without requiring the demand representation to be redesigned.

### 2. Establish the shared semantics and evidence through Resolver02 — implemented

After the shared closure rung, start end-to-end execution with Resolver02. It is the simplest maintained implementation that supports nonempty object and Query fragments and `FromArgument`, while avoiding selective successor demand, explicit reactor scheduling, coroutine suspension, field checkers, and Resolver26's symbolic bindings. Use it to establish the new orchestration shape and the oracle, witness, and observation semantics before adding those concerns, fixing closure bugs uncovered by real execution as needed.

[`QueryFragmentResolverContract.kt`](/home/raymie_stata/repos/2rv/qplan/semantics/src/testFixtures/kotlin/semantics/contract/QueryFragmentResolverContract.kt) currently codifies the old policy in test names and assertions such as “do not share OERs,” `assertNotSame`, and a transitive test that expects the middle resolver to own a second observed Query result. Revise the contract and first make its new expectations pass through Resolver02.

The revised contract should require:

- two resolver owners on one containing OER associate with the same Query OER;
- aliases and owner-local materialization shapes remain distinct;
- different grounded arguments occupy different keys in the shared Query OER;
- equal exact dependencies coalesce to one application;
- a transitive root-Query dependency associates the outer and inner owners with the same OER;
- empty Query fragments leave the optimistically allocated associated Query OER undemanded and
  materialize an empty Query value;
- Query-fragment failures become the appropriate owner input error without corrupting unrelated projections;
- no owner observes fields outside its declared materialization projection.

Add a small diamond test in which two owners request the same exact Query key and assert one producer application plus two correct owner projections. Add the argument variant where the owners request the same coordinate with different arguments and assert two keys and two applications in the same Query OER. Adapt the resolver-only expansion fixture so Resolver02 can prove that depth 8 performs nine resolver invocations, each `fieldN` exact key is invoked once, and the client result remains unchanged.

Update observation and correctness evidence as part of this Resolver02 milestone. `ResolverObserver.onQueryFragmentPrepared` should still emit once per nonempty owner, but several owner IDs may carry the same `ObjectEngineResult`. `CorrectnessResolverObserver` and every `queryFragmentResults(...).single()` consumer must tolerate that shared identity. Validate the shared Query OER once against its unioned closed demand and validate each field-resolver task's projection separately; do not globally permit unexplained extra cells.

Implement the paired-OER lifecycle in the shared `DepthFirstOrchestrationTask` and `DepthFirstFieldResolverTask` machinery, but use Resolver02 as the first semantic target. The depth-first task must know the complete dependencies of both OERs before launch. Extend `SiblingDependencyLogic` so a resolver on the Query side depends on exact keys selected by both its object fragment and its Query fragment. Repeated exact dependencies must become dependency cycles rather than recursive root creation.

Revisit `DepthFirstQueryFringeOrderingContract`. It currently proves that an independent Query fragment uses its own dispatcher and cannot consume the enclosing fringe. Under singular sharing, require the one orchestration task to drain both prepared OERs according to their complete dependency order without stealing unrelated work from the containing result traversal.

Resolver01 shares the modified recursive task implementation but does not support nonempty Query fragments. Its full contract is a compatibility check: the optimistically allocated Query OER must remain undemanded and its ordinary object orchestration must remain unchanged.

### 3. Add Resolver03 selective successor demand — acceptance complete

Once Resolver02 passes, run the same shared Query-fragment contract through Resolver03. This should be a small semantic step because Resolver03 shares the recursive orchestration and field-task implementation.

The specific new concern is selective resolution. Successor demand must be computed for resolver invocations on both the object OER and the associated Query OER, including transitive Query-side invocations discovered during joint closure. Assert that combining those invocations does not lose required successor demand, activate undemanded output, or duplicate a resolver application. Retain Resolver03's selective output and one-shot witness expectations.

### 4. Carry the implementation to Resolver06–08 — acceptance complete

Next apply the proven recursive semantics to the explicit depth-first task/reactor family. Resolver06 is a compatibility baseline only: like Resolver01, it does not support nonempty Query fragments but shares the task machinery and must preserve the no-Query-demand path. Resolver07 should match Resolver02's complete-output Query-fragment behavior, and Resolver08 should match Resolver03's selective successor-demand behavior.

Use this stage to expose any assumption that worked only because Resolver02/03 executed recursively. The reactor must enqueue and drain work for both OERs through one orchestration task while retaining exact task identity, ordering, publication, and fringe boundaries.

### 5. Carry the implementation to Resolver21–23 — acceptance complete

Resolver21 remains the empty-fragment compatibility check for the shared coroutine orchestration lifecycle: its real Query context remains undemanded, is frozen with the object OER, and leaves existing publication behavior unchanged. Resolver22 establishes complete-output singular behavior and validates the parent-aware closure structure introduced in Step 1. Resolver23 adds selective successor demand and confirms that checker-free selective resolution behaves like Resolver03/08.

Resolver21–23 `CoroutineOrchestrationTask` now replaces the temporary undemanded Query context with real Query-side closed state, installs both sides' publications before dispatching any producer, and launches them from one dispatch. Each `GroundedFieldPublicationOccurrence` receives the prepared Query OER, and its field-resolver task uses the resolver occurrence's existing fragment information to materialize its own projection without creating or dispatching ordinary Query production.

Resolver22/23 retain the full `ParentFieldResolverContract` and add a directed interaction in which Query-rooted parent-lifted demand activates an ancestor resolver with a nonempty Query fragment. Query-rooted closure traverses the parent structure without leaking cells into the client result, ancestor/descendant re-entry does not freeze an OER early, and every producer runs once.

Resolver23's original resolver-only timeout reproduction now runs at depth 27 and completes within the ordinary timeout with 28 resolver invocations rather than 832,039; every exact `fieldN` key is invoked once. The coroutine-specific contract also proves that cancellation terminates one shared producer and every owner waiting on it.

Within joint closure for every family, route fragments according to the side on which the resolver occurrence was discovered: object-side object fragments remain on the object side, while object-side Query fragments and both fragments of Query-side resolvers enter the Query side. This is demand routing inside one orchestration lifecycle, not a second task kind.

### 6. Migrate Resolver26 only after Resolver01–23 are stable

Resolver26 adds symbolic keys, `FromObjectField`, `FromQueryField`, `FromProvider`, inclusion conditions, provider-read cycles, and request-owned concurrent execution. A direct first implementation there will conflate the core ownership change with its hardest binding cases.

Extend `ClosedConstructionDemandContext` to retain the object- and Query-rooted demand components prepared by the one orchestration task, including every contributing resolver occurrence, its inclusion alternatives, and both object- and Query-fragment provider reads. During closure, newly discovered Query-side resolver occurrences must contribute guarded object and Query construction selections back into the Query component.

Resolver21–23 currently keep shared-Query projection in the private `FieldResolutionLogic.materializeQueryFragment` helper. Revisit that seam during Resolver26 migration: share it only if Resolver26's symbolic selections, occurrence-local bindings, and materializer dependencies can remain explicit rather than being hidden behind a grounded-family abstraction.

Declare every binding needed by both closed demand components before dispatch. A `FromQueryField` binding reads from the shared Query OER but remains owned by one resolver occurrence; equal produced values do not merge variable instances. A `FromObjectField` binding used in a Query fragment still reads the owner's object OER. Preserve the current parallel readiness behavior so neither fragment is given an artificial global precedence.

The Resolver26 materializer can reserve symbolic cells before producer installation. Under the new invariant, scope preparation should nevertheless determine and install the complete symbolic key domain before freezing. Do not use reservability as permission for unbounded late scope growth.

### 7. Address references and nested objects deliberately

After root-scalar and ordinary owner sharing work, add tests for:

- a root-field-reference target that starts an independently rooted nested Query execution;
- a target with a nonempty declared Query fragment whose nested execution uses singular sharing internally;
- two emitted reference occurrences that remain distinct nested executions rather than coalescing through the caller's resolver Query scope;
- a cyclic direct reference tail that fails deterministically rather than running until timeout;
- a Query field returning an object whose descendant resolver declares a Query fragment;
- list elements producing separate containing occurrences;
- abstract result types whose concrete descendant resolvers contribute different Query demand.

Use those tests to enforce the settled scope boundaries described earlier. Do not infer correctness solely from the scalar regression, and do not classify tenant-provided list fanout as resolver Query-scope duplication.

### 8. Update documentation and then run broad validation

The following documents currently state the old independent-root policy and will need intentional revision:

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

## Remaining Questions For Steps 6–7

1. How should Resolver26's inclusion alternatives contribute guarded shared-scope demand without making an excluded owner wait on irrelevant production?
2. How should Resolver26 declare all occurrence-local `FromObjectField`, `FromQueryField`, and `FromProvider` bindings for both OERs before dispatch while retaining concurrent readiness?
3. Which exact runtime cycles remain after Resolver26's conservative registry validation, and what additional `CycleCheckState` evidence do they require under shared symbolic production?
4. Should root-field references keep their direct invocation path or reuse literal `ctx.query()` machinery once Step 7 adds the complete boundary suite? Either choice must preserve independently rooted semantics.
5. Does a recursive dependency crossing a Query root, a returned descendant object occurrence, and another associated Query OER require an ancestry guard beyond ordinary exact-cell cycle checking?
6. Can the grounded and symbolic orchestration tasks share object/Query demand-pair and per-OER preparation/launch interfaces while retaining their intentionally different installed-cell and binding strategies?

## Completion Boundary

The singular Query-scope work is complete when all resolver families that advertise declared Query-fragment support satisfy the revised shared contract; the checker-free depth-27 graph executes one resolver per exact field key; simple recursive Query dependencies fail deterministically rather than expanding; aliases, arguments, variables, references, failures, cancellation, and observer/correctness replay remain valid; documentation describes shared scopes rather than independent per-owner roots; and the full seeded qplan check passes.

Field-checker Query-scope migration is not part of that completion boundary. Preserve a clean resolver-only commit stack so the F4 field-checker branch can be rebased afterward. The planned integration should place resolver, field-checker, and type-checker Query demand in the same per-containing-occurrence scope using separate checked and unchecked components; make that an explicit follow-up rather than an accidental merge-conflict resolution.
