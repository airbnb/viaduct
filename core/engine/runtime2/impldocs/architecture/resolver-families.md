# Resolver Families And Alignment

## Purpose

Runtime2 maintains Resolver01–23 alongside production Resolution for two reasons.

First, the families protect Resolution's architectural integrity. They express related semantics through recursive depth-first construction, explicit depth-first tasks, and structured coroutines. Corresponding roles must remain recognizable across those execution structures, while differences required for readiness, identity, ownership, and progress remain explicit.

Second, the families are the feature-development ladder:

```text
Resolver01–03
    → Resolver06–08
    → Resolver21–23
    → production Resolution
```

A semantic change should become understandable and testable in the simplest applicable family before scheduling, suspension, runtime bindings, and production-only concerns are added. Resolver01–23 are therefore maintained executable refinement and comparison models, not historical snapshots, project chronology, or alternative production engines.

This document owns the family boundaries, comparison grid, cross-family alignment policy, naming preferences, and feature-development workflow. [Architectural principles](principles.md) defines the durable semantic rules that every family follows. [Production Resolution](resolution.md) owns the complete production algorithm, and [access checks](access-checks.md) owns checker semantics.

## Source And Package Ownership

Production Resolution lives in `src/main/kotlin/viaduct/engine/runtime2/resolution`. Contracts and model operations required by both production and development families live in `src/main/kotlin/viaduct/engine/runtime2/resolution/framework`. Coroutine base classes shared by Resolver21–23 and Resolution remain in the production `resolution` package because they implement production scheduling and failure boundaries.

Resolver01–23 and support used only by those families live in the unpublished `src/support/kotlin/viaduct/engine/runtime2/resolvers` tree. Correctness machinery, generators, benchmarks, and neutral development fixtures also belong to `support`; JUnit contracts and concrete tests belong to `test`. An architectural relationship across source sets does not make an earlier family a production dependency or justify a separate production integration for it.

Package placement follows semantic ownership, not the number of implementations that call a type. All-family roles belong in `resolution.framework`; earlier-family-only mechanisms belong in `resolvers`; production mechanisms and coroutine-family base machinery belong in `resolution`.

## Comparison Grid

The maintained versions form a refinement and comparison grid, not a chronology. Its rows add semantic capability while its columns change execution structure, allowing the same obligations to be examined independently of increasing scheduling complexity.

| Semantic stage | Recursive depth-first | Explicit depth-first tasks | Structured coroutines | Capability |
| --- | --- | --- | --- | --- |
| Base | Resolver01 | Resolver06 | Resolver21 | Empty user object fragments and complete output |
| Object fragments | Resolver02 | Resolver07 | Resolver22 | Nonempty fragments and `FromArgument`, with complete output |
| Selective resolution | Resolver03 | Resolver08 | Resolver23 | The same fragment domain with selective output and full successor demand |

Each row changes semantic capability while holding execution structure roughly constant. Each column changes execution structure while holding semantic capability roughly constant. Resolver03, Resolver08, and Resolver23 are the usual comparison points for production Resolution because all three expose selective demand at increasing levels of scheduling sophistication.

The grid deliberately does not pretend that every family supports the same domain:

- Resolver01–03 and Resolver06–08 require a schema with no `@parent` fields. Resolver21 has the same precondition at its base capability; Resolver22 and Resolver23 support `@parent`.
- Resolver01–08 are value-only and require a checker-free registry. Resolver21 establishes grounded field- and type-checker publication with fragment-free checker inputs; Resolver22 and Resolver23 add fragment-bearing checker inputs, and Resolver23 adds selective checked and unchecked demand.
- Resolver01–23 substitute available bindings before grouping exact keys. Resolution retains variable-bearing selections as symbolic keys and treats grounding as readiness and invocation data rather than as rekeying.
- All maintained families support root-field references in resolver output. A demanded reference invokes its target with grounded arguments and an empty object input, follows direct reference tails, and publishes the eventual value at the original consumer occurrence. The depth-first families invoke targets inline; coroutine families retain structured ownership and promise readiness.
- All maintained families use source-sensitive ownership for argumentless registered fields: an ancestor output may supply such a field passively, while an omitted field uses its standard resolver. Fields with arguments remain active.
- Resolver01–23 retain inclusion conditions while materializing resolver inputs but do not consistently use them to gate producer and checker activation. Production Resolution evaluates occurrence-local conditions before tenant invocation. The earlier-family activation gap is a maintained alpha boundary, not evidence that inclusion conditions are merely projection metadata.

The parent-free precondition applies to the whole schema, including fields the operation does not select. Those families do not define a fallback execution for a schema outside their domain. Shared demand closure may still contain parent-lifting structure; with an empty parent relation it contributes no work and does not require a capability flag.

## Architectural Alignment

Treat different names for the same role, arbitrary helper decomposition, and inconsistent ownership of equivalent work as accidental differences to remove. Treat differences required by a family's capability, identity rules, readiness protocol, or progress guarantees as essential differences to preserve and explain.

| Element | Common structure to expose | Essential differences to preserve |
| --- | --- | --- |
| Construction-demand closure | Close one object/associated-Query pair: discover resolver and checker expansion work, collect and route input demand, lift parent demand, and repeat to a fixed point while retaining checked and unchecked provenance. | Grounded closure substitutes available bindings. Resolution's symbolic closure tracks key-inclusion alternatives and produces occurrence and provider descriptions needed by preparation. |
| Successor demand | Separate recursively requested output from fixed resolver and checker input contributions. Make expansion boundaries and their state explicit. | Grounded families retain argument-sensitive keys. Resolution uses schema-field boundaries for fixed-template analysis, symbolic producer projection, and recursion-cut-aware memoization. |
| Parent lifting | Keep parent-induced demand analysis identifiable and distinguish construction lookahead from successor transposition. | Preserve each family's lift schedule, grounding, and cache rules. Shared analysis does not imply that every family supports parent backedges. |
| Task roles | Orchestration closes demand and prepares publications. Field-resolver tasks invoke resolvers, resolve passive output, and discover descendant orchestration. | Depth-first families obtain readiness from dependency order. Coroutine families claim publications before dispatch and suspend on promises. Resolution additionally defers activation until runtime bindings and inclusion conditions are ready. |
| Publication and invocation identity | Keep publication at the consumer occurrence distinct from the resolver invocation that produces it. A reference tail may change invocation while retaining publication ownership. | Grounded and symbolic publications have different identity contracts; conditioned passive publications and runtime provider reads are Resolution mechanisms. |
| Query ownership | Distinguish the orchestration's associated Query OER, shared by its ordinary owners, from independently rooted reference-target and `ctx.query()` execution. | Depth-first production runs inline; coroutine producers have explicit structured ownership and readiness. |

The closure/preparation boundary deserves particular care. Grounded closure returns demand ready for its preparation helpers. Resolution's symbolic closure also retains the occurrence and provider-read descriptions those helpers need. Producing those descriptions belongs to closure; claiming cells and promises belongs to publication preparation; dispatching their owners belongs to execution scheduling. Align these responsibilities without requiring identical return types or moving runtime execution into closure.

Resolver01–08 obtain readiness from synchronous depth-first execution whose order follows resolver dependencies: a consumer runs after the predecessors whose values it reads. Resolver21–23 and Resolution decouple task execution order from value readiness by installing promises and allowing coroutine tasks to suspend. A common abstraction is useful only when this difference stays visible. Do not add coroutine-style promise installation or preparation machinery to a synchronous family merely to make lifecycle code uniform.

Shared contracts may exist solely to enforce architectural consistency. `SharedFieldResolverTask<P>` requires each field-resolver-task implementation to retain a concretely typed publication occurrence even though callers currently use concrete task types. The absence of a polymorphic consumer is not, by itself, a reason to remove a contract that makes an important role explicit.

Tasks consume contexts through composition: orchestration tasks expose their owning operation, and field-resolver tasks expose their publication occurrence. A task is not a subtype of the context it uses. Publication occurrences may delegate the operation interface where that relationship is part of their identity and dispatch contract.

When one implementation must be shared between execution families, prefer aligning Resolver21–23 with production Resolution over forcing coroutine behavior through Resolver01–08. Preserve the depth-first families as the simpler structural reference.

## Lifecycle Comparison

`OrchestrationConstructionDemand` and `closeOrchestrationConstructionDemand` name the paired object/Query closure role in every family. `closedConstructionDemand` retains provenance; `closedValueSelections` is its merged value projection.

| Phase | Resolver01–08 | Resolver21–23 | Resolution |
| --- | --- | --- | --- |
| Orchestration preparation | Close grounded paired demand and establish dependency order. | Close grounded paired demand, bind `FromArgument`, and establish parent backedges where supported. | Close symbolic paired demand, retain occurrence and provider descriptions, declare pending bindings, and establish parent backedges. |
| Publication preparation | `DepthFirstFieldResolverTask.prepare` claims and activates one publication and returns its executable task at dispatch. | `prepareAll` claims ordinary value and checker publications on both roots during `prepareAndDispatchFieldWork`. | Prepare checker slots before passive descent, record executable checkers and delayed absence, register conditioned passive-list publications, and prepare ordinary values. |
| Dispatch | Enter synchronously or enqueue the prepared task in dependency order. | Orchestration dispatches prepared value and checker publications. | Orchestration dispatches ordinary and conditioned passive publications with executable checkers. |
| Task launch | The dispatcher enters the task or the depth-first reactor later dequeues it. | The dispatcher launches a request-root coroutine and constructs the running task inside it. | The same coroutine boundary applies. |
| Activation | Activation occurs during field-resolver-task preparation. | Activation occurs during ordinary value-publication preparation. | Activation occurs after argument, binding, and condition readiness; negative activation leaves the reserved cell logically absent. |
| Publication | Publish after dependency-ordered execution and passive value resolution. | Publish value and checker slots independently; absent checker entries complete during orchestration. | Publish after runtime binding and activation; absent checker slots complete through passive materialization or resolver activation and cancellation. |

The coroutine prepare-all boundary covers ordinary fields on one orchestration pair, including Resolution's deferred conditioned passive lists. List-element references discovered during passive value resolution retain a combined preparation and dispatch boundary because their cells are list positions rather than object keys and may be discovered after the containing OER freezes. Descendant orchestration may dispatch during passive descent. These boundaries preserve the recursive semantic structure without imposing coroutine readiness machinery on depth-first implementations.

Coroutine families validate prepared publication metadata at field-task entry, inside the field-error boundary and before helper coroutines start. Invalid metadata therefore becomes an error in the owned field without aborting sibling execution; Resolution also terminates bindings whose producers will not start. The depth-first families retain synchronous preparation preconditions and fail-fast behavior. This difference is part of their failure model and must not be hidden by moving coroutine validation into orchestration preparation.

## Family Roles

### Resolver01–03: Compact Semantics

Resolver01 is the smallest result-tree constructor. Resolver02 adds object-fragment closure and `FromArgument`. Resolver03 adds selective projection and complete successor demand.

Resolver03 is the principal compact reference for demand closure, exact-key publication, passive deepening, argument grounding, one-shot producer demand, and completed-result correctness. Its recursive execution exposes those semantics with the least scheduling machinery.

The family excludes `@parent` because a parent backedge can require an ancestor resolver to re-enter the same open child occurrence. That graph re-entry is not representable by per-OER sibling dependency order without destroying the family's compact depth-first structure. [Examples](examples.md#why-the-depth-first-resolvers-do-not-support-parent) gives a concrete case.

### Resolver06–08: Explicit Depth-First Work

Resolver06–08 express the same capability rows through `DepthFirstOrchestrationTask`, `DepthFirstFieldResolverTask`, and `DepthFirstReactor`. Resolver08 is the principal reference for task identity, queue ordering, preparation, dispatch, and publication without coroutine suspension or runtime path variables.

This family retains the parent-free domain. Making its queue suspend, revisit ancestors, and re-enter open descendants would erase the explicit but simple dependency-ordered execution structure that makes it useful.

### Resolver21–23: Structured Suspension

Resolver21–23 use the same task roles and coroutine phase boundaries as Resolution. They share `CoroutineTaskDispatcher`, `CoroutineOrchestrationTaskBase`, and `CoroutineFieldResolverTaskBase` with production while retaining grounded demand, grounded publication identities, and family-specific preparation and resolution logic.

Resolver21 is the base coroutine and access-check plumbing reference. Resolver22 adds object and Query fragments, `FromArgument`, fragment-bearing checker inputs, and parent support. Resolver23 adds selective checked and unchecked successor demand, exact resolver and checker applications, and per-OER correctness replay. Together they isolate structured ownership, promise readiness, cancellation, and field-error publication from Resolution's symbolic-key and runtime-provider machinery.

### Production Resolution

Resolution is the production field-resolution implementation. It retains variable-bearing resolver-fragment selections as symbolic OER keys, instantiates variables once per resolver occurrence, closes symbolic demand before local installation, and treats grounding as readiness rather than rekeying. It supports parent lifting, condition-aware activation, all maintained variable sources, access checks, root-field-reference tails, exact observations, and request-owned structured concurrency.

Resolution shares the architectural roles exposed by the earlier families but owns production-only mechanisms. Every maintained resolver uses one associated Query OER per orchestration for ordinary declared Query fragments and fresh roots for `ctx.query()` and reference targets. Coroutine implementations prepare ordinary object- and Query-side publications before dispatch and rely on promise readiness rather than depth-first dependency order.

## Code Naming Preferences

Use names that expose a common role and qualifiers that reveal an essential difference.

- Prefer **`orchestration` over `orchestrator`** for the coordination activity, its task role, and its demand. Use `OrchestrationTask`, `OrchestrationConstructionDemand`, and `closeOrchestrationConstructionDemand`.
- Use lifecycle verbs for their actual boundary: **`prepare`** claims publications and makes work ready, **`dispatch`** hands work to a dispatcher, **`launch`** starts asynchronous execution, and **`publish`** completes the owned result. A dispatch guard named `dispatched` does not mean the task has started.
- Use **`closedConstructionDemand`** for provenance-preserving demand and **`closedValueSelections`** for its merged value projection. Use `checked` and `unchecked` for construction provenance, and `checked` and `raw` for reads or materialization.
- Use **`requestedSuccessorDemand`** and **`fixedSuccessorInputDemand`** for the two successor-expansion contributions. Qualify the fixed contribution as `fixedResolverInputDemand` or `fixedCheckerInputDemand` where their sources matter.
- Use **`hasRegisteredResolver`** for registry membership and reserve activation terms for the decision to execute an occurrence. Use **`producerSuppliableOnly`** when analysis admits both passive fields and resolver-bearing fields that an ancestor producer may supply.
- Use **`fieldResolver`** and **`fieldChecker`** for the canonical task roles. Retain **`Grounded`** and **`Symbolic`** publication qualifiers where they distinguish identity and binding contracts.
- Use **`prepareRootFieldReferenceInvocation`** and **`invokeRootFieldResolver`** for reference-target work. Preserve the distinction between depth-first `produceAndMaterializeIndependentQueryFragment` and coroutine `launchIndependentQueryFragmentProducer`.
- Use **`invocationRoot`**, **`invocationPath`**, and **`invocationKey`** for a reference target's changing invocation identity. These names distinguish it from the stable consumer publication.
- Use **`Base`** on shared coroutine task base classes. Resolver21–23 retain concrete `Coroutine...Task` names, while production retains `OrchestrationTask` and `FieldResolverTask`.
- Prefer semantic role names such as `operation`, `publication`, `variableBindings`, `fieldValue`, `authoritativeNodeIdentity`, and `passiveValue` over mechanically repeating a type's `Context` or `State` suffix. Preserve distinct terms such as `newResolverKeys` and `newResolverKeyInclusions` when they denote different work.
- Apply the canonical **`Occurrence`**, **`Task`**, **`Logic`**, **`Context`**, **`State`**, and **`Observer`** suffixes consistently. Give helpers at different receiver levels distinct names so a call site reveals the operation and scope.
- Reserve **`Context`** for an immutable bundle of configuration and state references with a stated organizing principle. Use **`Occurrence`** for a concrete semantic identity or location, and **`Task`** or **`Logic`** for executable behavior. The presence of operation or state references does not by itself turn an occurrence into a generic context.

When a helper has a counterpart in a simpler family, match its name and boundary if the responsibility is the same. When it differs, make the semantic reason visible in the name or nearby documentation.

## Feature-Development Workflow

Implement a new semantic feature through the ladder unless its documented family domain makes a rung inapplicable:

1. Establish the behavior in Resolver01–03. Begin at the lowest row that can express it and prove the compact construction, demand, and result contract.
2. Carry the same behavior into Resolver06–08. Make preparation, ordering, task identity, and publication explicit without changing the semantic result.
3. Carry it into Resolver21–23. Establish promise readiness, structured ownership, cancellation, and local failure behavior.
4. Implement it in production Resolution. Add symbolic identities, runtime providers, conditions, access checks, and production integration only where the feature requires them.

If a capability is intentionally excluded from a family, retain an explicit precondition or the closest applicable semantic contract at that rung and explain why the execution structure cannot support the full behavior. Do not silently skip a family because production code is the immediate target.

For debugging, reduce in the same order: Resolver03 for compact selective semantics, Resolver08 for explicit work, Resolver23 for structured suspension, and Resolution for symbolic identity and runtime bindings. Reduce further to the base or fragment row when selective output is not required.

Cross-family agreement localizes a disagreement but is not independent proof. The families share carriers, fixtures, generators, and parts of the correctness machinery. Confirm the relevant application, binding, lifecycle, and occurrence-aware properties independently.

## Refactoring Policy

Shared carrier and API migrations update every maintained family. Prefer one shared model operation or adapter boundary over family-specific compatibility code, but preserve meaningful readiness and ownership differences. Earlier families gain behavior through shared contracts and remain free of separate production integrations.

Bespoke tests remain appropriate for Resolution's symbolic-key policy, runtime variable sources, inclusion behavior, and implementation-specific lifecycle or concurrency guarantees. Shared contracts should state cross-family facts without weakening production-only requirements.
