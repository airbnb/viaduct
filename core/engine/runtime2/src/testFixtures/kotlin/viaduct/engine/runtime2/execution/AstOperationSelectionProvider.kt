package viaduct.engine.runtime2.execution

import graphql.execution.ExecutionContext
import graphql.schema.GraphQLSchema
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.schema.AstSelectionParser

/** Independent eager AST oracle, deliberately excluded from the production engine. */
object AstOperationSelectionProvider : Runtime2OperationSelectionProvider {
    override fun selections(
        world: Assumptions,
        sourceSchema: GraphQLSchema,
        context: ExecutionContext
    ): SelectionForest =
        AstSelectionParser(sourceSchema, world.schema, emptyMap()).selectionsFrom(
            context.operationDefinition,
            context.coercedVariables,
            context.graphQLContext,
            context.locale,
            context.fragmentsByName,
        )
}
