package viaduct.engine.runtime2.execution

import graphql.GraphQLContext
import graphql.execution.ExecutionContext
import graphql.schema.GraphQLSchema
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.registry.Assumptions

/** Supplies construction demand from an already validated operation. */
interface Runtime2OperationSelectionProvider {
    /** Applied before validation; preparsed caches must retain any requested validation artifact. */
    fun configureContext(context: GraphQLContext.Builder) {}

    fun selections(
        world: Assumptions,
        sourceSchema: GraphQLSchema,
        context: ExecutionContext,
    ): SelectionForest
}
