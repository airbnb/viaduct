# Inclusion-Condition Validation

Production Resolution implements `@skip` and `@include` through a pipeline of representations and transformations rather than a single Boolean check at resolver invocation:

```text
source directives
    → lowered template condition
    → occurrence-specific instantiated condition
    → inherited and down-pushed condition
    → disjunction across equal keys
    → pre-freeze fixed-point propagation
    → runtime activation
```

Source occurrences inherit conditions from enclosing fragments, so an occurrence's initial condition can already be a conjunction. Resolver fragments are instantiated at request-local resolver occurrences. When demand is closed, structurally equal selection keys merge: alternative occurrences combine disjunctively, while each occurrence's condition is pushed conjunctively into its descendants. The compact DAG does not distribute these operators. Contributions discovered during later closure iterations must propagate through object-fragment and provider prerequisites exactly once until the demand reaches a fixed point. The resulting condition determines whether a reserved OER cell activates and whether tenant resolver code runs.

## Current Alpha Guarantees

Production inclusion tests live in `src/test/kotlin/viaduct/engine/runtime2/resolution/inclusion/`. `InclusionCombinationTest` exhaustively enumerates the bounded Boolean vectors defined by its T1, T2, and T3 fixtures and checks:

- seed alternatives that combine `@include` and `@skip` for T3;
- T3 alternatives combined with nested conditions for T2;
- direct and transitive T3 alternatives combined for T1;
- expected resolver and provider application counts;
- exact input aliases, active and inactive cells, and result fingerprints;
- completed results against `correctResolution`; and
- explicit direct-only, indirect-only, and combined activation witnesses.

Provider-path handling preserves the same condition semantics. `FromObjectField` exclusion is local to the defining object or Query fragment. Template definitions retain response paths, and instantiated reads carry exact keys and effective inclusion conditions. Guard compilation pushes each occurrence's condition into its descendants before disjoining alternatives, preserving repeated-path correlation. Excluded aliases bind null before reading an OER cell. Registry cycle validation includes source-path inclusion dependencies, and assembly rejects statically excluded provider paths. The independent binding oracle collects defining fragments rather than trusting compiled guards, and generated inclusion cases are deterministic.

Focused lowering, registry, binding, and resolution tests cover these rules and source directive semantics. Together with the combination tests, they provide strong deterministic evidence that the production pipeline preserves conjunction within an occurrence, disjunction across equal keys, descendant correlation, and alternatives discovered across closure iterations for the cases represented by the fixtures.

`InclusionConditionSharedEvaluationTest` bounds binding-read counts for shared-prefix graphs at depths 12, 64, and 256 in both ordinary evaluators and checks that memoized results do not cross invocations. `InclusionConditionContradictionTest` checks nested contradiction pruning against independent Boolean formulas, preserves left-guard binding order, and exercises wide and shared graphs. `InclusionConditionActivationTest` checks that contradicted nested branches neither propagate failed bindings nor await unresolved ones while retaining failures from required left guards. `InclusionConditionResolutionRegressionTest` verifies the production consequence: an excluded cell remains inactive rather than publishing an activation error, invokes no tenant resolver, and leaves an unrelated field running. These tests establish the explicit-guard simplification boundary, not complete contradiction detection for arbitrary Boolean graphs.

The contradiction tests also bound requirement-map scans for a growing conjunction chain whose guards share one conflicting requirement and each add a distinct nonconflicting requirement. Pruning caches rewrites by the guard's projection onto variables with opposing polarities, so those guards reuse prefix rewrites. This establishes linear requirement-scanning work for that graph family, not a general bound for graphs with many distinct conflicting guards or a production query witness for that family.

`InclusionConditionFailureOrderTest` covers two failure-sensitive cases: `((z AND x) OR x) AND z` with false `z` must exclude without reading failed or pending `x`, while `(x OR y) AND z` with true `x` and false `z` must still expose a failure in `y`. Flat guarded alternatives are deduplicated by their requirement maps after ignoring guaranteed guard entries, retaining the original first alternative's read order. Ordinary evaluation revisits a rejected successful disjunction in an exhaustive mode; each mode shares completed-node results within the invocation. The shared-evaluation tests bound reads for rejected shared products at depths 12, 64, and 256, so inspecting remaining alternatives does not reintroduce Cartesian expansion.

`InclusionConditionLegacyOutcomeTest` independently specifies ordered normalized requirement maps for ten guarded and product fixtures, including guards separated from duplicate alternatives by another conjunction. It compares both ordinary evaluators over every true/false/failed binding vector and production activation over every true/false/failed/pending vector. A ready successful alternative wins; absent success, a pending alternative delays the outcome, then a failed alternative wins over false. The duplicate-guard activation tests include failed and unresolved bindings, and the production-cell regression verifies exclusion, no activation error or resolver invocation, and continued execution of an unrelated field. This establishes compatibility for the represented graph families, not complete normalized-DNF failure-order equivalence for arbitrary Boolean graphs.

These guarantees are alpha guarantees, not a complete independent validation of the condition algebra. The combination fixtures use direct truth-table oracles for their expected activations and values, but some broader correctness assertions reuse production condition machinery as described below.

## Validation Boundary

The `correctResolution` predicates remain useful for detecting failures to reserve, activate, or materialize a value that the shared selection model considers required. They are not fully independent evidence that condition combination itself is correct.

In particular, `conformsToSelections` calls the same `SelectionForest.merge()` operation used by production demand processing. That operation uses the same `InclusionCondition.and()`, `anyOf()`, and `guardedBy()` machinery to disjoin equal-key occurrences and push conditions into descendants. `conformsToSelections` then uses the ordinary `InclusionCondition` evaluator. `isClosedUnderResolverDemand`, which checks that an activated standard resolver has its required object-fragment input, delegates its selection check to `conformsToSelectionsAt`.

A defect in merging or down-pushing conditions can therefore affect Resolution and these validation predicates in the same way, allowing them to agree on the same wrong result. Direct source-lowering tests can catch a local error such as interpreting `@skip` as `@include`; they do not independently establish that otherwise-correct instantiated conditions remain intact through merged, iterative demand closure.

## Under-Inclusion And Over-Inclusion

Inclusion correctness has two directions:

- **Under-inclusion:** a selection should be included under some condition, but the combined condition or execution excludes it.
- **Over-inclusion:** a selection should be excluded, but the combined condition or execution includes it.

Both directions matter. The existing `correctResolution` relation deliberately permits extra OER values, so it is principally an under-inclusion judgment. That scope does not make over-inclusion harmless: a condition that is too broad can execute tenant code unnecessarily and can have observable effects even when the final selected value is otherwise valid.

For expected effective condition `E` and actual merged condition `M`, the obligations are:

```text
no under-inclusion: E ⇒ M
no over-inclusion:  M ⇒ E
exact composition:  E ⇔ M
```

The current independent evidence is strongest for under-inclusion in the bounded combination fixtures. Runtime2 does not yet have a general independent judgment for either implication across arbitrary generated worlds.

## Shared-Cell Masking

Multiple resolver occurrences can contribute demand to the same OER cell. Suppose occurrence A should demand `foo`, but a defect drops A's contribution. If occurrence B independently demands the same concrete `foo` key, the shared cell can still activate. An end-result predicate observes that A's required value exists but cannot tell that A failed to contribute its condition.

The final OER may be extensionally correct in that execution, but the case is weak evidence for A's condition propagation. The latent defect appears when A occurs without B. A validation mechanism must therefore state whether it checks final extensional inclusion, contribution-level condition preservation, or a corpus containing executions in which masking cannot occur.

## Validation Alternatives

### Independent Final-OER Predicate

An independent predicate could retain source selection occurrences before they are lowered into `InclusionCondition`, implement `@skip` and `@include` directly, and calculate which concrete OER occurrences should activate. Directives and inherited conditions would combine conjunctively within one source occurrence; contributions to the same concrete key would combine disjunctively only after each branch's condition had been pushed into its descendants.

This approach provides an end-to-end judgment, but it must independently reconstruct raw directives, occurrence-local variable bindings, abstract-type specialization, grounded arguments, request and Query-fragment roots, passive fields, and provider-only infrastructure demand. It must inspect inactive or missing cells as well as active occurrences. It also cannot identify which contributor activated a shared cell without provenance, counterfactual executions, or unique-contributor fixtures.

### Occurrence Ledger And Unmasked Witnesses

The existing correctness traversals can support a ledger keyed by request-local root and exact OER path. Its expected side would collect source-derived inclusion claims; its actual side would record active and inactive reserved cells. Claims for the same cell would combine disjunctively.

Aggregation alone still permits shared-cell masking. A generated-test contract can compensate by requiring unmasked witnesses: conditionally included object-fragment occurrences whose concrete key has only one possible contributor in that execution. Shared-demand cases remain necessary to exercise disjunction, but they are not the only evidence for contribution preservation.

This is the smallest extension of the current infrastructure. Its assurance is coverage-based: it demonstrates the relevant behavior in required generated witnesses rather than validating every contribution in a shared-demand execution.

### Contribution Provenance

A more precise design carries traceable claims alongside each normalized inclusion condition. Every instantiated resolver-fragment selection contributes its origin, target demand occurrence, and effective condition. The effective condition includes the incoming resolver alternative and every inherited and local fragment condition:

```text
effective contribution
    = incoming resolver alternative
      AND inherited fragment conditions
      AND local selection condition
```

Down-pushing strengthens each claim conjunctively. Merging equal keys unions the claims and combines their conditions disjunctively. An alternative discovered in a later closure iteration contributes another claim that must pass through the same fixed-point computation.

Let `C₁ … Cₙ` be the effective conditions contributed to one merged selection, let `E = C₁ OR … OR Cₙ`, and let `M` be the merged condition produced by demand closure. Under-inclusion can be checked as `E ⇒ M`, equivalently `Cᵢ ⇒ M` for every contribution. The check must use logical implication rather than evaluation under one execution's bindings: if A and B are both true, a defective condition containing only B evaluates true, while `A ⇒ B` exposes that A can no longer force inclusion.

Provenance should travel beside `InclusionCondition`, not participate in its semantic equality. Resolution captures additive occurrence contributions before entering merged demand and uses structural condition equality to deduplicate fixed-point expansion; distinct origins must not turn structurally equal conditions into duplicate work. Their implication checker must be independent of the representation being validated; bounded enumeration of the relevant Boolean assignments is one suitable test-only technique.

This design addresses masking at the formula level but adds bookkeeping throughout demand closure. It still needs a runtime assertion that a merged condition evaluating true activates its cell.

## Remaining Decision

The unresolved design choice is the strength of the independent claim required for alpha hardening:

| Approach | Primary claim | Shared-cell masking | Cost |
| --- | --- | --- | --- |
| Independent final-OER predicate | Independently expected source selections appear in execution | Not by itself | Broad end-to-end reconstruction |
| Occurrence ledger plus unmasked witnesses | The generated corpus contains direct evidence for contribution propagation | Avoided for required witnesses | Smaller, coverage-based extension |
| Contribution provenance | Every contribution remains sufficient to force the merged condition | Addressed at formula level | Precise, bookkeeping-heavy extension |

Contribution provenance is the direct choice when the required claim is correctness of the demand-closure algebra itself. An occurrence ledger with mandatory unmasked witnesses is the smaller choice when the immediate goal is stronger generated evidence without an independent algebra. Whichever boundary is selected must keep expected contributions independent from merged demand, cover both under- and over-inclusion explicitly, include mutations that drop a disjunct or a later closure alternative or over-strengthen a descendant, and retain a separate runtime activation check.
