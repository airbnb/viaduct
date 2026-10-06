# Runtime2 Research Provenance

> **Historical evidence.** This document preserves the findings, rejected alternatives, validation records, and source lineage that informed Runtime2. It does not describe current implementation status or current test commands. Use the [Runtime2 overview](../../README.md) and the linked architecture and testing documents for maintained guidance.

## Established Findings

Production investigation, focused counterexamples, and failed prototype designs established the following constraints:

1. Demand rooted at one `QueryPlan` occurrence can miss a sibling contribution that later converges on the same memoized producer.
2. Unioning all predictions eventually associated with an OER can hide an under-supplied producing application.
3. A registration barrier proves only that currently known contributors have arrived; running a producer can reveal another contributor.
4. Lazy concrete-type plans and cycle backedges may be absent from the plan index visible at the initial execution site.
5. Selection traversal type and variable-provider target are separate dimensions.
6. Source field-resolver and node-resolver ownership roots differ; fixture composition preserves that boundary by normalizing node values to `Query.node` references whose concrete-type-bearing IDs dispatch to node resolvers.
7. Static paths and schema coordinates do not identify runtime object occurrences, list positions, concrete types, argument tuples, or execution epochs.
8. Alias-free internal demand is not automatically a valid tenant-visible GraphQL fragment.
9. Broad random campaigns can miss a decisive counterexample or validate a flawed oracle.
10. Variable identity belongs to its defining resolver occurrence; nested variables must be instantiated one activated demand layer at a time.
11. Production engine input data, resolver output data, OER leaf values, and `EngineObjectData.Sync` overload Kotlin `String` for GraphQL String, ID, and enum values; tenant boundaries temporarily project IDs and enums to `GlobalID<T>` or generated Kotlin enum classes and lower them back to strings before returning to the engine.

The central consequence is producer-specific: every producer-owned value later consumed from one resolver-bearing occurrence must be covered by the demand supplied to that occurrence's producing application. A correct final union, cache hit, widened result, or second materialization is weaker evidence. The maintained form of this conclusion is [One-Shot Correctness Is Producer-Specific](../architecture/principles.md#one-shot-correctness-is-producer-specific).

## Alternatives Considered

### Final-Result Or Global-Union Validation

Validating only the final OER, or unioning every demand prediction that eventually reaches it, can show extensional completeness while hiding that a selective producer ran before one contribution arrived. These approaches were rejected as the primary one-shot correctness argument. Final-result validation remains useful when paired with exact application and producer-input witnesses.

### Registration Barriers

A barrier over currently registered consumers does not establish that registration is complete: executing a producer can discover another concrete type, provider value, reference target, or resolver occurrence. A barrier is sound only when the accepted feature domain supplies a separate closed-world argument.

### Ungrounded-Key Policies

Argument-bearing fields admit three broad policies. Execution can wait for every potentially coalescing key to ground, which gives true one-shot coalescing but requires a tractable progress rule and sufficiently strong cycle exclusion. It can speculatively widen successor demand for keys that might later coalesce, but then supplied demand depends on timing and over-selection. Or it can preserve symbolic values and variable-instance identity, accepting that distinct symbolic keys may later ground equally and invoke the same field resolver more than once. Production Resolution uses the third policy; the [architectural principles](../architecture/principles.md#choose-ungrounded-key-semantics-explicitly) record its maintained semantics.

### Coverage Trees

The MAT and `KeyTree` work represented runtime demand and coverage as typed trees of exact OER keys. Union combines demand, difference exposes missing coverage, and paths preserve concrete types, arguments, and list positions. Those ideas remain useful for coverage and diagnostics, but a coverage tree does not by itself encode consumer provenance, producer ownership, scheduling prerequisites, raw-versus-checked reads, target scope, guarded alternatives, or producing-application identity. MAT could fetch missing coverage after demand arrived; one-shot Resolution must justify complete in-scope demand before the selective application.

### Broad Random Testing Alone

Large generated campaigns found important interaction failures, but their distributions also missed decisive structural witnesses. Runtime2 therefore combines focused counterexamples, feature contracts, independent correctness judgments, exact application witnesses, mutation tests, and directed generated campaigns. The maintained evidence model is in the [testing strategy](../testing/strategy.md).

## Correctness Obligations Derived From The Research

### Producer Completeness

All in-scope demand targeting one resolver-bearing occurrence is accounted for before its selective producer runs. Work that cannot satisfy this relation must be conservatively covered, assigned a distinct occurrence or epoch, or excluded.

### Dependency Discovery

Every resolver, provider, concrete-type step, or other prerequisite that execution may require is represented before dispatch. Runtime activation may choose among bounded alternatives; it must not silently introduce an unbounded dependency for an already-applied producer.

### Identity Agreement

Aggregation identity agrees with actual result construction. Multiple paths to one exact cell converge, while separate objects, list positions, argument tuples, and epochs remain separate even when entity IDs or values agree.

### Ownership Soundness

Demand supplied to a producer remains within that producer's output ownership apart from explicit engine bridges. Traversal stops at behavioral boundaries and attributes successor work to the successor producer.

### Monotonic Safety

Each exact cell and binding has at most one writer and one value, and each resolver-bearing occurrence has at most one producing application under the resolver family's declared key semantics. Published parent structure remains stable while descendants gain cells.

### Termination And Liveness

Demand closure and dependency ordering terminate over the accepted finite domain. Execution progress additionally assumes that invoked tenant resolvers, checkers, and variable-provider callbacks return or throw. Under that assumption, required claimed units complete successfully or exceptionally so dependents are released; missing writers and engine-created deadlocks remain defects. The [failure-isolation principle](../architecture/principles.md#isolate-tenant-failure-without-stranding-work) permits an operation to wait indefinitely on nonterminating tenant work, including work whose output becomes unnecessary after another error. It does not require prompt completion through demand retraction, and request-wide abort is not an acceptable fallback for local tenant errors.

### Concurrency

Correct aggregation does not require global barriers across unrelated object or list occurrences. Independent ready work remains concurrent, and compatible underlying work may still be batched beneath distinct semantic occurrence identities.

## Hard-Case Evidence

| Area | Evidence retained | Maintained treatment |
| --- | --- | --- |
| Converging demand | Independently reached selections can require unequal demand from one producer, and final union can mask an early application. | [Architectural principles](../architecture/principles.md#one-shot-correctness-is-producer-specific) and [testing strategy](../testing/strategy.md#observations-and-exact-witnesses) |
| Runtime `FromObjectField` providers | Structural paths are known before execution, but values and exact consumer keys appear only after provider cells complete. The [production census](./from-object-field-census.md) records observed source shapes. | [Semantic model](../architecture/model.md#variables-and-keys) and [Resolution design](../architecture/resolution.md#binding-declaration) |
| Query re-entry and ancestor or `@parent` targets | Targets outside ordinary descendant traversal require occurrence-specific scope, root identity, and ancestry. | [Resolution design](../architecture/resolution.md) and [Engine API integration](../integration/engine-api.md#nested-query-execution) |
| Abstract recursion and cycle backedges | Concrete alternatives can be lazy, and legal recursion requires guarded dependencies plus exact ancestor context. | [Resolution design](../architecture/resolution.md#construction-demand-closure) and [testing Resolution](../testing/resolution.md) |
| Lists and repeated IDs | Every list position is a separate result occurrence even when IDs, coordinates, or values repeat. | [Result Occurrence Is Identity](../architecture/principles.md#result-occurrence-is-identity) |
| Aliases, directives, and fragments | Internal normalized demand, response identity, independently resolved Query values, and tenant-visible syntax are different domains. | [Keep Semantic Domains Distinct](../architecture/principles.md#keep-semantic-domains-distinct) and [inclusion validation](../correctness/inclusion-validation.md) |
| Checkers and execution epochs | Raw and checked reads differ; work must not be coalesced across checker ownership or ordering boundaries. | [Access checks](../architecture/access-checks.md) and [Resolution design](../architecture/resolution.md#field-checks) |

These hard cases constrain carrier and algorithm changes without implying that every feature is part of the current alpha surface. Current support and exclusions belong to the [Runtime2 overview](../../README.md) and [Engine API integration](../integration/engine-api.md#supported-alpha-surface).

## Production Scalar Carrier Evidence

Production Viaduct has no engine-level nominal value type for GraphQL ID or enum members. GraphQL Java coercion produces strings, tenant argument conversion projects those strings to tenant-facing `GlobalID<T>` or generated enum values when required, tenant resolver return conversion lowers those values back to strings, `FieldResolutionResult.engineResult` stores the resulting leaf unchanged, and `EngineObjectData.Sync` exposes the same string. Consequently an ID, GraphQL String, and enum member with the same spelling are indistinguishable inside production engine input and output data without schema context.

Runtime2's carrier model follows this production representation for `EngineInputData` and `EngineOutputData`, but not for `EngineResult`. The result domain uses structural `EngineIDResult` values and canonical `ViaductSchema.EnumValue` definitions so IDs, strings, enum types, and same-named members of distinct enum types remain distinguishable. Schema-directed adapters wrap output strings when publishing results and unwrap result values when materializing resolver-visible data. This is a compatibility conversion, not an assertion that production's overloaded representation is the desired endpoint. The maintained carrier boundary is documented in the [semantic model](../architecture/model.md#carrier-boundary).

## Selected Historical Validation Records

These records demonstrate the scale and shape of completed investigations. They are not current acceptance commands, minimum coverage promises, or substitutes for the maintained [testing guide](../testing/guide.md).

| Investigation | Recorded evidence | Durable lesson |
| --- | --- | --- |
| Singular associated Query OER | Seeded 10,000-case campaigns passed for Resolver03, Resolver08, Resolver23, and production Resolution when it was still named Resolver26; the final tree also passed its seeded full check with 1,910 regular tests and 226 skips. | Query-root sharing requires exact scope and owner-local projection witnesses across every fragment-capable family. |
| Cross-family generated stress | Thirteen suite/profile runs completed 111,250 generated cases, including four 10,000-case deep-stress suites, five standalone 10,000-case profiles, directed parent and root-reference campaigns, and a 100-worker production Resolution run that observed all workers and 71 concurrent continuations. | Aggregate case counts matter only when paired with per-case correctness, exact application accounting, and required activation signatures. |
| Runtime field checks | Five bounded profiles, 2,500 success cases, 2,500 denial cases, and the unchanged 10,000-case deep stress passed at the recorded seeds; the investigation retained the depth-two denial scaling limit separately from implementation repairs. | Input reductions must remain distinct from semantic repairs and must not weaken exactness or coverage requirements. See the [F5 profile archive](./profiles/2026-09-27-f5-field-checks/README.md). |
| Runtime type checks | Six success, denial, and mixed field/type profiles passed 15,000 cases at seed `424242`; a mixed profile also passed 2,500 cases at seed `20260929` on 100 Resolution threads with explicit list, associated-Query, owner-outcome, and checked-dependency witnesses. | Generated checker campaigns must join declarations to root- and path-qualified applications and reject coverage observed at the wrong occurrence. |

## Research Evaluation Questions

The research used the following questions to distinguish semantic claims from attractive implementation sketches. They remain useful when evaluating a substantial change, but they are not an unowned feature backlog:

1. What exact feature scope receives a one-shot producer-completeness guarantee?
2. How is out-of-scope demand rejected, conservatively covered, or isolated?
3. Which demand paths converge on one exact OER cell?
4. Which dependencies are bounded statically, and which values bind only at runtime?
5. How are provider targets, concrete alternatives, Query or ancestor targets, and execution epochs represented?
6. How are aliases, directives, fragments, internal demand, resolver-visible demand, and completion artifacts kept distinct?
7. How are cycles classified and diagnosed?
8. Why must every unfinished valid state have ready work?
9. How does every failure complete or cancel its dependents?
10. What schedule-independence evidence justifies concurrent execution?
11. What evidence compares the model with actual Viaduct execution?
12. Which measurements expose over-selection, repeated work, missing writers, and fallback behavior?

## Source Provenance

These sources preserve the research trail. Proposals and implementation reviews are evidence, not current implementation instructions.

### Research And Proposals

- [Query Execution Revisited session index](https://docs.google.com/document/d/1L8oGjvvcSMZNkY6ooL78l0K_92f84SLUuGFdf3S3cA8/edit?tab=t.0)
- [RFC-254: ctx-selections and alternatives](https://docs.google.com/document/d/1aXmtEPIQx0xD35kBYyePb2sqnzOjI5GQSB-5STkxHVk/edit)
- [RFC-246: Selective vs Non-Selective Resolvers](https://docs.google.com/document/d/1rr1KSMe4okF3C_mci17GO4vnP5kCbZ049TbJ5IDC_jI/edit)
- [Selective Resolvers discussion #399](https://github.com/airbnb/viaduct/discussions/399)
- [Resolver OSS build-time plan](https://slate.airbnb.tools/9nREbww0kY)
- [Initial OSS correctness review](https://slate.airbnb.tools/Z8YyTAUXin)
- [Review of PR #1090492](https://slate.airbnb.tools/mKRh5cyEBw)
- [OSS demand-closure handoff](https://slate.airbnb.tools/iDijcqjfQ6)
- [Viaduct Modern Tenant API Spec](https://docs.google.com/document/d/1DSsqbNKAMAKTxn2QdSdOQYX4QJcrtX__PQrycRNeGcQ/edit)

### Implementation Lineage

- [#1085244: Record resolver OSS at build time](https://git.musta.ch/airbnb/treehouse/pull/1085244)
- [#1088747: Project resolver-owned output selections](https://git.musta.ch/airbnb/treehouse/pull/1088747)
- [#1090492: Compute OSS-bounded resolver demand](https://git.musta.ch/airbnb/treehouse/pull/1090492)
- [#1086215: QueryPlan `KeyTree` projection](https://git.musta.ch/airbnb/treehouse/pull/1086215)
- [#1086532: MAT ledger](https://git.musta.ch/airbnb/treehouse/pull/1086532)
- [#1089236: Remove selective OER keys](https://git.musta.ch/airbnb/treehouse/pull/1089236)
- [#1089960: Deep arbitrary suite](https://git.musta.ch/airbnb/treehouse/pull/1089960)
- [#1061282: Preserve type constraints](https://git.musta.ch/airbnb/treehouse/pull/1061282)
- [#1064433: Normalized child plans](https://git.musta.ch/airbnb/treehouse/pull/1064433)
- [#1079007: Retain skipped fragments](https://git.musta.ch/airbnb/treehouse/pull/1079007)
- [`FieldResolutionResult.engineResult`](../../../runtime/src/main/kotlin/viaduct/engine/runtime/FieldResolutionResult.kt)
- [Tenant resolver output lowering](../../../../tenant/runtime/src/main/kotlin/viaduct/tenant/runtime/execution/FieldUnbatchedResolverExecutorImpl.kt)
- [`EngineObjectData.Sync` materialization](../../../runtime/src/main/kotlin/viaduct/engine/runtime/SyncEngineObjectDataFactory.kt)
- [`ViaductSchema.EnumValue`](../../../../shared/viaductschema/src/main/kotlin/viaduct/graphql/schema/ViaductSchema.kt)

### Specifications

- [Viaduct output selection sets](https://viaduct.airbnb.tech/docs/developers/resolvers/?h=output+selection#output-selection-sets)
- [Viaduct node responsibility sets](https://viaduct.airbnb.tech/docs/developers/resolvers/node_resolvers/#responsibility-set)
- [GraphQL CollectFields](https://spec.graphql.org/draft/#CollectFields)
- [GraphQL fragment applicability](https://spec.graphql.org/draft/#sec-Fragment-Spread-Is-Possible)
- [GraphQL variables](https://spec.graphql.org/draft/#sec-Language.Variables)
