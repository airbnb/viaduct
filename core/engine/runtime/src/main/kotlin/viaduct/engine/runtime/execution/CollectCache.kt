package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches the results of field collection to optimize performance during execution.
 *
 * Both [FieldResolver] and [FieldCompleter] need to collect fields for the same objects.
 * Without caching, this work would be duplicated for every object in the response.
 *
 * The cache uses a specialized key that relies on **identity equality** for
 * its components ([GraphQLObjectType] and [QueryPlan.SelectionSet]). This is safe because:
 * 1. The [QueryPlan] (and its [QueryPlan.SelectionSet] nodes) is immutable and shared.
 * 2. The cache is scoped to a single execution (via [ExecutionParameters.Constants]), where variables are constant.
 *
 * By avoiding expensive structural equality checks and repeated collection logic,
 * this cache significantly reduces overhead in the hot path of execution.
 */
internal class CollectCache {
    private class CollectKey(
        val parentType: GraphQLObjectType,
        val selectionSet: QueryPlan.SelectionSet
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CollectKey) return false
            return parentType === other.parentType &&
                selectionSet === other.selectionSet
        }

        override fun hashCode(): Int {
            val a = System.identityHashCode(parentType)
            val b = System.identityHashCode(selectionSet)
            return 31 * a + b
        }
    }

    private val map = ConcurrentHashMap<CollectKey, QueryPlan.SelectionSet>()

    fun collect(
        schema: GraphQLSchema,
        selectionSet: QueryPlan.SelectionSet,
        variables: CoercedVariables,
        parentType: GraphQLObjectType,
        fragments: QueryPlan.Fragments
    ): QueryPlan.SelectionSet {
        val key = CollectKey(parentType, selectionSet)
        return map.computeIfAbsent(key) {
            CollectFields.shallowStrictCollect(schema, selectionSet, variables, parentType, fragments)
        }
    }
}
