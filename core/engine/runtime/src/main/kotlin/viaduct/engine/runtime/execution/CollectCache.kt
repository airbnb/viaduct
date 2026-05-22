package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.runtime.execution.QueryPlan.Selection
import viaduct.engine.runtime.execution.QueryPlan.SelectionVariableReference
import viaduct.engine.runtime.execution.constraints.Constraints
import viaduct.utils.collections.MaskedSet

/**
 * Caches the results of field collection to optimize performance during execution.
 *
 * Both [FieldResolver] and [FieldCompleter] need to collect fields for the same objects.
 * Without caching, this work would be duplicated for every object in the response.
 *
 * The cache uses a specialized key that relies on **identity equality** for
 * its stable components ([GraphQLObjectType] and [QueryPlan.SelectionSet]). This is safe because:
 * 1. The [QueryPlan] (and its [QueryPlan.SelectionSet] nodes) is immutable and shared.
 * 2. Runtime variables that participate in field collection are included structurally because
 *    different child executions in the same request can run the same plan with different
 *    @skip/@include values.
 *
 * By avoiding expensive structural equality checks and repeated collection logic,
 * this cache significantly reduces overhead in the hot path of execution.
 */
internal class CollectCache {
    private class CollectKey(
        val parentType: GraphQLObjectType,
        val selectionSet: QueryPlan.SelectionSet,
        val variables: Map<String, Any?>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CollectKey) return false
            return parentType === other.parentType &&
                selectionSet === other.selectionSet &&
                variables == other.variables
        }

        override fun hashCode(): Int {
            val a = System.identityHashCode(parentType)
            val b = System.identityHashCode(selectionSet)
            return (31 * a + b) * 31 + variables.hashCode()
        }
    }

    private val map = ConcurrentHashMap<CollectKey, QueryPlan.SelectionSet>()

    // The primary cache key needs collection-sensitive variable values, so we need to
    // discover the relevant variable names before we can query it. Keep that discovery
    // cached separately so cache hits do not rewalk fragments and constraints.
    private val collectionVariableNamesBySelectionSet = ConcurrentHashMap<CollectionVariableNamesKey, Set<String>>()

    fun collect(
        schema: GraphQLSchema,
        selectionSet: QueryPlan.SelectionSet,
        variables: CoercedVariables,
        parentType: GraphQLObjectType,
        fragments: QueryPlan.Fragments
    ): QueryPlan.SelectionSet {
        val key = CollectKey(
            parentType,
            selectionSet,
            collectionVariableValues(selectionSet, variables, parentType, fragments)
        )
        return map.computeIfAbsent(key) {
            CollectFields.shallowStrictCollect(schema, selectionSet, variables, parentType, fragments)
        }
    }

    private fun collectionVariableValues(
        selectionSet: QueryPlan.SelectionSet,
        variables: CoercedVariables,
        parentType: GraphQLObjectType,
        fragments: QueryPlan.Fragments
    ): Map<String, Any?> {
        val names = selectionSet.collectionVariableNames(parentType, fragments)
        return if (names.isEmpty()) emptyMap() else names.associateWith { variables.get(it) }
    }

    private class CollectionVariableNamesKey(
        val parentType: GraphQLObjectType,
        val selectionSet: QueryPlan.SelectionSet,
        val fragments: QueryPlan.Fragments
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CollectionVariableNamesKey) return false
            return parentType === other.parentType &&
                selectionSet === other.selectionSet &&
                fragments === other.fragments
        }

        override fun hashCode(): Int {
            val a = System.identityHashCode(parentType)
            val b = System.identityHashCode(selectionSet)
            val c = System.identityHashCode(fragments)
            return (31 * a + b) * 31 + c
        }
    }

    /**
     * Runtime variable names that can affect shallow field collection for [selectionSet].
     *
     * Query plans record variable references on selections and fragment definitions, but field
     * collection only reads variables through @skip/@include constraints. Field argument variables
     * are resolved later by field execution and must not affect collection caching.
     */
    private fun QueryPlan.SelectionSet.collectionVariableNames(
        parentType: GraphQLObjectType,
        fragments: QueryPlan.Fragments
    ): Set<String> =
        collectionVariableNamesBySelectionSet.computeIfAbsent(CollectionVariableNamesKey(parentType, this, fragments)) {
            findCollectionVariableNames(parentType, fragments)
        }

    /**
     * Walk the same shallow selection surface that [CollectFields] will inspect for [parentType].
     * Fields contribute their own variable references, but not references from their subselections.
     * Inline fragments and fragment spreads are expanded because their children are collected into
     * this same result. Each selection is solved with a variables-free [Constraints.Ctx] first, so
     * type-pruned branches and literal @skip/@include directives cannot add irrelevant variables to
     * the cache key.
     */
    private fun QueryPlan.SelectionSet.findCollectionVariableNames(
        parentType: GraphQLObjectType,
        fragments: QueryPlan.Fragments
    ): Set<String> =
        buildSet {
            addCollectionVariableNames(enclosingVariableReferences)
            forEachVariableReferencesVisibleToCollection(parentType, fragments) { references ->
                addCollectionVariableNames(references)
            }
        }

    private fun MutableSet<String>.addCollectionVariableNames(references: List<SelectionVariableReference>) {
        references.forEach { reference ->
            if (reference.kind == SelectionVariableReference.Kind.CONDITIONAL_DIRECTIVE) {
                add(reference.name)
            }
        }
    }

    private fun QueryPlan.SelectionSet.forEachVariableReferencesVisibleToCollection(
        parentType: GraphQLObjectType,
        fragments: QueryPlan.Fragments,
        visit: (List<SelectionVariableReference>) -> Unit
    ) {
        val ctx = Constraints.Ctx(variables = null, parentTypes = MaskedSet(listOf(parentType)))
        val visitedFragments = mutableSetOf<String>()
        val queue = ArrayDeque(selections)

        while (queue.isNotEmpty()) {
            when (val selection = queue.removeFirst()) {
                is QueryPlan.CollectedField -> Unit

                is QueryPlan.Field -> {
                    if (selection.isDroppedFor(ctx)) continue
                    visit(selection.variableReferences)
                }

                is QueryPlan.InlineFragment -> {
                    if (selection.isDroppedFor(ctx)) continue
                    visit(selection.variableReferences)
                    queue.addAll(0, selection.selectionSet.selections)
                }

                is QueryPlan.FragmentSpread -> {
                    if (selection.isDroppedFor(ctx)) continue
                    visit(selection.variableReferences)
                    if (visitedFragments.add(selection.name)) {
                        val fragmentDefinition = requireNotNull(fragments[selection.name]) { "Fragment `${selection.name}` is not defined" }
                        visit(fragmentDefinition.variableReferences)
                        queue.addAll(0, fragmentDefinition.selectionSet.selections)
                    }
                }
            }
        }
    }

    private fun Selection.isDroppedFor(ctx: Constraints.Ctx): Boolean = constraints.solve(ctx) == Constraints.Resolution.Drop
}
