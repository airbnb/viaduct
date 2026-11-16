package viaduct.engine.runtime2.execution

import graphql.GraphQLContext
import graphql.execution.ExecutionContext
import graphql.normalized.NormalizedDocument
import graphql.schema.GraphQLSchema
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.schema.NormalizedSelectionSource

/**
 * Uses the validated ND for queries, subscriptions and ordered mutations. Missing cached graphs
 * fail explicitly rather than being rebuilt after validation.
 */
object NormalizedOperationSelectionProvider : Runtime2OperationSelectionProvider {
    override fun configureContext(context: GraphQLContext.Builder) {
        context.put(NormalizedDocument.ENABLED, true)
    }

    override fun selections(
        world: Assumptions,
        sourceSchema: GraphQLSchema,
        context: ExecutionContext,
    ): SelectionForest {
        val document = requireNotNull(context.normalizedDocument) {
            "Enable NormalizedDocument before validation and retain it in the preparsed cache"
        }
        return NormalizedSelectionSource(
            document,
            sourceSchema,
            world.schema,
            context.coercedVariables,
            context.graphQLContext,
            context.locale,
        ).selections(context.operationDefinition)
    }
}
