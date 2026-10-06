# Resolver Construction TLA+ Project

This directory is a standalone, disposable formal-modeling project. It records a finite resolver-construction calculus and its proof experiments; Runtime2 production code, Gradle tasks, and implementation documentation do not depend on it. Removing the project must not change engine behavior. Work here should not be treated as part of ordinary Runtime2 development unless it is explicitly in scope.

## Scope

The modules model finite, occurrence-scoped construction properties corresponding to Resolver01 through Resolver03. One `ResolverCore` instance denotes one concrete OER object occurrence. Keys are specialized to that occurrence's runtime object type and retain exact argument tuples. Recursive objects and list elements are separate instances, so structurally equal values and repeated node IDs are not coalesced.

The model covers least resolver-demand closure, dependency-first construction, occurrence-indexed result trees, demand projection, prefix and final materialization, returned-result judgments, and guarded Resolver03 producer completeness. It models the primary result OER only.

The project does not model field-relative execution variables, nonempty resolver Query fragments or their occurrence-specific Query OERs, directives, `@parent`, checkers, lazy values, cyclic resolver demand, mutations, execution epochs, JVM scheduling, caching, batching, side effects, or cross-tree coalescing. It is not the executable semantic definition of Runtime2 and does not prove the Kotlin implementation correct.

## Module And Proof Inventory

The project is organized into three layers. A `*Proof.tla` module supplies TLAPS arguments for its base layer; a `*MC.tla` module and matching `.cfg` supply a bounded TLC instance. Four proof-bearing modules—`DependencyOrder.tla`, `Resolver01.tla`, `Resolver02.tla`, and `Resolver03.tla`—predate the `*Proof.tla` naming convention and must also be passed to TLAPM explicitly.

### Local Demand And Dependency Order

- `ResolverCore.tla` defines least exact-key resolver-demand closure and a dependency-first fold for one concrete OER occurrence. `ResolverCoreProof.tla` proves closure, input availability, unique construction positions, safety, termination, and completed local correctness.
- `Resolver01.tla` proves the empty-object-fragment stage. `Resolver02.tla` proves exact direct object-fragment closure. `Resolver03.tla` proves guarded producer completeness when registry extension covers every exact guarded requirement token.
- `DependencyOrder.tla` models the Kahn-style worklist corresponding to sibling dependency ordering. It proves that dependencies precede each removed key, no key is reapplied, and construction terminates under the finite ready-member assumption. `DependencyOrderMC` checks a four-key graph with transitive dependencies.
- `Resolver02MC` checks local closure with transitive sibling demand and an argument-preserving bridge-shaped dependency. `Resolver03MC` adds guarded nested requirements.

Resolver03 requirement tokens are opaque proof abbreviations. Their intended extraction preserves containing-object paths, concrete-type guards, exact keys, and argument tuples; injectivity of that extraction is a refinement obligation, not a checked result.

### Result Trees And Occurrence Folds

- `ResultTree.tla` gives finite OER object, cell, list-position, and resolver-observation occurrences an extensional carrier and states the five modeled primary-result `correctResolution` conjuncts. `ResultTreeProof.tla` proves least reachability, finiteness, and equivalence between local and whole-tree judgments.
- `TreeConstruction.tla` indexes least exact-key demand closure by every reachable object occurrence. `TreeConstructionProof.tla` lifts completed Resolver01 and Resolver02 folds to whole-tree selection and resolver-demand conformance.
- `OccurrenceFolds.tla` takes the product of every reachable occurrence's construction order. `OccurrenceFoldsProof.tla` proves type safety, strict progress, weak-fairness termination, and simultaneous completion with each occurrence's built keys equal to its closed demand. `OccurrenceFoldsMC` checks two interleaved occurrences.

An explicit `OutputAlignment` premise relates terminal fold keys to returned-result cells. The model does not derive that relation from Kotlin object construction.

### Projection, Materialization, And Application

- `Projection.tla` defines a finite observation semantics for `snipToDemand`. `ProjectionProof.tla` proves completeness, soundness, monotonicity, behavioral-boundary stopping, and agreement on overlapping demand.
- `ValueConstruction.tla` combines projection, the resolver-output typename contract, and tree construction. `ValueConstructionProof.tla` derives conditional Resolver01 and Resolver02 `CorrectResolution` theorems.
- `Materialization.tla` identifies a resolver application with one dependency-first work item. `MaterializationProof.tla` proves that prefix-materialized input equals final-result input for the modeled exact cells.
- `ReturnedResult.tla` and `ReturnedResultProof.tla` separate structural returned-result assumptions from projection coverage. `ReturnedResultCoveredProof.tla` supplies coverage for the Resolver01 and Resolver02 result theorem.
- `ResolverApplication.tla` and `ResolverApplicationProof.tla` connect deterministic resolver functions to prefix and final materialization. `Resolver01And02ApplicationProof.tla` supplies their projection premise.
- `Resolver03Projection.tla` and `Resolver03ProjectionProof.tla` derive projection coverage from exact direct and guarded nested requirements. `Resolver03Application.tla` and `Resolver03ApplicationProof.tla` compose coverage, materialization, result judgments, and product-fold completion.
- `ResolverApplicationMC`, `Resolver03ProjectionMC`, `ReturnedResultMC`, `MaterializationMC`, `ProjectionMC`, `TreeConstructionMC`, `ResultTreeMC`, and `ValueConstructionMC` check bounded instances of their respective layers. `Resolver03ApplicationMC` checks the composed terminal judgment with a guarded nested requirement.

The complete checked-in module matrix is the validation target. TLC models are finite counterexample searches; their state counts are useful diagnostics but are not proofs of the world assumptions.

## Machine-Checked Results

Under each module's explicit finite-world assumptions, TLAPS establishes:

1. `ClosedDemand` is the least set containing external demand and closed under exact direct resolver demand.
2. Every demanded key appears exactly once in a valid dependency-first construction order.
3. Every resolver input dependency precedes its application.
4. The finite fold terminates and gives each activated resolver key one unique sequence position per OER occurrence.
5. Resolver03 supplied producer demand contains every guarded requirement represented by each activated nested occurrence's exact predecessor demand.
6. The least root-reachable finite result-tree carrier turns recursive local judgments into the five modeled primary-result `correctResolution` conjuncts.
7. Completed occurrence-indexed Resolver01 and Resolver02 folds establish whole-tree selection and resolver-demand conformance.
8. Projecting one raw resolver output retains exactly demanded passive observations, stops at behavioral boundaries, and agrees on every observation shared by two demands.
9. Under explicit observation alignment and projection coverage, Resolver01 and Resolver02 value construction plus their completed folds imply all five modeled primary-result `correctResolution` conjuncts.
10. Every finite reachable object-occurrence fold can run in one interleaved product machine whose terminal built keys equal that occurrence's least closed demand.
11. Dependency-first prefix materialization and final-result materialization select the same exact input cells, so a deterministic resolver function yields the same raw output at construction time and in the final judgment.
12. Resolver03 direct predecessor demand and guarded successor demand derive the projection-coverage premise, and its occurrence product fold terminates in a result satisfying every modeled primary-result `correctResolution` conjunct.

TLC exhaustively checks the checked-in bounded worlds for transitive sibling demand, guarded nested requirements, occurrence-indexed construction, projection, materialization, and composed Resolver03 application. These runs provide counterexample-finding evidence distinct from the symbolic TLAPS results.

## Assumptions And Proof Boundary

The proof fixes a finite canonical world and assumes:

- exact concrete-key dependency discovery is sound for every reached argument tuple;
- the resolver dependency graph is acyclic and its construction order is a duplicate-free dependency-first enumeration;
- resolver fragments, values, selection trees, lists, and the reachable exact-key universe are finite;
- Resolver01 has empty exact object fragments;
- Resolver03 registry extension preserves every exact path, type guard, field, and argument tuple represented by a requirement token;
- resolver functions are deterministic, defined on every materialized input in scope, schema-conformant, and contain every demanded passive output before a behavioral boundary;
- finite object, cell, list-position, exact-demand, and resolver-observation atoms faithfully extract the corresponding structural Kotlin values and relations;
- each modeled result observation aligns with the exact passive observation copied by `snipToDemand` from the same raw output used by the correctness judgment; and
- terminal product-fold built keys align with the exact cells present in the returned Kotlin OER.

The project proves the internal resolver-construction calculus over these carriers. It does not derive structural extraction of schemas, selection forests, values, objects, lists, materialized inputs, demand, object/list union, or resolver-value comparison. It also does not derive returned cells from fold transitions or establish the assumed alignment between terminal fold state and a returned Kotlin result.

The distinction matters most for one-shot language. TLAPS proves one mathematical application position per exact key and concrete OER occurrence. It does not prove JVM invocation counts, scheduling behavior, caching behavior, batching behavior, or side-effect cardinality. The composed Resolver01 through Resolver03 theorems must not be quoted as unconditional proofs of the Kotlin functions.

## Toolchain

The Runtime2 `mise.toml` pins Java Corretto 21.0.4.7.1, TLA+ Tools 1.7.4, and TLAPS 1.5.0 from build `202210041448`. The checked-in TLAPS package is configured for Linux x64. Install the pinned tools with `mise install` from `core/engine/runtime2`.

TLA+ Tools 1.7.4 parses and TLC evaluates `RECURSIVE` operators, but TLAPS 1.5.0 rejects them with `Recursive operator definitions are not supported`. The proof modules therefore use intersection-defined least finite closed sets and explicit finite state machines. TLC enumeration of `SUBSET` is exponential, so bounded carriers must remain small.

Run TLAPM modules serially because `.tlacache` is shared by the working directory. Concurrent TLC runs require distinct absolute `-metadir` paths. Terminal models use `CHECK_DEADLOCK FALSE`. The installed proof methods are Zenon, Isabelle, SMT/Z3, and PTL/LS4; CVC4, Yices, veriT, and SPASS are not installed.

State constant world predicates as TLC invariants so an invalid fixture fails visibly. A state constraint alone can prune every state and make a run vacuous. TLAPS 1.5.0 also commonly requires explicit carrier-membership lemmas and explicit unfolding of both named `INSTANCE` operators when transferring a judgment between instances.

## Validation

Run the complete matrix from `core/engine/runtime2`. Parsing every module catches syntax and semantic errors. The proof list includes every module containing a top-level `THEOREM` or `LEMMA`, including the four files without a `Proof` suffix. TLC then checks every bounded model:

```sh
for module in tla/*.tla; do
  mise run tla:parse -- "$module"
done

proof_modules="
  tla/DependencyOrder.tla
  tla/Resolver01.tla
  tla/Resolver02.tla
  tla/Resolver03.tla
  tla/MaterializationProof.tla
  tla/OccurrenceFoldsProof.tla
  tla/ProjectionProof.tla
  tla/Resolver01And02ApplicationProof.tla
  tla/Resolver03ApplicationProof.tla
  tla/Resolver03ProjectionProof.tla
  tla/ResolverApplicationProof.tla
  tla/ResolverCoreProof.tla
  tla/ResultTreeProof.tla
  tla/ReturnedResultCoveredProof.tla
  tla/ReturnedResultProof.tla
  tla/TreeConstructionProof.tla
  tla/ValueConstructionProof.tla
"
for module in $proof_modules; do
  mise run tla:prove -- "$module"
done

for module in tla/*MC.tla; do
  mise run tla:check -- "$module"
done
```

The loops are intentionally serial. `tla:check` changes to the specification directory and normalizes path-valued options so standard-library imports resolve. Treat a successful run as evidence about the checked-in files, not as a timeless module count or a Kotlin refinement result.

## Refinement Backlog

Internal proof validity is strong within the declared finite carriers. Refinement to Kotlin structure and execution remains weak, and the bounded TLC corpus needs more adversarial worlds. The central problem is that relations the implementation should derive are still caller-supplied premises; a broken constructor can choose atomic maps that satisfy the current theorems.

### Gaps

- **Returned cells:** `OccurrenceFolds.tla` maps abstract work to stipulated `WorkCell` values instead of constructing cells through structural equivalents of `resolveKey`, recursive passive-value resolution, and write-once OER updates.
- **Complete observations:** `ResultTree.tla` accepts resolver observations instead of deriving every scalar, null, shape, list-position, and passive-descendant observation from returned cells and raw resolver results.
- **Fragment demand:** `OperationDemand`, `ResolverDemand`, and related maps are inputs rather than extractions from structural fragments, keys, type guards, and registry membership.
- **Materialization:** `CellValue` is not required to equal the corresponding returned cell, and the application model abstracts away missing keys, resolver partiality, projection failure, and invalid construction.
- **Identity:** opaque atoms do not prove that unequal Kotlin keys, arguments, containing-object paths, concrete guards, list positions, and OER occurrences remain unequal after extraction.
- **Classification and attribution:** resolver cells, argument-error cells, typename cells, behavioral boundaries, and producers are caller-classified instead of derived from keys, registry membership, output guarantees, and transitions.
- **One application:** the local core proves one sequence position per exact key, but the composed Resolver03 result does not expose that fact as a final theorem.
- **Projection facts:** the current relation needs demand tokens for some scalar and shape facts that Kotlin preserves independently of nested demand.

### Repair Sequence

1. Add adversarial TLC worlds for wrong returned cells, omitted observations, nonempty fragments mapped to empty demand, returned-cell and `CellValue` disagreement, collapsed arguments or list occurrences, wrong semantic classifications, and a false one-application fact.
2. Introduce structural carriers or proved extraction maps for schemas, object occurrences, exact keys, selections, type guards, fragments, values, cells, and list positions. Derive registry classification and demand, and prove preservation of arguments, paths, guards, and occurrence identity.
3. Model returned OERs as monotonic transition state: each cell moves from absent to one value, child OER identity remains stable, and terminal cells are produced by the fold rather than supplied by alignment assumptions.
4. Derive complete observations by traversing raw outputs and returned cells, and tie producer attribution and projection to the same structures.
5. State separate theorems for termination, demand closure, input availability, projection coverage, returned-result correctness, and one application per exact cell occurrence. Final premises may constrain valid worlds and resolver functions but must not choose supplied demand, returned cells, actual observations, or application counts.

Every new world predicate needs a negative fixture. Record explored TLC state counts, treat unexpectedly small counts as possible over-constraint, and require a mutation that breaks the modeled algorithm while preserving carrier validity. Preserve the passing baseline while adding any adversarial refinement model.
