# Validating Inclusion Conditions in Resolution

This document records a validation gap in Resolution and the candidate approaches we considered for closing it. It is a handoff, not a settled design. The immediate goal is to state the problem precisely enough that we can resume the work without first reconstructing the argument.

## Problem statement

Resolution’s support for `@skip` and `@include` is not a single Boolean check at the point where a field resolver runs. It is a pipeline of representations and transformations.

Source-level directives are first lowered into `InclusionCondition` values. A source occurrence can inherit conditions from enclosing fragments, so its initial condition may already be a conjunction. Resolver fragments are then instantiated at particular resolver occurrences, giving their variables request-local identities. As demand is closed, structurally equal selection keys are merged: conditions from alternative occurrences are combined disjunctively, while each occurrence’s condition is pushed into that occurrence’s descendants conjunctively. Resolution must also propagate alternatives discovered in subsequent closure iterations through object-fragment and provider prerequisites until demand reaches a fixed point. The resulting combined condition is eventually evaluated to decide whether a reserved OER cell activates and whether tenant resolver code runs.

Each individual rule is understandable. Their composition is complicated:

```text
source directives
    → lowered template condition
    → occurrence-specific instantiated condition
    → inherited/down-pushed condition
    → disjunction across equal keys
    → iterative pre-freeze fixed-point propagation
    → runtime activation
```

The present validation is not as independent from this pipeline as it ultimately needs to be. `conformsToSelections` calls the same `SelectionForest.merge()` operation used by production demand processing. That operation uses the same `InclusionCondition.and()`, `anyOf()`, and `guardedBy()` machinery to disjoin equal-key occurrences and push conditions into descendants. `conformsToSelections` then evaluates the resulting condition through the ordinary `InclusionCondition` evaluator. `isClosedUnderResolverDemand`, which checks that an activated standard resolver has its required object-fragment input, delegates its selection check to `conformsToSelectionsAt`.

These predicates remain useful. In particular, they can detect Resolution-specific failures to reserve, activate, or materialize a value that the shared selection model considers required. They do not, however, independently establish that the shared condition-combination model is correct. A defect in merging or down-pushing conditions can affect both resolution and validation in the same way, allowing them to agree on the same wrong result.

A simple parser error—such as lowering `@skip` with the meaning of `@include`—illustrates the coupling, but it is not the main concern here. The source-level meanings are small enough to cover with direct unit tests. The more consequential risk is that otherwise-correct instantiated conditions are lost, strengthened, or propagated to the wrong descendants as Resolution builds and closes combined demand.

### Under-inclusion and over-inclusion are separate obligations

It is useful to divide inclusion correctness in two:

- **Under-inclusion:** a selection should be included under some condition, but the combined condition or resulting execution excludes it.
- **Over-inclusion:** a selection should be excluded, but the combined condition or resulting execution includes it.

Both are correctness concerns. They need not be solved by the same validation mechanism.

The existing `correctResolution` relation is deliberately permissive about extra OER values, and the work discussed here has principally been concerned with under-inclusion. A future implementation and its documentation should say this explicitly: validation of directive-driven over-inclusion is deferred, not declared harmless. In particular, extra execution caused by a condition that is too broad may have correctness consequences beyond absolute result minimality, even if the final selected value is otherwise valid.

For the present scope, the desired implication is:

```text
independently expected inclusion ⇒ actual combined condition or execution includes it
```

We do not yet require the converse.

### “Correct for the wrong reasons”

Object fragments make under-inclusion validation especially subtle because multiple resolver occurrences can contribute demand to the same OER.

Suppose resolver occurrence A should demand `foo`, but a bug drops A’s contribution. If resolver occurrence B independently demands the same concrete `foo` key, the shared OER still contains an active `foo` cell. An end-result predicate can observe that A’s required value exists, but cannot determine that A failed to contribute the demand.

This is not necessarily an incorrect final OER. It is nevertheless a weak test of A’s condition propagation: a nearby demand has masked the defect that would appear as soon as A occurs without B. Any validation strategy should be explicit about whether it proves final extensional correctness, contribution-level condition correctness, or merely provides generated examples in which masking cannot occur.

## Idea 1: An independent final-OER inclusion predicate

The first idea was a `conformsToInclusion` predicate over the completed execution result. It would preserve or receive source-level selection occurrences before they are lowered into `InclusionCondition`, independently implement the meanings of `@skip` and `@include`, and calculate which concrete OER occurrences should be active.

For a single source occurrence, directives and inherited conditions are conjunctive. When multiple source occurrences resolve to the same concrete key, their contributions are disjunctive. Conditions must remain attached to their individual occurrence branches while they are pushed into descendants; only contributions to the same descendant key may then be combined.

The predicate would be one-sided for the current scope: every independently expected inclusion must correspond to an active cell. It would not reject unexpected active cells.

This approach has strong end-to-end appeal, but it requires a substantial independent reconstruction. The witness must retain raw directives, resolve occurrence-local variable bindings, specialize abstract selections to runtime types, ground arguments, distinguish request and Query-fragment roots, and account for passive fields and provider-only infrastructure demand. It must also examine negatively activated or missing cells; iterating only active OER occurrences cannot detect a value that should have activated but did not.

Most importantly, a final OER still does not record which contributor caused a shared cell to activate. The predicate can correctly conclude that the final result is not under-included while still missing the “correct for the wrong reasons” scenario. Avoiding that masking requires either contribution provenance, counterfactual executions, or generated cases with a unique possible contributor.

## Idea 2: Validate demanded occurrences and require unmasked witnesses

The second idea stays closer to the existing correctness and witness infrastructure.

The current traversals already provide several pieces:

- `conformsToResolvers` and `isClosedUnderResolverDemand` reconstruct source ownership, distinguishing standard resolver work from passively source-supplied fields.
- The registered-resolver occurrence traversal preserves exact occurrence paths and descends only through activated cells.
- The activation-count traversal observes both activated and negatively activated reserved cells.
- Query-root witness logic includes the primary result root and retained Query-fragment roots.
- `conformsToSelections` detects a missing selection that its condition considers included.

These pieces could be factored into a richer occurrence ledger rather than adding another traversal from scratch. The expected side would collect inclusion claims by exact request-local root and OER path; the actual side would collect activation state for both active and inactive reserved cells. Multiple claims for the same cell would be combined disjunctively.

This improves diagnostics and can provide a useful one-sided end-result check, but aggregation alone does not solve masking. If A and B both can demand the same cell, the active cell satisfies the aggregate expectation even when A’s contribution was lost.

One way to make this approach meaningful without introducing internal provenance is to require **unmasked witnesses** in the property corpus. An unmasked witness is a conditionally included object-fragment occurrence whose exact concrete key has one possible demand contributor in that execution. If its condition is true, the cell can be active only if that contribution reached demand closure. Shared-demand cases should still be generated to exercise disjunction, but they should not be the property suite’s only evidence for under-inclusion.

This is probably the smallest extension, but its assurance is coverage-based rather than universal. It shows that important interactions occur without masking somewhere in the generated corpus; it does not prove contribution preservation in every shared-demand case.

## Idea 3: Preserve contribution provenance through condition composition

The third idea validates the condition algebra directly, before asking what happened in the final OER.

Each instantiated resolver-fragment selection would contribute a traceable inclusion claim. A claim would identify its originating resolver occurrence and fragment selection, its target demand occurrence, and the effective condition sufficient for that contribution. The effective condition includes the incoming activation alternative as well as conditions inherited along the fragment path:

```text
effective contribution
    = incoming resolver alternative
      AND inherited fragment conditions
      AND local selection condition
```

Down-pushing strengthens each individual claim conjunctively. Merging equal keys unions their claims and combines their semantic conditions disjunctively. An alternative discovered in a subsequent closure iteration adds another claim that must propagate through the same fixed-point computation.

Let `C₁ … Cₙ` be the effective conditions contributed to one merged selection and let `M` be the combined condition produced by demand closure. Define `E = C₁ OR … OR Cₙ`. The two correctness directions are then:

```text
no under-inclusion: E ⇒ M
no over-inclusion:  M ⇒ E
exact composition:  E ⇔ M
```

For the current scope, validation checks only `E ⇒ M`. Equivalently, every individual contribution must imply the combined condition:

```text
for every Cᵢ: Cᵢ ⇒ M
```

This check must be logical implication, not evaluation under only the bindings observed in one execution. If A and B both happen to be true, a combined condition containing only B evaluates to true and masks the lost A contribution. In contrast, `A ⇒ B` will generally be false, showing that A cannot independently force inclusion.

The provenance should probably travel alongside the existing normalized `InclusionCondition`, rather than becoming part of its semantic equality. Resolution uses normalized alternatives to deduplicate fixed-point expansion. Making otherwise equal conditions distinct because they have different origins could cause duplicate work or interfere with convergence.

A conceptual representation might be:

```kotlin
data class TracedInclusionCondition(
    val condition: InclusionCondition,
    val claims: Set<InclusionClaim>,
)
```

There are two independence requirements. Claims must be created from the instantiated fragment contribution before it enters merged demand; reconstructing them from the merged result would be circular. The implication check should also avoid depending on the normalization behavior being validated—bounded enumeration of relevant Boolean assignments is one possible test-only implementation.

This approach addresses “correct for the wrong reasons” directly at the formula level and cleanly separates under- from over-inclusion. It is also the most bookkeeping-heavy option. It would still need a thin execution assertion that a combined condition which evaluates true actually activates its cell; formula composition alone cannot establish that the runtime honored the formula.

## Where this leaves the design

The three ideas validate different boundaries:

| Approach                                  | Primary claim                                                            | Handles shared-cell masking?    | Character of the work            |
| ----------------------------------------- | ------------------------------------------------------------------------ | ------------------------------- | -------------------------------- |
| Independent final-OER predicate           | Expected source selections appear in execution                           | Not by itself                   | Broad, end-to-end reconstruction |
| Occurrence ledger plus unmasked witnesses | Generated executions contain direct evidence of under-inclusion behavior | For the required witness subset | Smaller, coverage-based          |
| Traceable condition composition           | Every contribution remains sufficient to force the combined condition    | Yes, at the formula level       | Precise, bookkeeping-heavy       |

A later design should first decide which claim we need from the property suite. If the concern is principally the complex algebra of demand closure, traceable condition composition is the most direct formulation. If we want a smaller improvement now, unmasked object-fragment witnesses may provide useful assurance without attempting a complete independent model.

Regardless of the chosen approach, the resumed work should:

1. Document directive-driven over-inclusion as a known correctness obligation deferred from the present under-inclusion scope.
2. Keep expected contributions independent from the merged demand being validated.
3. Exercise mutations that drop a disjunct, fail to propagate an alternative discovered in a subsequent closure iteration, or make a descendant condition too restrictive.
4. Retain a separate runtime check that positively evaluated combined conditions activate their cells.
