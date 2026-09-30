package model.lowering

import graphql.schema.GraphQLSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.GraphQLTypeRelations

/** Keeps source-schema relations and canonical lowered definitions tied to one source schema. */
data class ViaductAndGJSchema internal constructor(
    val graphQLSchema: GraphQLSchema,
    val typeRelations: GraphQLTypeRelations,
    val loweredSchema: ViaductSchema,
) {
    companion object {
        @JvmStatic
        fun fromGraphQLSchema(sourceSchema: GraphQLSchema): ViaductAndGJSchema =
            ViaductAndGJSchema(
                graphQLSchema = sourceSchema,
                typeRelations = GraphQLTypeRelations(sourceSchema),
                loweredSchema = lowerSchema(sourceSchema),
            )
    }
}
