package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import viaduct.engine.runtime.ObjectEngineResult

/**
 * Runtime selection context for selective OER keys.
 *
 * It carries the uncollected QueryPlan subtree plus the fragments and variables required
 * to re-run collection at each proxy hop against the concrete parent type being traversed.
 */
class ExecutionSelections internal constructor(
    private val schema: GraphQLSchema,
    private val selectionSet: QueryPlan.SelectionSet,
    private val fragments: QueryPlan.Fragments,
    private val variables: CoercedVariables,
    private val collectCache: CollectCache,
) : ObjectEngineResult.Selections {
    private val variableMap = variables.toMap()

    override fun selectionSetForSelection(
        parentType: GraphQLObjectType,
        responseKey: String
    ): ObjectEngineResult.Selections? {
        val collected = collectCache.collect(schema, selectionSet, variables, parentType, fragments)
        val childSelectionSet = collected.selections
            .firstNotNullOfOrNull { selection ->
                (selection as? QueryPlan.CollectedField)
                    ?.takeIf { it.responseKey == responseKey }
                    ?.selectionSet
            }
            ?: return null
        return ExecutionSelections(schema, childSelectionSet, fragments, variables, collectCache)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExecutionSelections) return false
        return selectionSet === other.selectionSet && variableMap == other.variableMap
    }

    override fun hashCode(): Int = 31 * System.identityHashCode(selectionSet) + variableMap.hashCode()

    companion object {
        fun fromParameters(
            schema: GraphQLSchema,
            parameters: ExecutionParameters,
        ): ObjectEngineResult.Selections =
            ExecutionSelections(
                schema = schema,
                selectionSet = parameters.selectionSet,
                fragments = parameters.queryPlan.fragments,
                variables = parameters.coercedVariables,
                collectCache = parameters.constants.collectCache,
            )
    }
}
