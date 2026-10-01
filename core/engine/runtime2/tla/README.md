# Resolver TLA+ Proof Baseline

## Scope

These modules prove finite, occurrence-scoped construction properties for `semantics.resolvers.resolver01` through `semantics.resolvers.resolver03`. One `ResolverCore` instance denotes one concrete OER object occurrence. Its keys are specialized to that occurrence's runtime object type and retain exact argument tuples. Recursive objects and list elements are separate instances, so structurally equal values and repeated node IDs are not coalesced. The modules model the primary result OER only; nonempty resolver Query fragments and the occurrence-specific Query OERs retained by resolver observations are outside this baseline.

The proof is factored at the same boundaries as the Kotlin model:

- `ResolverCore.tla` defines least resolver-demand closure and the dependency-first fold; `ResolverCoreProof.tla` proves closure, input availability, one position per applied key, safety, termination, and completed-result correctness.
- `Resolver01.tla` proves the empty-object-fragment stage.
- `Resolver02.tla` proves exact direct object-fragment closure and lifts local closure over finite OER occurrences.
- `Resolver03.tla` proves guarded producer completeness when registry extension covers every exact guarded requirement token.
- `DependencyOrder.tla` models the Kahn-style dependency worklist and proves that dependencies complete before each step, no key is reapplied, and construction terminates.
- `ResultTree.tla` gives finite OER object, cell, list-position, and resolver-observation occurrences an extensional carrier and states the five modeled primary-result conjuncts of `correctResolution`.
- `TreeConstruction.tla` indexes least exact-key demand closure by every reachable object occurrence and lifts completed Resolver01/02 folds to whole-tree selection and resolver-demand conformance.
- `Projection.tla` proves a finite observation semantics for `snipToDemand`, including behavioral boundaries and overlap coherence.
- `ValueConstruction.tla` combines projection and the resolver-output typename contract with tree construction to derive conditional Resolver01 and Resolver02 `CorrectResolution` theorems.
- `OccurrenceFolds.tla` takes the product of every reachable occurrence's construction order and proves that arbitrary fair interleaving terminates with all local folds complete.
- `Materialization.tla` identifies each resolver application with one dependency-first work item and proves that its prefix-materialized input equals its final-result input.
- `ReturnedResult.tla` and `ResolverApplication.tla` separate structural result assumptions from projection coverage, then derive the result conjuncts once coverage is supplied.
- `Resolver03Projection.tla` derives projection coverage from direct and exact guarded nested requirements, while `Resolver03Application.tla` composes that result with materialization and product-fold completion.

Resolver03 requirement tokens are opaque proof abbreviations. Their intended extraction preserves containing-object paths, concrete-type guards, exact keys, and argument tuples, but the current modules do not prove that extraction is injective. Unequal Kotlin structures remaining unequal in the finite carriers is an outstanding refinement obligation.

The TLA+ modules retain established operator names such as `ExtendedByOccurrence`; in Kotlin-facing vocabulary, those operators represent predecessor demand lifted into successor demand.

## Machine-Checked Results

TLAPS proves the following under each module's explicit world assumptions:

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
11. Dependency-first prefix materialization and final-result materialization select the same exact input cells, so a deterministic resolver function yields the same raw output at construction time and in the final correctness judgment.
12. Resolver03 direct predecessor demand and guarded successor demand derive the projection-coverage premise, and its occurrence product fold terminates in a result satisfying every modeled primary-result `correctResolution` conjunct.

TLC exhaustively checks small models containing transitive sibling demand, an argument-preserving bridge-shaped dependency, guarded nested requirements, occurrence-indexed construction, projection, materialization, and composed Resolver03 application.

The complete checked-in module matrix is the validation target. Treat a successful run as evidence about the current files rather than preserving dated module counts here.

## Assumptions

The proof fixes a finite canonical world and assumes:

- exact concrete-key dependency discovery is sound for every reached argument tuple;
- the resolver dependency graph is acyclic and its construction order is a duplicate-free dependency-first enumeration;
- resolver fragments, values, selection trees, lists, and the reachable exact-key universe are finite;
- Resolver01's scoped model has empty exact object fragments;
- Resolver03 registry extension preserves every exact path, type guard, field, and argument tuple represented by a requirement token;
- resolver functions are deterministic, defined on every materialized input in scope, schema-conformant, and contain every demanded passive output before a behavioral boundary;
- finite object, cell, list-position, exact-demand, and resolver-observation atoms faithfully extract the corresponding structural Kotlin values and relations;
- each modeled result observation is aligned with the exact passive observation copied by `snipToDemand` from the same raw resolver output used by the correctness judgment;
- terminal product-fold built keys align with the exact cells present in the returned Kotlin OER.

The modules exclude field-relative execution variables, resolver Query fragments and their independent Query OERs, directives, `@parent`, checkers, lazy values, cyclic resolver demand, mutations and execution epochs, and cross-tree coalescing.

The Kotlin registry's depth-first provider/use branch-order invariant is pre-reasoning infrastructure outside this proof baseline. No current theorem proves the branch extractor, its least fixed point, or the sufficiency of branch stratification for a future variable-aware construction.

## Proof Boundary

This is a machine-checked proof of the resolver construction calculus and a finite extensional model of the five primary-result `correctResolution` conjuncts, not a complete refinement proof from the structural Kotlin carriers or the occurrence-specific query-value judgment. Result-tree recursion, occurrence-indexed demand closure, simultaneous product-fold completion, projection coherence, prefix/final materialization equality, guarded projection coverage, and the resolver-output typename contract are represented and proved.

Structural extraction of schemas, selection forests, objects, lists, materialized resolver inputs, observed demand, object/list union, and resolver-value comparison into those atoms remains explicit, as does alignment between terminal product-fold built keys and returned Kotlin OER cells. The composed Resolver01 through Resolver03 theorems therefore must not be quoted as unconditional proofs of the Kotlin functions.

The distinction matters most for one-shot language. TLAPS proves one mathematical resolver application position per exact key and concrete OER occurrence. It does not assert JVM side-effect counts, scheduling, caching, batching, or runtime invocation behavior.

## Toolchain Constraints

TLA+ Tools 1.7.4 parses and TLC evaluates `RECURSIVE` operators, but TLAPS 1.5.0 rejects them with `Recursive operator definitions are not supported`. These modules therefore use intersection-defined least finite closed sets and explicit finite state machines. Keep model carriers small because TLC enumeration of `SUBSET` is exponential.

Run TLAPM modules serially because `.tlacache` is shared by the working directory. Give concurrent TLC runs distinct `-metadir` paths and use `CHECK_DEADLOCK FALSE` for terminal models. The installed and tested proof methods are Zenon, Isabelle, SMT/Z3, and PTL/LS4; CVC4, Yices, veriT, and SPASS are not installed.

State constant world predicates as TLC invariants so an invalid fixture fails visibly; using only a state constraint can prune every state and make a run vacuous. TLAPS 1.5.0 also commonly requires explicit carrier-membership lemmas and explicit unfolding of both named `INSTANCE` operators when transferring a judgment between instances.

## Validation

From `core/engine/runtime2`, parse every module, prove every proof module serially, and run every TLC model:

```sh
for module in tla/*.tla; do mise run tla:parse -- "$module"; done
for module in tla/*Proof.tla; do mise run tla:prove -- "$module"; done
for module in tla/*MC.tla; do mise run tla:check -- "$module"; done
```

TLC commands may run concurrently only when each receives a unique absolute `-metadir`; the loop above runs them serially. `tla:check` changes to the specification directory and normalizes path-valued options so TLAPS standard-library imports resolve.

[`refinement-backlog.md`](refinement-backlog.md) records the structural extraction and Kotlin-alignment work that remains outside this proof baseline.
