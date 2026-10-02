# Argument for [resolver02-demand-closed-result]

## Scope

Fix a finite reasoning world whose schema, values, selections, and resolver registry satisfy their documented invariants, including an acyclic resolver-demand relation. Every runtime variable in this argument is defined `FromArgument`; runtime `FromObjectField` binding is outside the domain. Resolver Query fragments are empty, so independently resolved query-fragment OERs and their correctness witnesses are outside this claim.

The claim concerns the non-selective Resolver02 policy supplied by `semantics/resolvers/resolver02/Resolver.kt` to the shared constructor in `semantics/resolvers/resolver01/DepthFirstResolve.kt`. The public `resolve` entry obtains the canonical Query source through `ResolverRegistry.createRootQueryInput()`, starts with an empty mutable Query OER, and applies complete resolver outputs. The claim does not establish selective one-shot producer completeness, JVM invocation count, scheduling, complexity, or runtime `FromObjectField` behavior.

## Proof Structure

The argument separates demand discovery, same-object dependency availability, and recursive result construction:

1. local closure contains every transitively required resolver selection;
2. dependency order makes each resolver input materializable before its key is resolved; and
3. resolving each selected value satisfies its accumulated subselections and recursively closes every nested result.

For any present result key, `isClosedUnderResolverDemand` imposes no resolver-fragment obligation when the arguments contain an error or the field has no registered resolver. Otherwise, Lemma 1 places the resolver's applicable instantiated and grounded object-fragment selections in the closed forest, Lemma 2 makes that input available during construction, and Lemma 3 shows that the completed containing OER satisfies those selections and that every value below the key is recursively closed.

The ordering lemma is needed to show that Resolver02 can construct the claimed result without materializing an absent input. Once construction is complete, ordering history is not observable by `isClosedUnderResolverDemand`; the extensional closure conclusion follows from the demand and recursive-construction lemmas.

## Lemma 1: Transitive Resolver Demand Is Included

Let `closedSelections = source.closeConstructionDemand(operation, occurrence, selections)` for one concrete object occurrence. The result contains every applicable occurrence from the incoming selections. For every ground key in `closedSelections` whose arguments contain no error and whose field has a registered resolver, it also contains every applicable selection obtained by instantiating the resolver's fixed object fragment at `path + key`, binding its `FromArgument` variables, and specializing it to the concrete object type. The same property holds transitively for resolver keys introduced by those selections.

The termination measure is the finite set of reachable resolver keys that have not yet been expanded. Each closure step chooses currently present unexpanded resolver keys, adds their object fragments, and records those keys as expanded. A key is expanded at most once. Resolver fragments and the reachable exact-key universe are finite, so the unexpanded set strictly decreases until no expansion remains.

Argument-error keys are deliberately not expanded. This agrees with `isClosedUnderResolverDemand`, which exempts a present key from resolver-fragment conformance when its arguments contain an error.

Concrete-type filtering and specialization through `SelectionForest.merge(type)` agree with the applicability and concrete-key operations used by `conformsToSelections`. Closure therefore contributes the exact concrete keys whose cells satisfy each applicable resolver fragment, rather than abstract or inapplicable field selections.

## Lemma 2: Dependency Order Makes Resolver Inputs Available

Before a key is resolved, every sibling subtree required by its instantiated and grounded object fragment is present in the resolved prefix OER.

Proceed by induction over the prefix of the ordering returned by `SiblingDependencyLogic.order`. A key is ready only when `dependenciesOf(key, unresolved)` finds no required sibling in the unresolved suffix. Lemma 1 guarantees that every applicable required sibling belongs to the complete closed key set. A required sibling absent from the unresolved suffix must therefore belong to the resolved prefix.

The prefix induction invariant, using Lemma 3 for keys already resolved, says that each such sibling cell also satisfies its accumulated subselections. The prefix OER consequently conforms to the current resolver's instantiated and grounded object fragment, so the Resolver01–23 `materializeResolverInput` wrapper and its shared `materializeResult` projection are defined for that resolver input.

The canonical registry's acyclic resolver-demand invariant guarantees that every nonempty unresolved set contains a ready key. `SiblingDependencyLogic.order` rejects a nonempty set with no ready member as a cycle. Independent ready keys may appear in any order because no member requires another member of that ready set.

Node resolution needs no separate dependency-order case. A Node-valued source producer returns a root-field reference to `Query.node`; after the producer is ready, Resolver02 executes that grounded reference inline through the same root-reference mechanism and recursively resolves the returned object's demanded fields. The `Query.node` target has an empty object fragment, so it adds no sibling prerequisite to this ordering. Unequal argument tuples on ordinary Node-valued source fields remain distinct ground producer keys.

## Lemma 3: Recursive Resolution Satisfies Selections And Closes Descendants

After a key is resolved, its cell value satisfies every subselection accumulated for that key, and every object or list nested within that value satisfies `isClosedUnderResolverDemand`.

The argument is a well-founded mutual induction over passive `resolvePassiveValues` construction, retained object continuations, the finite dependency-order prefix, and nested result structure. Null, error, and simple values create no descendant demand. A list applies the induction hypothesis independently at every exact `ListEngineResult.Index` path.

For an object output, `SharedPassiveValueResolutionLogic` allocates one stable mutable target OER and creates its prepared orchestration task before resolving passive fields recursively. The task carries the source object, target OER, exact path, and closed demand. Passive traversal dispatches children before their containing object; `DepthFirstTaskDispatcher.resolveOrchestrationFringe` consumes that accumulated fringe after each field, before the next dependent sibling. Containing cells and list positions retain the same target OER identities while active child fields complete; no ancestor or passive subtree is rebuilt or replaced.

At one object occurrence, Lemma 1 establishes the complete local resolver obligations and Lemma 2 supplies materializable inputs in dependency order. The induction hypotheses establish selection satisfaction and closure below every resulting cell.

The dependency-prefix and recursive-result inductions are mutually supporting but not circular: input availability for the current key depends only on strictly earlier keys in the finite prefix, while recursive closure depends on strictly smaller value-construction subderivations or separately retained descendant occurrences.

## Conclusion

Resolver02's Query result contains every activated resolver's complete fixed input demand at the corresponding OER occurrence, and the property holds recursively through all object and list results. Therefore the returned `ObjectEngineResult` satisfies `isClosedUnderResolverDemand(operation)` within the stated finite, acyclic, `FromArgument`-only domain.
