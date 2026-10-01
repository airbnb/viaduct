# Qplan Research Evidence

## Purpose

This document preserves durable evidence, correctness obligations, known hard cases, and source provenance that inform qplan resolver design. It is not an implementation-status document: use [runtime2’s overview](../README.md) for current state and resolver-local design documents for current behavior. Completed chronology belongs in Git history.

## Established Findings

These findings come from production investigation, focused counterexamples, or failed model designs:

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

The central consequence is producer-specific: every producer-owned value later consumed from one resolver-bearing occurrence must be covered by the demand supplied to that occurrence's producing application. A correct final union, cache hit, widened result, or second materialization is weaker evidence.

Ungrounded argument-bearing fields force one of three policies. Execution can wait for every potentially coalescing key to ground, which provides true one-shot behavior but requires cycle exclusion strong enough to prevent deadlock. Execution can speculatively widen successor demand for keys that may later coalesce, but this makes supplied demand timing-sensitive and was rejected as a poor developer experience. Or execution can coalesce by symbolic values and variable-instance identity, accepting that distinct symbolic keys may later ground equally and invoke the same resolver more than once. Resolution uses the third policy; the first remains a possible design only if its progress rule can be made tractable.

## Correctness Obligations

### Producer Completeness

All in-scope demand targeting one resolver-bearing occurrence is accounted for before its selective producer runs. Work that cannot satisfy this relation must be conservatively covered, assigned a distinct occurrence or epoch, or excluded.

### Dependency Discovery

Every resolver, provider, concrete-type step, or other prerequisite that execution may require is represented before dispatch. Runtime activation may choose among bounded alternatives; it must not silently introduce an unbounded dependency for an already-applied producer.

### Identity Agreement

Aggregation identity agrees with actual result construction. Multiple paths to one exact cell converge, while separate objects, list positions, argument tuples, and epochs remain separate even when entity IDs or values agree.

### Ownership Soundness

Demand supplied to a producer remains within that producer's output ownership apart from explicit engine bridges. Traversal stops at behavioral boundaries and attributes successor work to the successor producer.

### Monotonic Safety

Each exact cell and binding has at most one writer and one value, and each resolver-bearing occurrence has at most one producing application. Published parent structure remains stable while descendants gain cells.

### Termination And Liveness

Demand closure and dependency ordering terminate over the accepted finite domain. Execution progress additionally assumes that invoked tenant resolvers, checkers, and variables-provider callbacks return or throw. Under that assumption, required claimed units complete successfully or exceptionally so dependents are released; missing writers and engine-created deadlocks remain defects. The [tenant failure and progress policy](design-principles.md#tenant-failure-isolation-and-progress) permits the operation to wait indefinitely on nonterminating tenant work, including work whose output becomes unnecessary after another error. It does not require prompt completion through demand retraction, and request-wide abort is not an acceptable fallback for local tenant errors.

### Concurrency

Correct aggregation does not require global barriers across unrelated object or list occurrences. Independent ready work remains concurrent, and compatible underlying work may still be batched beneath distinct semantic occurrence identities.

## Hard-Case Inventory

| Area | Current status | Durable concern |
| --- | --- | --- |
| Converging demand | Modeled and tested in qplan | Independently reached selections can require unequal demand from one producer. |
| Runtime `FromObjectField` providers | Modeled by Resolution; active qplan compatibility constraint | Structural paths are known before execution, but values and exact consumer keys appear only after provider cells complete. |
| Query re-entry and ancestor or `@parent` targets | Backlogged compatibility constraint | Targets outside ordinary descendant traversal require occurrence-specific scope and ancestry identity. |
| Abstract recursion and cycle backedges | Partly modeled; broader production compatibility remains backlogged | Concrete alternatives can be lazy, and legal recursion requires guarded dependencies plus exact ancestor context. |
| Lists and repeated IDs | Modeled and active | Every list position is an independent result occurrence even when IDs, coordinates, or values repeat. |
| Aliases, directives, and fragments | Response aliases are preserved for resolver-input materialization; named spreads are lowered at the semantic document boundary; Query-rooted resolver fragments are modeled by Resolver02/03, Resolver07/08, Resolver22/23, and Resolution but remain excluded from the stated future `execution2` scope; directives remain mostly pre-reasoning or out of scope | Internal normalized demand must not be confused with response identity, an independently resolved query value, or tenant-visible syntax. |
| Checkers and execution epochs | Backlogged; mutations, subscriptions, and incremental epochs are outside the stated future scope | Raw and checked reads can differ, and work must not be coalesced across ordering or epoch boundaries. |

Scope labels describe current qplan and stated integration boundaries, not claims that the harder cases are permanently irrelevant. Carrier changes must preserve their identities or explicit exclusions without silently broadening qplan's supported domain.

## Production Scalar Carrier Evidence

Production Viaduct has no engine-level nominal value type for GraphQL ID or enum members. GraphQL Java coercion produces strings, tenant argument conversion projects those strings to tenant-facing `GlobalID<T>` or generated enum values when required, tenant resolver return conversion lowers those values back to strings, `FieldResolutionResult.engineResult` stores the resulting leaf unchanged, and `EngineObjectData.Sync` exposes the same string. Consequently an ID, GraphQL String, and enum member with the same spelling are indistinguishable inside current production engine input and output data without schema context.

Qplan's carrier model deliberately follows this production representation for `EngineInputData` and `EngineOutputData`, but not for `EngineResult`. The result domain uses structural `EngineIDResult` values and canonical `ViaductSchema.EnumValue` definitions so IDs, strings, enum types, and same-named members of distinct enum types remain distinguishable. Schema-directed adapters wrap output strings when publishing results and unwrap result values when materializing resolver-visible data. This is a compatibility conversion, not an assertion that production's overloaded representation is the desired endpoint.

## Multiple-Materialization Prior Art

The MAT and `KeyTree` work represent runtime demand and coverage as typed trees of exact OER keys. Union combines demand, difference exposes missing coverage, and paths preserve concrete types, arguments, and list positions. These are useful ideas for coverage and diagnostics.

A coverage tree is not by itself a producer-attribution or dependency model. `KeyTree` does not encode consumer provenance, producer ownership, scheduling prerequisites, raw-versus-checked reads, target scope, guarded alternatives, or whether two paths share one producing application. MAT may fetch missing coverage after demand arrives; one-shot resolution must instead justify complete in-scope demand before the first selective application.

## Focused Acceptance Cases

Broad generated campaigns do not replace small cases that directly exercise the obligations above. Retain or introduce focused coverage for:

- split-prediction rejection and sibling convergence on one producer;
- Node-valued source production plus per-occurrence `Query.node` reference dispatch, including independent list elements;
- lazy concrete-type dependencies and legal cycle backedges;
- abstract concrete recursion and independent list-item occurrences;
- raw checker reads when checkers enter scope;
- the distinctions among response alias, canonical field, and exact `GroundKey` identity;
- null and error ancestry;
- explicit missing-writer failure;
- variable late equality, either rejected or conservatively covered; and
- a selective producer that returns only requested coverage, so complete output cannot mask missing demand.

Every acceptance claim must identify whether it is a current resolver contract, an integration constraint, or backlog. Excluded cases should remain visible without becoming implied requirements of the current carrier model.

## Future Design Questions

1. What exact feature scope receives a one-shot producer-completeness guarantee?
2. How is out-of-scope demand rejected, conservatively covered, or isolated?
3. Which demand paths converge on one exact OER cell?
4. Which dependencies are bounded statically, and which values bind only at runtime?
5. How are provider targets, concrete alternatives, Query or ancestor targets, and execution epochs represented?
6. How are aliases, directives, fragments, internal demand, resolver-visible demand, and completion artifacts kept distinct?
7. How are cycles classified and diagnosed?
8. Why must every unfinished valid state have ready work?
9. How does every failure complete or cancel its dependents?
10. What schedule-independence result justifies concurrent execution?
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
- [`FieldResolutionResult.engineResult`](../../runtime/src/main/kotlin/viaduct/engine/runtime/FieldResolutionResult.kt)
- [Tenant resolver output lowering](../../../tenant/runtime/src/main/kotlin/viaduct/tenant/runtime/execution/FieldUnbatchedResolverExecutorImpl.kt)
- [`EngineObjectData.Sync` materialization](../../runtime/src/main/kotlin/viaduct/engine/runtime/SyncEngineObjectDataFactory.kt)
- [`ViaductSchema.EnumValue`](../../../shared/viaductschema/src/main/kotlin/viaduct/graphql/schema/ViaductSchema.kt)

### Specifications

- [Viaduct output selection sets](https://viaduct.airbnb.tech/docs/developers/resolvers/?h=output+selection#output-selection-sets)
- [Viaduct node responsibility sets](https://viaduct.airbnb.tech/docs/developers/resolvers/node_resolvers/#responsibility-set)
- [GraphQL CollectFields](https://spec.graphql.org/draft/#CollectFields)
- [GraphQL fragment applicability](https://spec.graphql.org/draft/#sec-Fragment-Spread-Is-Possible)
- [GraphQL variables](https://spec.graphql.org/draft/#sec-Language.Variables)
