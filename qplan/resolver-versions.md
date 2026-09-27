# Resolver Families and Alignment

## Purpose

Every maintained resolver uses the aligned Engine API carrier boundary. The versions form a comparison grid that separates semantic capability from execution structure. Resolver26 is the primary algorithm, while earlier versions make its essential ideas easier to isolate and verify. Maintaining the grid lets us identify the structural similarity in field resolution as dependency management changes from depth-first ordering to promise-based suspension and capabilities grow to include `@parent`, runtime from-field variables, and access checks.

Alignment makes that similarity visible in code: corresponding semantic roles should have corresponding names, helper boundaries, carriers, and ownership. A reader comparing construction demand, successor demand, parent lifting, or task preparation should be able to locate the common algorithm and identify what each added capability requires. Resolver26 is the intended end product, and maintaining Resolver01–23 is part of preserving its architectural integrity.

This document owns the cross-family alignment policy and code naming preferences. [`semantics/README.md`](./semantics/README.md#vocabulary) owns the canonical definitions of contexts, occurrences, tasks, lifecycle phases, and demand; [`design-principles.md`](./design-principles.md#use-earlier-resolvers-to-remove-accidental-complexity) gives the durable design rationale; [Resolver26's design](./semantics/src/main/kotlin/semantics/resolver26/design.md) owns its specific protocols.

## Elements of Alignment

Treat different names for the same role, arbitrary helper decomposition, and inconsistent ownership of equivalent work as accidental differences to remove. Treat differences required by a family's capabilities, identity rules, or progress guarantees as essential differences to explain. Earlier resolvers supply the simpler structural reference, but their vocabulary and boundaries can also improve: when a clearer name describes a common role, update every maintained implementation of that role.

| Element | Common structure to expose | Differences to preserve |
| --- | --- | --- |
| [Construction-demand closure](./semantics/README.md#demand-vocabulary) | Close one object/associated-Query pair: discover new resolver and checker expansion work, collect and route input demand, lift parent demand, and repeat to a fixed point. Preserve checked/unchecked provenance. | Grounded closure substitutes available bindings. Symbolic closure tracks key-inclusion alternatives and finalizes occurrence/provider descriptions needed by preparation. Its incremental lifting need not become grounded closure's re-grounding schedule. |
| [Successor demand](./semantics/README.md#demand-vocabulary) | Separate recursively requested output from fixed resolver/checker input contributions; make expansion boundaries and their state explicit. | Grounded boundaries retain argument-sensitive keys. Resolver26 uses schema-field boundaries for fixed-template analysis, symbolic producer projection, and memoization that accounts for recursion cuts. |
| [Parent lifting](./semantics/README.md#parent-construction-lookahead) | Keep parent-induced demand analysis identifiable, with helpers returning additions for their caller to combine with original demand. Distinguish construction lookahead from successor transposition. | Preserve each caller's lift schedule, grounding, and cache rules. Sharing the analysis does not imply support for parent backedges in every execution family. |
| [Task roles and preparation](#lifecycle-comparison) | Orchestration closes demand and prepares publications; field-resolver tasks invoke resolvers, resolve passive output, and discover descendant orchestration. Distinguish preparation, dispatch, execution, and publication, with explicit owners. | DFS obtains readiness by dependency order; coroutine families claim publications before dispatch and suspend on promises. Resolver26 must additionally describe symbolic work during closure and defer activation until runtime bindings are ready. |
| [Publication and invocation identity](./semantics/README.md#publication) | Keep the publication at the consumer occurrence distinct from the resolver invocation that produces its value. A reference tail can change invocation while retaining publication ownership. | Grounded and symbolic publication identities differ; conditioned passive publications and variable-provider reads are Resolver26 mechanisms. |
| [Query ownership](./semantics/README.md#lifecycle-and-task-roles) | Distinguish the orchestration's associated Query OER, shared by its ordinary owners, from independently rooted reference-target or `ctx.query()` execution. | Inline DFS production and coroutine producer launch have different readiness and ownership mechanics. |

The closure/preparation boundary deserves particular care. Grounded closure returns demand ready for its preparation helpers; it has no need for a separate description of executable occurrences. Resolver26 also has preparation helpers, but symbolic closure first retains the occurrence and provider-read descriptions those helpers need. Constructing these descriptions belongs with making demand ready for preparation. Claiming publication cells and promises belongs with publication preparation; dispatching their owners belongs with execution scheduling. Align these responsibilities without requiring identical return types or moving runtime execution into closure.

In particular, Resolver01–08 obtain readiness from synchronous depth-first execution whose order is constrained by resolver data dependencies: a consumer runs after the predecessors whose values it reads. Resolver21–26 decouple task execution order from value readiness by installing promises and allowing coroutine tasks to suspend until their inputs complete. This is an intentional comparison boundary, not incidental implementation drift. A shared abstraction is useful only when it leaves that distinction visible; synchronous implementations should not acquire asynchronous-style producer installation, promise-readiness, or preparation/launch machinery merely to make lifecycle code uniform.

**Do not make changes that obscure meaningful differences among resolver families.** Consolidation must preserve where each implementation obtains readiness, ordering, ownership, and progress guarantees. Prefer a small amount of family-specific structure when a common mechanism would make a synchronous dependency-ordering algorithm look like an asynchronously scheduled promise-driven algorithm, or vice versa.

Shared contracts can therefore be valuable solely for enforcing architectural consistency. `SharedFieldResolverTask<P>` intentionally requires every field-resolver-task implementation to retain a concretely typed publication occurrence, even though no caller currently consumes that interface polymorphically. The importance of the field-resolver-task role justifies preserving this common shape. Evaluate such contracts by the architectural concern they make explicit as well as by shared runtime consumers; absence of a polymorphic caller alone is not a reason to remove them.

## Code Naming Preferences

Prefer names that expose the common role and qualify them where an essential difference matters. These are contextual preferences for corresponding code, not instructions to make unlike operations look identical. The [semantic vocabulary](./semantics/README.md#vocabulary), [lifecycle verbs](./semantics/README.md#lifecycle-and-task-roles), and [demand definitions](./semantics/README.md#demand-vocabulary) remain authoritative.

- Prefer **`orchestration` over `orchestrator`** when naming the coordination activity, its task role, or its demand. Use `OrchestrationTask`, `OrchestrationConstructionDemand`, and `closeOrchestrationConstructionDemand`; the demand belongs to an orchestration of the object/Query pair. Name a variable holding that task `orchestration` when the role is unambiguous.
- Prefer **lifecycle verbs that describe the actual boundary**. Use `prepare`/`prepareAll` for helpers that claim publications and make work ready for dispatch; use `dispatchOrchestration`/`dispatchFieldResolver` for handing work to its dispatcher and `launch` for starting execution. Thus a prepare-only helper should not be called `launchAll`, while helpers that own both preparation and dispatch are `prepareAndDispatchFieldWork` and `prepareAndDispatchListElement`. Name dispatch guards `dispatched` and collections of accepted tasks `dispatchedTasks`; neither means the task has started. DFS `DepthFirstFieldResolverTask.prepare` also activates its cell and returns an executable task; it does not imply a coroutine prepare-all protocol.
- Prefer **`closedConstructionDemand` for provenance-preserving demand** and **`closedValueSelections` for its merged value projection**. Use `checked`/`unchecked` to describe construction provenance and `checked`/`raw` to describe reads or materialization. A raw checker read does not make the value resolver's own inputs unchecked.
- Prefer **`requestedSuccessorDemand` and `fixedSuccessorInputDemand`** for the two contributions to successor expansion. Use `fixedResolverInputDemand` and `fixedCheckerInputDemand` for the latter's distinct sources, and `SuccessorExpansionState` for the expansion protocol. Corresponding names expose the decomposition even when one family's state additionally owns caching and recursion-cut accounting.
- Prefer **`hasRegisteredResolver` for registry membership**, reserving activation language for the decision to run a particular occurrence. Prefer **`producerSuppliableOnly` over `passiveOnly`** when an analysis admits both passive fields and argumentless resolver-bearing fields that an ancestor producer may supply. Registry membership alone establishes neither runtime activation nor output ownership.
- Prefer **`fieldResolver` and `fieldChecker` task names** for their canonical roles. A field-resolver task may follow references or publish conditioned passive values; do not invent a separate field-value task role for that reason. Retain **`Grounded`/`Symbolic` publication qualifiers** where they identify different identity and binding contracts.
- Prefer **`prepareRootFieldReferenceInvocation` and `invokeRootFieldResolver`** for corresponding reference-target work. Retain the meaningful distinction between DFS `produceAndMaterializeIndependentQueryFragment` and coroutine `launchIndependentQueryFragmentProducer`: one returns materialized input after synchronous production, while the other starts a producer whose result is read separately.
- Prefer **`invocationRoot`, `invocationPath`, and `invocationKey`** for reference-target identity, including preparation locals and prepared carriers. These names distinguish a changing invocation from the stable consumer publication. Prefer **`constructionDemand` at orchestration factories** and **`closedConstructionDemand` for their closure result**; `initialDemand` remains appropriate inside closure. Use `closedOER` for one side's richer closed-demand context, and `oer` for its prepared OER context.
- Prefer **`Base` on shared coroutine task base classes** to distinguish them from concrete tasks. Keep `CoroutineOrchestrationTaskBase` and `CoroutineFieldResolverTaskBase` in `resolver26`; Resolver21–23 retain their concrete `Coroutine...Task` names, and Resolver26 retains `OrchestrationTask` and `FieldResolverTask`. These qualifiers make inheritance legible while keeping the production algorithm's canonical role names.
- Prefer **semantic role names for locals, parameters, and properties**, rather than mechanically repeating a type's `Context` or `State` suffix: `operation`, `publication`, `variableBindings`, `fieldValue`, `authoritativeNodeIdentity`, and `passiveValue` identify different responsibilities. Preserve `operation: SharedOperationContext` in grounded analysis and `world: Assumptions` in symbolic analysis; their dependency boundaries differ. Similarly, preserve `newResolverKeys` versus `newResolverKeyInclusions` when the latter discovers new inclusion alternatives for an existing symbolic key.
- Prefer **type and helper names that reveal their organizing principle**. Apply the canonical `Occurrence`, `Task`, `Logic`, `Context`, `State`, and `Observer` [roles](./semantics/README.md#vocabulary). Name semantic helpers at different receiver levels distinctly, as in `findParentDemandInSelectionForest` and `findParentDemandInObjectSelection`, so the call site identifies the operation. Keep corresponding boundaries recognizable across families even when separate implementations are necessary.

When adding or refactoring a helper, compare its counterpart in the simpler family: match the name and boundary if the responsibility matches; otherwise make the reason for the difference visible in the name or nearby documentation. Update this policy when a new common role emerges, and update canonical definitions in `semantics/README.md` when its meaning changes.

## Production Direction and Package Ownership

The [runtime2 integration plan](https://slate.airbnb.tools/hSFpbNvtAN) makes Resolver26 the production field-resolution implementation. Its production dependencies and common architectural contracts will remain in `main`; the earlier resolver families will remain maintained in an unpublished `support` source set, shared by tests, benchmarks, and development tools. JUnit-specific contracts and harnesses belong in `test`. The earlier families continue to provide architectural comparison and incremental feature development without becoming production alternatives.

This direction explains today's [package ownership](./semantics/README.md#package-ownership): `semantics.shared` exposes machinery needed across all families, including DFS; `semantics.resolvers` owns support exclusive to earlier families; and `semantics.resolver26` owns the production algorithm and machinery shared by the coroutine families. A coroutine base does not move to `shared` merely because Resolver21–23 also use it. Under the future package scheme, today's Resolver26 implementation becomes `resolution`, while all-family architectural roles become `resolution.framework`. The integration plan owns the eventual source-set and package migration; this policy guides placement in the current qplan layout.

## Comparison Grid

| Semantic stage | Recursive construction | Explicit depth-first tasks | Structured coroutines | Capability |
| --- | --- | --- | --- | --- |
| Base | Resolver01 | Resolver06 | Resolver21 | Empty user object fragments and complete output |
| Object fragments | Resolver02 | Resolver07 | Resolver22 | Nonempty fragments and `FromArgument`, with complete output |
| Selective resolution | Resolver03 | Resolver08 | Resolver23 | The same fragment domain with selective output and full successor demand |

Each row changes semantic capability while holding the execution family roughly constant. Each column changes execution structure while holding capability roughly constant.

Resolver01–03, Resolver06–08, and Resolver21 have an input precondition that the schema contains no `@parent` fields, equivalently `world.parentFieldRelations.isEmpty()`. This applies to the whole schema, including fields the operation does not select. These versions do not validate or define rejection behavior for schemas outside that domain. Shared demand closure always includes parent lifting; an empty parent relation contributes no demand without a resolver-capability flag.

Every row also supports symbolic root-field references in resolver output. A demanded reference invokes its registered target with grounded arguments and an empty object input, follows direct reference tails, and publishes the eventual scalar, enum, object, or list-position value at the consumer occurrence. Resolver01-08 invoke each target inline before continuing depth-first sibling work. Resolver21-23 invoke it inside the current structured coroutine scope. The fragment-capable rows execute a target Query fragment and bind its `FromArgument` variables; the selective row supplies full successor demand to the target. Resolver26 additionally supports its runtime `FromQueryField` and `FromProvider` target bindings and condition-aware activation.

### Lifecycle comparison

The phase names follow the [canonical vocabulary](./semantics/README.md#lifecycle-and-task-roles). `OrchestrationConstructionDemand` and `closeOrchestrationConstructionDemand` name the paired closure role in every family. `closedValueSelections` denotes its merged value projection, not its checked/unchecked provenance.

| Phase | Resolver01–08 | Resolver21–23 | Resolver26 |
| --- | --- | --- | --- |
| Orchestration preparation | Close grounded paired demand; establish dependency order. | Close grounded paired demand and bind `FromArgument`; establish parent backedges where supported. | Close symbolic paired demand, retain occurrence/provider descriptions, declare pending bindings, and establish parent backedges. |
| Publication preparation | `DepthFirstFieldResolverTask.prepare` claims and activates a publication and returns its executable task at dispatch. No prepare-all readiness protocol. | `prepareAll` claims ordinary value and checker publications on both roots during `prepareAndDispatchFieldWork`. | Prepare checker slots before passive descent; record executable checkers and delayed absence explicitly. Descent registers conditioned passive-list publications; `prepareAndDispatchFieldWork` prepares ordinary values. |
| Dispatch | Enter synchronously or enqueue the prepared task in dependency order. | Orchestration dispatches prepared value/checker publications. | Orchestration dispatches ordinary and conditioned passive publications alongside executable checkers. |
| Task launch | Dispatcher enters the task or the reactor later dequeues it. | Dispatcher launches a request-root coroutine and constructs the running task inside it. | Same coroutine boundary. |
| Activation | During field-resolver task preparation. | During ordinary value publication preparation. | During execution after argument and condition readiness; negative activation leaves the reserved cell logically absent. |
| Publication | Publish after dependency-ordered execution and passive value resolution. | Publish value and checker slots independently; null-checker entries complete during orchestration. | Publish after runtime binding/activation; absent checker slots complete after passive materialization or field-resolver activation/cancellation. |

The coroutine prepare-all boundary covers ordinary fields on one orchestration pair, including Resolver26's deferred conditioned passive lists. List-element references discovered during passive value resolution retain a combined preparation/dispatch boundary in each family; their cells are list positions rather than new object keys, and discovery may occur after the containing OER freezes. Descendant orchestration can also dispatch before the containing orchestration during passive descent. These boundaries preserve the recursive algorithm without imposing coroutine readiness machinery on DFS.

Both coroutine families validate prepared publication metadata at field-task entry, inside the field-error boundary and before helper coroutines start. Invalid metadata therefore becomes an error in the owned field without aborting sibling execution; Resolver26 also completes bindings whose provider readers will not start. DFS retains its synchronous preparation preconditions and fail-fast behavior. This deliberate failure-policy difference does not justify moving coroutine validation into orchestration preparation, where it would widen the failure to the containing work. Publication claiming and orchestration validation still follow their own preparation/dispatch failure boundaries.

### Recursive Reference: Resolver01-03

Resolver01 is the smallest result-tree constructor. Resolver02 adds object-fragment closure and `FromArgument`. Resolver03 adds selective projection and full successor demand.

Resolver03 is the principal compact semantic reference. Start there when reasoning about demand closure, exact-key publication, passive deepening, argument grounding, or completed-result correctness that does not require runtime object-field variables.

Resolver01-03 require a schema with no `@parent` fields. Parent backedges can require an ancestor resolver to re-enter the same still-open child occurrence, which is not representable by their per-OER sibling dependency order without adding graph re-entry machinery. [`examples.md`](./examples.md#why-the-depth-first-resolvers-do-not-support-parent) gives a concrete world.

### Explicit Work: Resolver06-08

Resolver06-08 run the same `DepthFirstOrchestrationTask` and `DepthFirstFieldResolverTask` implementations as Resolver01-03 through the `DepthFirstReactor` queue. Resolver08 is especially useful after Resolver03 passes: it exposes task identity, queue ordering, and publication as explicit mechanics without adding `FromObjectField`.

Resolver06-08 have the same parent-free schema precondition; making their task queue occurrence-aware enough to suspend, revisit an ancestor, and safely re-enter an open descendant would erase the simplicity that makes this family useful.

### Structured Suspension: Resolver21-23

Resolver21-23 use the same task roles and phase boundaries as Resolver26: a prepared `CoroutineOrchestrationTask`, a `GroundedFieldPublicationOccurrence<CoroutineOperationContext>` passed to the dispatcher, a running `CoroutineFieldResolverTask` owning helper coroutines, and `FieldResolutionLogic` for invocation and publication. Both coroutine families jointly close one object OER and one associated Query OER, prepare all ordinary value and checker publications on both OERs before their dispatch, and let ordinary owners suspend while materializing their projections from that associated Query OER; independently rooted reference targets retain field-owned Query producers. They use `resolver26.CoroutineTaskDispatcher` and extend `resolver26.CoroutineOrchestrationTaskBase` and `resolver26.CoroutineFieldResolverTaskBase`, sharing scheduling, the paired-OER orchestration preparation/dispatch/freeze lifecycle, and the field-error boundary. Their concrete tasks retain specialized demand preparation, publication preparation, and resolution logic. Resolver21 is the access-check plumbing baseline: a sibling `CoroutineFieldCheckerTask` retains a typed `GroundedFieldCheckerPublicationOccurrence` and resolves checker slots for already-demanded grounded field occurrences on either OER, accepts only argument-dependent checkers with no required selections, and does not alter value demand. Materialization uniformly enforces a present checker slot and defaults an absent slot open, but Resolver21 remains publication-only in practice because its resolvers have empty input fragments. Resolver22/23 add fragment-bearing ordinary resolver inputs that make enforcement observable; Resolver22 also supports named paired raw checker inputs, and Resolver23 adds selective checked/unchecked successor demand and exact checker applications. Resolver26 carries that contract through symbolic identities, condition-aware activation, all four variable sources, cancellation, and shared object/Query closure. Type-checker discovery remains deferred.

Resolver22/23 support `@parent`. Their structured suspension and exact promises allow demand to cross to an ancestor and return through an already-started descendant without forcing a depth-first re-entry protocol into local dependency ordering. Resolver21 retains its empty-fragment capability boundary and parent-free schema precondition.

Access checking is likewise confined to Resolver21–23 and Resolver26. Resolver01–08 could run checkers with no required selections, but checker RSS creates unchecked value-demand edges whose reached value resolvers retain ordinary checked dependencies. Representing that general dependency graph would require suspension or re-entry machinery contrary to the depth-first families' purpose, so they remain value-only and require a checker-free registry as an input precondition. [`access-check-semantics.md`](./access-check-semantics.md#resolver-family-boundary) defines this boundary.

## Advanced Resolvers

### Resolver26

Resolver26 retains variable-bearing resolver-fragment selections as symbolic OER keys. Variables are instantiated once per resolver occurrence, so equal symbolic keys coalesce within an OER while separate containing OERs remain distinct. It synchronously closes symbolic demand before local installation, uses source presence to let ancestor outputs own argumentless fields that otherwise have standard resolvers, prepares every binding required by the remaining work, reserves active cells once their symbolic keys are contextually grounded, freezes the OER key set, and runs field resolution under one request-owned coroutine scope.

Resolver26 now uses the shared passive traversal and task-context interfaces. Its orchestration factory returns paired closed demand and fully initialized task state, while its `SharedTaskDispatcher` implements the two dispatch operations with request-root coroutines. Resolver01-03 and Resolver06-08 use the same protocol and grounded task implementations, with synchronous and queued dispatch respectively. Every maintained resolver resolves ordinary declared Query fragments through one associated Query OER per orchestration task, shared by its owners, while independently rooted reference targets retain a fresh nested execution. The coroutine implementations prepare ordinary object- and Query-side publications before field-resolver and field-checker dispatch and rely on promise readiness rather than imposing the depth-first families' dependency order. They close ancestor demand before passive descent and freeze each OER after publication preparation; ancestor re-orchestration and passive-child rediscovery are unnecessary.

Resolver26 supports `@parent` by extending both construction-demand closure and successor-demand closure to lift parent-induced demand before each OER is frozen.

Resolver26 implements the same root-field-reference contract as the comparison grid through its sealed occurrence and field-resolver-task protocol. That implementation adds conditioned passive activation, runtime variable providers, and exact invocation/publication observations; those are Resolver26 mechanisms rather than prerequisites for the shared reference behavior.

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

Shared carrier and API migrations must update every maintained version. Prefer one shared model operation or adapter boundary over resolver-specific compatibility code. Older versions should not acquire separate production integrations.

Resolvers01-23 gain behavior through shared contracts. Bespoke tests remain appropriate for Resolver26's symbolic-key policy and implementation-specific lifecycle or concurrency behavior.

## Resolver10 As A Lesson

Resolver10 is not maintained, but comparing its abandoned approach with Resolver03, Resolver08, and Resolver26 is useful. It combined readiness rescanning, persistent late-demand acceptance, provider traversal, late grounding, and complete-output retention. That machinery increased the state space and could conceal incomplete demand supplied to the original producer.

Use Resolver10 to recognize paths that can be simplified, not as code to revive or a complete version to document.
