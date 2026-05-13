package viaduct.engine.api

import graphql.schema.GraphQLSchema
import viaduct.graphql.utils.GraphQLTypeRelations

/**
 * Wraps a [GraphQLSchema] together with its precomputed type-relation metadata.
 *
 * [rels] is expensive to compute, so it is calculated once at construction time and reused for the
 * lifetime of the schema. Callers should therefore create at most one [ViaductSchema] per schema
 * instance rather than constructing fresh copies per request.
 */
data class ViaductSchema(
    val schema: GraphQLSchema,
) {
    // Note: this is quite expensive to compute. This means that we need to be thoughtful
    // about creating instances of this class, and we should only do it once per schema
    // (for the lifetime of that schema).
    val rels: GraphQLTypeRelations = GraphQLTypeRelations(schema)
}
