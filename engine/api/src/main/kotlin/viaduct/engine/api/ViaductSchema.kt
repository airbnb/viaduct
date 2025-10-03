package viaduct.engine.api

import graphql.schema.GraphQLSchema
import viaduct.graphql.utils.GraphQLTypeRelations

data class ViaductSchema(
    val schema: GraphQLSchema
) {
    // Note: this is quite expensive to compute. This means that we need to be thoughtful
    // about creating instances of this class, and we should only do it once per schema
    // (for the lifetime of that schema).
    val rels: GraphQLTypeRelations = GraphQLTypeRelations(schema)
}
