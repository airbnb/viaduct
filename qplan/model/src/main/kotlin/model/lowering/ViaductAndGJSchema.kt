package model.lowering

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import model.requireType
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

internal fun ViaductAndGJSchema.sourceCompositeType(type: ViaductSchema.CompositeTypeDef): GraphQLCompositeType {
    require(loweredSchema.requireType(type.name) == type) {
        "${type.name} is not canonical in this schema"
    }
    return graphQLSchema.getType(type.name) as? GraphQLCompositeType
        ?: throw IllegalArgumentException("${type.name} is not a source composite type")
}

internal val ViaductAndGJSchema.objectTypes: List<ViaductSchema.Object>
    get() =
        graphQLSchema.allTypesAsList
            .filterIsInstance<GraphQLObjectType>()
            .filterNot { it.name.startsWith("__") }
            .map { loweredSchema.requireType(it.name) as ViaductSchema.Object }
