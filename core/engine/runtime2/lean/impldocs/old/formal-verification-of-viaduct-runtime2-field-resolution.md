# Formal Verification of Viaduct Runtime2 Field Resolution

**Coding-agent handoff and implementation plan**

**Status:** Initial project charter and implementation guidance  
**Primary focus:** Lean 4 — semantic correctness  
**Secondary, future focus:** TLA+ — structured concurrency  
**Repository:** [https://github.com/airbnb/viaduct](https://github.com/airbnb/viaduct)  
**Runtime2:** [https://github.com/airbnb/viaduct/tree/main/core/engine/runtime2](https://github.com/airbnb/viaduct/tree/main/core/engine/runtime2)

---

## 1. Project Objective

We are undertaking a formal verification effort for **Runtime2**, Viaduct's new field-resolution engine.

The primary objective is to establish, through machine-checked proofs, that Runtime2's field-resolution algorithms produce semantically correct results for all inputs satisfying their stated preconditions.

This is motivated by the complexity of Runtime2's algorithms. They handle numerous subtle interactions involving resolver dependencies, selection sets, variables, object occurrences, conditional inclusion, access checks, root-field references, and other Viaduct-specific mechanisms.

These algorithms have many corner cases, some of which are difficult to cover systematically with conventional testing. We believe semantic algorithm defects represent a particularly significant risk to the correctness of Viaduct in production.

Formal verification should provide stronger guarantees than testing alone.

A second important objective is to **improve the structure and clarity of the Kotlin implementation itself**. Formalization should expose unnecessary complexity, ambiguous invariants, implicit assumptions, and opportunities for cleaner abstractions.

The objective is not to produce a separate mathematical artifact that happens to resemble the implementation. It is to make Runtime2's correctness easier to establish, understand, and maintain.

### Guiding principles

1. Pursue incremental, meaningful, machine-checked correctness results rather than attempting one enormous proof.
2. Define correctness independently of the algorithms that establish it.
3. Focus initially on semantic correctness, not concurrency correctness.
4. Treat tenant-provided resolvers and checkers as well-behaved mathematical functions satisfying explicit contracts.
5. Keep Lean models reasonably close to the Kotlin implementation, while allowing either side to evolve toward a cleaner design.
6. Use auxiliary correctness certificates selectively, when they simplify independent validation.
7. Preserve existing Kotlin correctness tests and witnesses as valuable independent evidence.
8. Do not claim that proving a Lean model correct automatically proves the Kotlin implementation correct.

---

## 2. Background: Viaduct and Runtime2

Viaduct is an open-source, schema-first GraphQL server platform developed at Airbnb.

Its architecture differs from conventional GraphQL servers in several ways, notably its approach to modularity, resolver inputs, and field resolution.

Runtime2 is a replacement implementation of Viaduct's field-resolution machinery. It was developed with particular attention to explicit semantic modeling, testability, and correctness.

The relevant source tree is:

`core/engine/runtime2`

Important locations include:

| Location                                   | Purpose                                       |
| ------------------------------------------ | --------------------------------------------- |
| `src/main/kotlin/.../model`                | Executable semantic model and data structures |
| `src/main/kotlin/.../resolution`           | Production field-resolution algorithm         |
| `src/main/kotlin/.../resolution/framework` | Shared resolution machinery                   |
| `src/support/kotlin/.../correctresolution` | Correctness predicates and validation         |
| `src/support/kotlin/.../resolvers`         | Maintained reference resolver implementations |
| `impldocs/architecture`                    | Architecture and semantic documentation       |
| `impldocs/correctness`                     | Correctness claims and arguments              |
| `impldocs/testing`                         | Testing and validation strategy               |
| `tla`                                      | Existing TLA+ experiments and proofs          |

The complete package prefixes can be determined from the repository.

**Agents must inspect the current repository rather than relying on this document as an exhaustive or permanently current description of the code.**

In particular, read:

- `README.md`
- `impldocs/architecture/principles.md`
- `impldocs/architecture/model.md`
- `impldocs/architecture/resolution.md`
- `impldocs/testing/strategy.md`
- `impldocs/correctness/claims.md`

Read the relevant `AGENTS.md` files and follow their repository-specific instructions.

### 2.1 Field resolution is not GraphQL execution

This distinction is fundamental to the project.

We are verifying **Viaduct field resolution**, not end-to-end GraphQL query execution.

Field resolution is a Viaduct-specific algorithm that takes a resolver world, selections, and other relevant inputs and constructs a collection of engine results.

Those results are subsequently processed by GraphQL field completion to produce the GraphQL response.

Conceptually:

```text
GraphQL operation
       |
       v
Operation preparation and validation
       |
       v
+----------------------------------+
| Viaduct field resolution         |
|                                  |
|   Main OER tree                  |
|   Subsidiary Query OER trees     |
|                                  |
+----------------------------------+
       |
       v
GraphQL field completion
       |
       v
GraphQL response
```

The formal verification boundary is the field-resolution component.

In particular, we are **not** initially verifying:

- GraphQL parsing or validation.
- GraphQL field completion.
- Response serialization.
- GraphQL response formatting.
- End-to-end GraphQL execution correctness.

The correctness specification must concern the semantic output of field resolution, not the eventual GraphQL response.

### 2.2 Object Engine Results

Runtime2 represents field-resolution results using Object Engine Results (OERs) and related result structures.

A resolution produces a main OER tree corresponding to the initial Query selection, together with subsidiary Query-typed OER trees that represent results of Query fragments used by resolvers and checkers.

The relationships between these trees, their owners, and the resolver occurrences they support are important aspects of field-resolution semantics.

The result model must also account for nested objects, lists, nulls, errors, exact field keys, argument values, and the distinctions between structural occurrences.

The Lean model should preserve the semantic distinctions that matter for correctness without reproducing every Kotlin representation detail.

---

## 3. Overall Formal-Methods Strategy

We intend to use **Lean 4 and TLA+ together**, assigning them different responsibilities.

### 3.1 Lean: semantic correctness

Lean will be used to specify and prove properties of Runtime2's field-resolution algorithms.

The central question is:

> Given a valid resolver world and field-resolution input, does Runtime2 produce semantically correct OER results?

Lean is attractive because Runtime2's semantic algorithms involve:

- Recursive data structures.
- Tree transformations.
- Exact occurrence identity.
- Selection and demand relationships.
- Fixed-point computations.
- Dependency closure.
- Materialization.
- Algebraic properties of results.
- Inductive correctness arguments.

Lean's inductive types, structural induction, well-founded recursion, and theorem-proving facilities are well suited to these problems.

### 3.2 TLA+: structured-concurrency correctness

Runtime2 also contains sophisticated structured-concurrency machinery built on Kotlin coroutines.

This includes task ownership, nested execution, promise dependencies, cancellation, error propagation, and completion.

TLA+ is likely better suited to exploring the possible interleavings of these operations and establishing safety and conditional-liveness properties.

Examples include:

- Every claimed promise has an appropriate owner.
- Cancellation cannot leave an engine-owned publication permanently pending.
- Nested tasks obey their intended ownership boundaries.
- Promise dependencies do not introduce undetected engine deadlocks.
- Request completion implies the required work has reached quiescence.
- Failure and cancellation behave correctly under different task interleavings.

TLA+ and TLC are particularly useful for discovering counterexample schedules that are difficult to reproduce in conventional tests.

### 3.3 Relationship between the two

The long-term objective is to establish complementary guarantees:

**Lean:** The field-resolution algorithm computes semantically correct results.

**TLA+:** The concurrency protocol preserves the required execution, ownership, and lifecycle guarantees.

Eventually, these should support a compositional correctness argument.

However, the two projects need not be developed simultaneously.

**The immediate project is Lean semantic verification. TLA+ work is deferred.**

Existing TLA+ material can be consulted for prior reasoning and useful invariants, but it is not a required foundation for the new Lean model.

---

## 4. Scope and Assumptions of Semantic Verification

### 4.1 Verify Viaduct, not tenant code

Runtime2 invokes tenant-provided resolvers, variable providers, and checkers.

We do not intend to verify the implementations of these tenant functions.

Instead, they should be represented by mathematical relations or functions satisfying explicit contracts.

Relevant assumptions may include:

- Determinism.
- Schema conformance.
- Selective-output correctness.
- Cross-demand coherence.
- Correct handling of declared inputs.
- Termination where required by the theorem.

These assumptions must be stated precisely and should correspond to Runtime2's actual tenant-facing contracts.

The verification target is what **Viaduct does with these functions**, including constructing their inputs, interpreting their results, and producing the appropriate OER trees.

An assumption that tenant functions behave correctly must never silently imply that Viaduct supplied the correct inputs or handled their outputs correctly.

Those are Viaduct's obligations.

### 4.2 Semantic correctness versus execution correctness

We distinguish two categories of correctness properties.

**Semantic-result correctness** establishes that the resulting OER trees contain the correct values, selections, checker outcomes, dependencies, and other semantic relationships.

**Correct-for-the-right-reason properties** establish facts about the execution that produced those results, such as exact resolver application counts, supplied demand, actual invocation inputs, or scheduling.

The immediate Lean effort concentrates on semantic-result correctness.

However, these categories are not an absolute prohibition against using execution-derived information in a correctness predicate.

Sometimes an independently checkable claim about a computation makes result correctness much simpler to validate.

That leads to the treatment of certificates described below.

---

## 5. The Central Specification: `CorrectResolution`

Runtime2 already contains a `correctResolution` predicate and several subsidiary predicates.

The relevant implementation is under:

`src/support/kotlin/viaduct/engine/runtime2/correctresolution`

The existing predicate has evolved over time.

Its scope has expanded as Runtime2 acquired additional capabilities, including Query fragments, checkers, variables, and root-field references.

Some previously separate checks have also been fused for performance reasons because correctness validation was becoming expensive in the test suite.

Consequently, the current implementation is **not necessarily the ideal structure for a mathematical correctness specification**.

### 5.1 Define an idealized conjunction

An early objective is to develop a clean set of semantic predicates:

```latex
\[
\operatorname{CorrectResolution}(W,S,R,\ldots)
=
P_1 \land P_2 \land \cdots \land P_n
\]
```

Here:

- `\(W\)` represents the resolver world.
- `\(S\)` represents the initial selection forest.
- `\(R\)` represents the collection of completed resolution results.
- Additional arguments may represent independently checkable semantic claims needed by particular predicates.

The exact signature must emerge from the specification work.

Each `\(P_i\)` should express a meaningful, understandable property of a correct field-resolution result.

The predicates should be independently specified wherever practical, even when their eventual proofs share foundational lemmas.

### 5.2 Do not restate the algorithm

This is one of the most important requirements of the project.

**The correctness predicates must not simply recapitulate Runtime2's resolution algorithms.**

A proof that an algorithm satisfies a predicate that is essentially a restatement of the algorithm offers limited assurance.

Instead, predicates should capture claims about the resulting state that can be independently validated.

For example:

- Required selected values are present.
- Resolver outputs agree with their mathematical resolver relations.
- Required resolver input values are available.
- Variable values agree with their definitions.
- Checker results agree with their checker relations.
- Subsidiary Query OERs satisfy the semantic obligations of their associated fragments.

These are properties of the completed resolution, not descriptions of the order in which Runtime2 constructed it.

The checker may need to inspect the result structures and evaluate tenant mathematical relations, but it should not have to execute Viaduct's demand-closure, scheduling, or field-resolution algorithms again.

### 5.3 Permissiveness and minimality

The current `correctResolution` intentionally permits OERs to contain values beyond those strictly required by the original selections and resolver dependencies.

Minimality and exactness are separate concerns.

We should preserve that distinction unless careful semantic analysis establishes a reason to change it.

A correct result need not necessarily be the smallest possible result.

Likewise, a correct result need not prove that each resolver was invoked exactly once or that a particular selective demand was supplied.

Those properties can be important without belonging in the initial semantic-result specification.

---

## 6. Initial Predicate Catalog

The current Kotlin implementation provides a starting inventory, not a final specification.

The initial investigation should examine at least the following predicates and related machinery.

### 6.1 Root and type correctness

Starting point:

`RootedAndWellTyped.kt`

Establish that the main result has the appropriate Query root and satisfies the relevant structural and type assumptions.

Some invariants are currently established by result constructors rather than checked explicitly by `correctResolution`.

The Lean specification should identify these assumptions and determine which belong in valid-result types, preconditions, or explicit correctness predicates.

### 6.2 Selection conformance

Starting point:

`ConformsToSelections.kt`

Establish that the result contains the values required by the initial selection forest, accounting for:

- Concrete runtime types.
- Conditional inclusion.
- Selection merging.
- Exact keys and grounded arguments.
- Nested objects.
- Lists.
- Null and error stopping behavior.

Surplus values may be permitted.

This is a promising early proof target because it exercises recursive result structures without requiring the full resolver semantics.

### 6.3 Resolver-demand closure

Starting point:

`IsClosedUnderResolverDemand.kt`

Establish that the completed result contains the inputs required by applicable resolver and checker relations.

Relevant issues include:

- Direct and transitive dependencies.
- Source-owned versus resolver-owned fields.
- Argument-dependent resolver occurrences.
- Conditional selections.
- Object and Query fragments.
- Checker-origin demand.
- Symbolic variables and their grounding.
- Nested occurrences.

This is likely to be one of the most valuable semantic proof areas.

The idealized predicate should express closure as a mathematical property, not by repeating the implementation's closure algorithm.

### 6.4 Resolver-value conformance

Starting points:

`ConformsToResolvers.kt`  
`ResolverApplication.kt`

Establish that values in the OER trees agree with their semantic owners and the corresponding resolver relations.

This includes distinguishing values supplied by a source object from values produced by registered resolvers.

Resolver relations may be reapplied mathematically to establish expected values.

However, the specification should avoid depending on recorded execution details unless independently validated auxiliary claims are necessary.

### 6.5 Checked-selection correctness

Starting points:

`ConformsToCheckedSelections.kt`  
`CheckerApplication.kt`

Establish that field and type checks have the correct semantic outcomes.

Important distinctions include:

- Raw versus checked input materialization.
- Field versus type checker results.
- Checker applicability.
- Checker-defined input fragments.
- Access-denial and error behavior.
- Checker variable bindings.
- Associated Query results.

The exact checker-result equivalence relation must be taken from the existing semantic contract, not invented for the Lean model.

### 6.6 Subsidiary Query OER correctness

The output of field resolution includes subsidiary Query-rooted results used to supply resolver and checker Query fragments.

These must be validated in the appropriate semantic context.

The investigation should determine:

- Which subsidiary OERs are semantically required.
- How they are associated with their owners.
- Which parts of their contents must satisfy correctness predicates.
- Whether a subsidiary result is independently correct or must be judged relative to its owner.
- How nested subsidiary results are handled.

This is a potentially important shared foundation for multiple predicates.

### 6.7 Root-field-reference correctness

Root-field references allow a resolver output to refer symbolically to another root-field resolver.

References may form chains before producing an ordinary value.

The final OER generally contains the resolved value rather than the intermediate reference chain.

An independent correctness checker therefore needs a way to justify that value.

This may be an appropriate case for a finite, independently validated reference-chain certificate.

However, the certificate design must be evaluated when this predicate is tackled, rather than fixed in advance.

### 6.8 Additional predicates

The catalog is not closed.

Agents should identify semantic properties that are missing, insufficiently explicit, or inappropriately fused into other checks.

Particular attention should be paid to whether correctness depends on assumptions that are currently implicit in constructors, adapters, or test fixtures.

Any proposed new predicate should include a concrete explanation of the incorrect results it would reject.

---

---

## 7. Certificates and Auxiliary Correctness Evidence

Some correctness predicates may be difficult to validate independently using only the completed OER trees and the resolver world. In these cases, the resolution algorithm may produce additional information—a *certificate*—that permits simpler independent validation.

**Certificates are optional, predicate-specific techniques, not a foundational requirement of the formal model.** Do not begin by designing a universal `ResolutionCertificate` type. Start with the catalog of idealized correctness predicates, then decide case by case whether auxiliary evidence simplifies independent validation or proof.

### Guiding principle

A correctness predicate should express a semantic property of completed resolution, not restate the algorithm. Avoid a checker that reimplements demand propagation, variable instantiation, dependency scheduling, or reference resolution. Instead, prefer independently checkable claims about completed OER trees.

Certificates are untrusted. Two proof obligations apply whenever one is used:

1. **Certificate soundness:** An accepted certificate establishes the intended semantic correctness property.
2. **Certificate production:** The modeled runtime2 algorithm produces a certificate that the independent checker accepts.

Neither obligation alone is sufficient.

### Example: variable bindings

Runtime2 might supply a map from variable occurrences to their claimed values. The checker can validate each entry against its variable definition, resolver occurrence, relevant field argument or OER value, and mathematical variable-provider relation. For `FromObjectField`, it can follow the declared selection path through the completed OER and compare the value with the certificate.

The checker need not reconstruct the complete dependency graph or repeat fragment instantiation and binding scheduling. But it must establish **soundness** (each certified binding is correct) and **completeness** (every binding needed to justify relevant resolver applications is represented). An alternative is to derive values directly from the world and OERs with a simpler occurrence-local mathematical operation. Compare both before introducing a certificate.

### Example: root-field-reference chains

Suppose A produces a reference to B, B a reference to C, and C an ordinary value published in the main OER. The completed OER usually omits the intermediate references. Reconstructing them by repeatedly evaluating relations risks recreating the resolution algorithm.

A finite candidate reference-chain certificate can instead supply the intermediate references and invocation contexts. The checker independently validates that each resolver relation produces the claimed reference or value; each target and argument is valid; associated Query OERs satisfy their obligations; and the final value matches the published OER value. It validates a proposed chain rather than discovering one.

Do not assume the existing `RootFieldReferenceInvocationObservation` is the right certificate. It is an execution observation containing invocation identity and supplied demand, some of which may be unnecessary for semantic correctness.

### Certificates versus execution witnesses

Semantic certificates justify values; execution witnesses establish what actually happened. A valid variable certificate need not prove a variable was evaluated exactly once. A valid reference-chain certificate need not prove the runtime followed precisely those invocations in that order. Some existing observations may serve both purposes, but the semantic content should be separated from execution history.

### Incremental decision process

For each predicate:

1. First express it directly using the world, selections, completed OERs, and tenant relations.
2. Identify reconstruction that complicates independent validation.
3. Consider a small, locally checkable certificate.
4. Compare certificate-based and certificate-free formulations.
5. If adopted, prove certificate-checker soundness.
6. Prove the modeled algorithm constructs an accepted certificate.
7. Consider simplifying Kotlin's semantic model to eliminate unnecessary evidence.

The goal is not a general proof-carrying execution framework. It is a collection of **simple, independently meaningful predicates**, with auxiliary evidence only where helpful. Certificates should serve the correctness specification, not dictate its architecture.

---

## 8. Milestones and Incremental Execution Plan

**Do not formalize all of runtime2 before proving anything.** Each milestone should produce a concrete, reviewable result. The ordering is provisional: adjust it as dependencies become clearer, while preserving incremental machine-checked progress.

### Milestone 1 — Audit and idealize `CorrectResolution`

**Objective:** Recover a clear modular specification of semantic field-resolution correctness.

Inspect `core/engine/runtime2/src/support/kotlin/viaduct/engine/runtime2/correctresolution/`, the model, documentation, and maintained resolver families. For every existing predicate, document its property, inputs, OER structures, tenant assumptions, shared dependencies, and any entanglement with execution evidence. Identify omissions and redundancies caused by historical scope expansion or performance-driven fusion. Pay special attention to subsidiary Query OERs, variables, checkers, and reference chains.

Propose an idealized conjunction:

```latex
\[
\operatorname{CorrectResolution}(W,S,R)=\bigwedge_{i=1}^{n}P_i(W,S,R)
\]
```

The notation is conceptual: predicates may take independently validated auxiliary evidence.

**Deliverables:** Predicate catalog, assumptions, relationship to existing Kotlin predicates, proof-dependency graph, and recommended ordering.

**Completion criterion:** A coherent specification that does not require the checker to reproduce resolution. This milestone is design and analysis, not a large Kotlin refactoring or complete Lean implementation.

### Milestone 2 — Establish Lean project and minimal semantic foundation

**Objective:** Create a working Lean 4 project expressing the first predicates.

Introduce only necessary semantic types: schemas, field definitions, engine input/output/result values, objects and lists, selections and inclusion, exact keys and arguments, occurrence paths, resolver worlds and mathematical tenant relations, and main/subsidiary Query OERs as needed. Preserve semantic distinctions without mechanically translating every Kotlin representation. Establish conventions for modules, theorem names, assumptions, and automated checks.

**Deliverables:** Buildable Lean project, minimal definitions, examples, and reproducible proof command.

**Completion criterion:** The first predicate can be stated, and representative valid/invalid examples checked. Avoid building the entire model in advance.

### Milestone 3 — Prove first substantive predicate

**Objective:** Establish the first machine-checked algorithm-correctness theorem.

Start with `rootedAndWellTyped`, then `conformsToSelections`. Cover concrete-type normalization, inclusion, exact-key lookup, recursive objects/lists, null/error stopping, and required selected values. Restrict the initial domain if needed, but state restrictions explicitly.

**Deliverables:** Lean predicate, modeled construction operation, lemmas, meaningful theorem, and examples.

**Completion criterion:** Proof checks without `sorry` or unproved placeholders in its trusted dependencies and is not tautological. Record any Kotlin/model discrepancies.

### Milestone 4 — Prove resolver-demand closure

**Objective:** Prove completed OERs contain required activated resolver inputs.

Begin with a restricted world (possibly excluding variables, checkers, Query fragments). Establish direct and transitive dependency coverage, source-owned versus registered-resolver-owned fields, nested occurrences, and correct distinction between argumented keys. Define closure mathematically rather than repeating the fixed-point implementation.

**Deliverables:** Demand-closure predicate, algorithm model, lemmas, machine-checked theorems.

**Completion criterion:** Closure established for a meaningful resolver-world domain.

### Milestone 5 — Prove resolver-value conformance

**Objective:** Show completed values agree with their semantic sources and resolver relations.

Begin with deterministic, non-selective functions and simple object inputs. Extend to passive and active values, nested objects/lists, materialized inputs, errors, and selective coherence. Exclude invocation counts, scheduling, and original supplied-demand exactness; use independently checked auxiliary evidence if helpful.

**Deliverables:** Value-conformance predicate, tenant contract, and proofs.

**Completion criterion:** Compose selection, demand, and value theorems into a meaningful restricted `CorrectResolution` result.

### Milestone 6 — Subsidiary Query OERs and variables

**Objective:** Extend proofs to runtime2's multi-tree semantics.

Model main and subsidiary Query OERs, their ownership associations, resolver occurrences, and variable definitions/bindings from arguments, object fields, Query fields, and providers. Validate bindings independently rather than replaying variable-discovery machinery. Consider certificates case by case. Preserve occurrence identity even when values coincide.

**Deliverables:** Expanded model, binding and Query-result predicates, machine-checked theorems.

**Completion criterion:** Correct main and subsidiary OER results for a substantial domain involving Query fragments and variables.

### Milestone 7 — Checkers and advanced features

**Objective:** Incorporate remaining major semantic features.

Address field/type checkers, raw versus checked inputs, denial/error precedence, conditional inclusion, reference chains, `@parent` backedges, symbolic grounding, and complex nested occurrences. Prioritize by production risk and proof dependencies rather than this list's order. Keep predicate proofs modular.

**Deliverables:** Feature-specific predicates, theorems, supporting lemmas, and coverage limitations.

**Completion criterion:** Intended production field-resolution semantics covered subject to explicit assumptions and exclusions.

### Milestone 8 — Kotlin correspondence

**Objective:** Strengthen the connection between verified Lean algorithms and production runtime2.

Begin opportunistically earlier, then systematically compare executable Lean and Kotlin semantic functions on generated inputs, reuse existing property-test generators, turn discrepancies into regressions, refactor Kotlin where it improves semantic clarity, and establish explicit refinement relationships. Do not confuse test agreement with a universal refinement proof.

**Deliverables:** Cross-implementation checks, correspondence assumptions, targeted Kotlin improvements, regressions.

**Completion criterion:** Substantial independently checkable evidence that production runtime2 implements verified semantics, with gaps documented.

### Milestone 9 — Compose the semantic correctness theorem

**Objective:** Compose the individual theorems into the strongest practical overall result.

```latex
\[
\operatorname{ValidWorld}(W)\land\operatorname{ValidInput}(W,S)
\Longrightarrow
\operatorname{CorrectResolution}(W,S,\operatorname{Resolve}(W,S))
\]
```

Specify auxiliary evidence, feature-domain restrictions, and termination assumptions precisely. The composition should rely primarily on previously proved conjuncts, not a new monolithic argument.

**Deliverables:** Machine-checked composition theorem, trusted-assumption inventory, and coverage report.

**Completion criterion:** Lean checks the theorem without unproved algorithm-specific correctness assumptions.

---

## 9. Execution Guidelines for Coding Agents

The milestones do not authorize implementing the whole roadmap in one pass. For each milestone, inspect current runtime2 sources and documentation, develop a narrow plan, identify assumptions, and implement only the definitions and proofs required for the immediate objective.

Distinguish:

- **Definitions:** Intended mathematical semantics.
- **Assumptions:** Valid-world, tenant, and input contracts.
- **Theorems:** Properties actually machine-checked.
- **Correspondence claims:** Relationships between Lean and Kotlin behavior.
- **Open obligations:** Properties not yet proved or assumptions not discharged.

A theorem that succeeds only because its conclusion is hidden in an assumption is not progress. Audit for circularity, unnecessarily strong assumptions, and tautological correctness definitions. An executable Lean model agreeing with Kotlin on examples is useful but not itself a proof.

### Kotlin changes

Kotlin is not immutable. Propose or implement appropriately scoped improvements—extracting pure transformations, separating fused predicates, clarifying invariants, or simplifying representations—when they make correctness easier to reason about. Preserve intended runtime behavior and validate changes against existing contracts. Do not weaken semantics merely to simplify a proof.

### Progress reporting

At each milestone, report:

1. Formally defined predicates.
2. Machine-checked theorems.
3. Assumptions and restrictions.
4. Kotlin algorithms modeled and correspondence quality.
5. Remaining correctness obligations.
6. Proposed Kotlin or specification changes.
7. Recommended next proof target.

**Every milestone should leave runtime2's semantic correctness better understood and, wherever possible, more rigorously established.**