package viaduct.engine.api

import graphql.schema.GraphQLCompositeType

/**
 * A reference to an unresolved root field.
 */
interface RootFieldReference {
    /** Path from the root type (e.g. Query) to the field, e.g. ["_factories", "translatedText", "create"] */
    val rootFieldPath: List<String>

    /** The type of the field */
    val type: GraphQLCompositeType

    /** Arguments to the field */
    val args: Map<String, Any?>
}
