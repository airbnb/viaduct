# Argument for `resolver07-demand-closed-result`

## Scope

Fix a finite reasoning world whose schema, values, selections, and resolver registry satisfy their documented invariants, including an acyclic resolver-demand relation. Every runtime variable in this argument is defined `FromArgument`; runtime `FromObjectField` binding is outside the domain. Resolver Query fragments are empty, so independently resolved Query-fragment OERs and their correctness witnesses are outside this claim. The schema has no `@parent` fields and the registry has no access checkers.

The claim concerns the non-selective policy in [resolver07/Resolver.kt](../../../src/support/kotlin/viaduct/engine/runtime2/resolvers/resolver07/Resolver.kt), supplied to [DepthFirstReactor](../../../src/support/kotlin/viaduct/engine/runtime2/resolvers/resolver06/DepthFirstReactor.kt). The public `resolve` entry obtains the canonical Query source through `ResolverRegistry.createRootQueryInput()`, starts with an empty mutable Query OER, and applies complete resolver outputs. The claim does not establish selective one-shot producer completeness, JVM invocation count, complexity, or runtime `FromObjectField` behavior.

## Argument Structure

The argument separates demand discovery, same-object dependency availability, and queued result construction:

1. local closure contains every transitively required resolver selection;
2. dependency order and queue priority make each resolver input materializable before its task runs; and
3. draining the queue satisfies accumulated subselections and closes every nested result.

For any present result key, `isClosedUnderResolverDemand` imposes no resolver-fragment obligation when the arguments contain an error or the field has no registered resolver. Otherwise, Lemma 1 places the resolver's applicable instantiated and grounded object-fragment selections in the closed forest, Lemma 2 makes that input available during construction, and Lemma 3 shows that the completed containing OER satisfies those selections and that every value below the key is recursively closed.

The ordering lemma establishes that Resolver07 can construct the claimed result without materializing an absent input. Once construction is complete, scheduling history is not observable by `isClosedUnderResolverDemand`; the extensional closure conclusion follows from demand inclusion and completed descendant construction.

## Lemma 1: Transitive Resolver Demand Is Included

For one concrete object occurrence, `source.closeOrchestrationConstructionDemand` closes the paired object and associated Query demand. In this claim's empty-Query-fragment domain, the object-rooted value projection contains every applicable occurrence from the incoming selections. For every ground key whose arguments contain no error and whose field has a registered resolver, it also contains every applicable selection obtained by instantiating the resolver's fixed object fragment at `path + key`, binding its `FromArgument` variables, and specializing it to the concrete object type. The same property holds transitively for resolver keys introduced by those selections.

Each closure step expands previously unexpanded resolver keys, adds their fixed object-fragment demand, and records those keys as expanded. The reachable exact-key universe and fragments are finite, and each key is expanded at most once, so closure reaches a fixed point.

Argument-error keys are deliberately not expanded. This agrees with `isClosedUnderResolverDemand`, which exempts a present key from resolver-fragment conformance when its arguments contain an error. Concrete-type filtering and `SelectionForest.merge(type)` agree with the applicability and concrete-key operations used by `conformsToSelections`, so closure contributes the concrete cells needed by each applicable fragment.

## Lemma 2: Queue Order Makes Resolver Inputs Available

Orchestration prepares field tasks in the topological order returned by `SiblingDependencyLogic.order`. A key is ready in that ordering only when no required sibling remains in the unresolved suffix. Lemma 1 guarantees that each applicable prerequisite belongs to the closed key set. A prerequisite outside the suffix is therefore already supplied passively or occurs earlier in the ordering. Acyclic resolver demand guarantees a ready member in each nonempty unresolved set; ordering rejects a cycle rather than attempting construction through it.

Sibling field tasks have the same Query-OER depth, task-path depth, and task-kind priority. Their queue insertion order therefore preserves this topological order. When an earlier producer returns an object or list, its passive construction retains stable descendant OERs and queues their active work. Descendant tasks and list-element reference tasks have greater task-path depth than the later sibling consumer, so they run first. Passive descendants discovered before their containing orchestration are held until that orchestration runs; releasing them then makes their deeper work eligible before the consumer.

Induct over the finite dependency-order prefix, using Lemma 3 for prerequisite subtrees. Before a consumer task materializes its input, each prerequisite cell and its accumulated descendant selections have completed. The numbered reference resolvers' `materializeResolverInput` wrapper and shared `materializeResult` projection are consequently defined for that input.

A Node-valued source producer returns a root-field reference to `Query.node`. Its field task invokes that grounded target inline and queues active work beneath the returned value through ordinary passive resolution. The target has an empty object fragment, so it introduces no sibling prerequisite of its own. Unequal argument tuples on ordinary Node-valued source fields remain distinct ground producer keys.

## Lemma 3: Draining The Queue Satisfies Selections And Closes Descendants

Passive resolution handles null, error, and simple values without descendant work. It constructs lists at exact `ListEngineResult.Index` paths and allocates one stable mutable OER for each object occurrence. Each object's orchestration closes demand before passive descent; containing cells and list positions retain that same OER while its active fields are populated.

For an object occurrence, Lemma 1 supplies complete local resolver obligations and Lemma 2 makes inputs available when its field tasks run. A task publishes its passive output into its claimed cell and queues any descendant work. Publication of that cell alone does not mean its entire subtree has completed. The reactor continues draining deeper work before dependent siblings and returns only after all dispatched tasks finish and no held child orchestration remains. Its completion checks also require the closed object- and Query-side value cells of every dispatched orchestration to be complete.

The proof is a well-founded mutual induction over the finite sibling dependency prefixes and finite descendant construction. Input availability depends on earlier prerequisites and their descendants; it does not depend on the current consumer's output. Each retained occurrence has one orchestration, and active publications are claimed once. No ancestor or passive subtree is rebuilt to satisfy later work. Thus all accumulated selections and resolver-input obligations hold throughout the completed result graph.

## Conclusion

Resolver07's Query result contains every activated resolver's complete fixed input demand at the corresponding OER occurrence, recursively through object and list results. Therefore the returned `ObjectEngineResult` satisfies `isClosedUnderResolverDemand(operation)` within the stated finite, acyclic, `FromArgument`-only domain.
