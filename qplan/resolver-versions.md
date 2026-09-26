# Maintained Resolver Versions

## Purpose

Every maintained resolver uses the aligned Engine API carrier boundary. The versions form a comparison grid that separates semantic capability from execution structure; they are not eleven production candidates. Resolver26 is the primary algorithm, while earlier versions make its essential ideas easier to isolate and verify. The grid preserves architectural insight into both the similarities and the differences among implementations: common roles reveal reusable semantic structure, while different execution mechanics reveal which guarantees come from ordering, scheduling, suspension, or promise ownership.

Resolver26 is the intended end product, and maintaining Resolver01–23 is part of preserving its architectural integrity. Implementing the same concerns in simpler algorithms and different execution structures forces deliberate choices about decomposition, encapsulation, ownership, and the relationships among contexts, occurrences, and tasks. Aligning their structure provides an ongoing check on Resolver26's design as its capabilities grow.

In particular, Resolver01–08 obtain readiness from synchronous depth-first execution whose order is constrained by resolver data dependencies: a consumer runs after the predecessors whose values it reads. Resolver21–26 decouple task execution order from value readiness by installing promises and allowing coroutine tasks to suspend until their inputs complete. This is an intentional comparison boundary, not incidental implementation drift. A shared abstraction is useful only when it leaves that distinction visible; synchronous implementations should not acquire asynchronous-style producer installation, promise-readiness, or preparation/launch machinery merely to make lifecycle code uniform.

**Do not make changes that obscure meaningful differences among resolver families.** Consolidation must preserve where each implementation obtains readiness, ordering, ownership, and progress guarantees. Prefer a small amount of family-specific structure when a common mechanism would make a synchronous dependency-ordering algorithm look like an asynchronously scheduled promise-driven algorithm, or vice versa.

Shared contracts can therefore be valuable solely for enforcing architectural consistency. `SharedFieldResolverTask<P>` intentionally requires every field-task implementation to retain a concretely typed publication occurrence, even though no caller currently consumes that interface polymorphically. The importance of the field-task role justifies preserving this common shape. Evaluate such contracts by the architectural concern they make explicit as well as by shared runtime consumers; absence of a polymorphic caller alone is not a reason to remove them.

## Comparison Grid

| Semantic stage | Recursive construction | Explicit depth-first tasks | Structured coroutines | Capability |
| --- | --- | --- | --- | --- |
| Base | Resolver01 | Resolver06 | Resolver21 | Empty user object fragments and complete output |
| Object fragments | Resolver02 | Resolver07 | Resolver22 | Nonempty fragments and `FromArgument`, with complete output |
| Selective resolution | Resolver03 | Resolver08 | Resolver23 | The same fragment domain with selective output and full successor demand |

Each row changes semantic capability while holding the execution family roughly constant. Each column changes execution structure while holding capability roughly constant.

Resolver01–03, Resolver06–08, and Resolver21 have an input precondition that the schema contains no `@parent` fields, equivalently `world.parentFieldRelations.isEmpty()`. This applies to the whole schema, including fields the operation does not select. These versions do not validate or define rejection behavior for schemas outside that domain. Shared demand closure always includes parent lifting; an empty parent relation contributes no demand without a resolver-capability flag.

Every row also supports symbolic root-field references in resolver output. A demanded reference
invokes its registered target with grounded arguments and an empty object input, follows direct
reference tails, and publishes the eventual scalar, enum, object, or list-position value at the
consumer occurrence. Resolver01-08 invoke each target inline before continuing depth-first sibling
work. Resolver21-23 invoke it inside the current structured coroutine scope. The fragment-capable
rows execute a target Query fragment and bind its `FromArgument` variables; the selective row
supplies full successor demand to the target. Resolver26 additionally supports its runtime
`FromQueryField` and `FromProvider` target bindings and condition-aware activation.

### Recursive Reference: Resolver01-03

Resolver01 is the smallest result-tree constructor. Resolver02 adds object-fragment closure and `FromArgument`. Resolver03 adds selective projection and full successor demand.

Resolver03 is the principal compact semantic reference. Start there when reasoning about demand closure, exact-key publication, passive deepening, argument grounding, or completed-result correctness that does not require runtime object-field variables.

Resolver01-03 require a schema with no `@parent` fields. Parent backedges can require an ancestor resolver to re-enter the same still-open child occurrence, which is not representable by their per-OER sibling dependency order without adding graph re-entry machinery. [`examples.md`](./examples.md#why-the-depth-first-resolvers-do-not-support-parent) gives a concrete world.

### Explicit Work: Resolver06-08

Resolver06-08 run the same `DepthFirstOrchestrationTask` and `DepthFirstFieldResolverTask` implementations as Resolver01-03 through the `DepthFirstReactor` queue. Resolver08 is especially useful after Resolver03 passes: it exposes task identity, queue ordering, and publication as explicit mechanics without adding `FromObjectField`.

Resolver06-08 have the same parent-free schema precondition; making their task queue occurrence-aware enough to suspend, revisit an ancestor, and safely re-enter an open descendant would erase the simplicity that makes this family useful.

### Structured Suspension: Resolver21-23

Resolver21-23 use the same task roles and phase boundaries as Resolver26: a prepared `CoroutineOrchestrationTask`, a `GroundedFieldPublicationOccurrence<CoroutineOperationContext>` passed to the dispatcher, a running `CoroutineFieldResolverTask` owning helper coroutines, and `FieldResolutionLogic` for invocation and publication. Both coroutine families jointly close one object OER and one associated Query OER, install every publication on both OERs before dispatch, and let ordinary owners suspend while materializing their projections from that shared Query OER; independently rooted reference targets retain field-owned Query producers. They use `resolver26.CoroutineTaskDispatcher` and extend `resolver26.CoroutineOrchestrationTask` and `resolver26.CoroutineFieldResolverTask`, sharing scheduling, the paired-OER orchestration dispatch/install/freeze lifecycle, and the field-error boundary. Their concrete tasks retain specialized demand preparation, field installation, and resolution logic. Resolver23 is the grounded coroutine baseline and initial landing zone for access checks before transferring them to Resolver26.

Resolver22/23 support `@parent`. Their structured suspension and exact promises allow demand to cross to an ancestor and return through an already-started descendant without forcing a depth-first re-entry protocol into local dependency ordering. Resolver21 retains its empty-fragment capability boundary and parent-free schema precondition.

## Advanced Resolvers

### Resolver26

Resolver26 retains variable-bearing resolver-fragment selections as symbolic OER keys. Variables are instantiated once per resolver occurrence, so equal symbolic keys coalesce within an OER while separate containing OERs remain distinct. It synchronously closes symbolic demand before local installation, uses source presence to let ancestor outputs own argumentless fields that otherwise have standard resolvers, prepares every binding required by the remaining work, reserves active cells once their symbolic keys are contextually grounded, freezes the OER key set, and runs field resolution under one request-owned coroutine scope.

Resolver26 now uses the shared passive traversal and task-context interfaces. Its orchestration factory returns paired closed demand and fully initialized task state, while its `SharedTaskDispatcher` implements the two dispatch operations with request-root coroutines. Resolver01-03 and Resolver06-08 use the same protocol and grounded task implementations, with synchronous and queued dispatch respectively. Every maintained resolver resolves ordinary declared Query fragments through one shared Query OER per orchestration task, while independently rooted reference targets retain a fresh nested execution. The coroutine implementations prepare all object- and Query-side publications before request-root coroutine dispatch and rely on promise readiness rather than imposing the depth-first families' dependency order. They close ancestor demand before passive descent and freeze each OER after field installation; ancestor re-orchestration and passive-child rediscovery are unnecessary.

Resolver26 supports `@parent` by extending both construction-demand closure and successor-demand closure to lift parent-induced demand before each OER is frozen.

Resolver26 implements the same root-field-reference contract as the comparison grid through its
sealed occurrence and field-task protocol. That implementation adds conditioned passive
activation, runtime variable providers, and exact invocation/publication observations; those are
Resolver26 mechanisms rather than prerequisites for the shared reference behavior.

Resolver26 is the primary algorithm and eventual implementation blueprint. Its aligned qplan shape remains close to what a future Viaduct query executor can use, but that future executor is not part of an ordinary qplan refactor.

## Debugging Reduction

Use this order unless the failing feature requires a later version:

1. Resolver03 for compact selective semantics.
2. Resolver08 for explicit work ordering and publication.
3. Resolver23 for structured suspension and promise ownership.
4. Resolver26 for runtime `FromObjectField`, `FromQueryField`, `FromProvider`, and symbolic resolver-instance identity.

Reduce further to Resolver01/06/21 to remove object fragments, or Resolver02/07/22 to retain object fragments and `FromArgument` without selective-output pressure.

Cross-version agreement is not independent proof because versions share carriers, fixture construction, generators, and parts of the oracle. Use the grid to localize differences, then rely on independent application, binding, lifecycle, and occurrence-aware evidence.

## Refactoring Policy

Shared carrier and API migrations must update every maintained version. Prefer one shared model operation or adapter boundary over resolver-specific compatibility code. Older versions should not acquire separate `execution2` integrations.

Resolvers01-23 gain behavior through shared contracts. Bespoke tests remain appropriate for Resolver26's symbolic-key policy and implementation-specific lifecycle or concurrency behavior.

## Resolver10 As A Lesson

Resolver10 is not maintained, but comparing its abandoned approach with Resolver03, Resolver08, and Resolver26 is useful. It combined readiness rescanning, persistent late-demand acceptance, provider traversal, late grounding, and complete-output retention. That machinery increased the state space and could conceal incomplete demand supplied to the original producer.

Use Resolver10 to recognize paths that can be simplified, not as code to revive or a complete version to document.
