package viaduct.api.types

import graphql.schema.GraphQLInputObjectType
import viaduct.engine.api.ViaductSchema

/**
 * Tagging interface for input types
 */
interface Input : InputLike {
    companion object {
        /**
         * Returns input type [name] from [schema].
         *
         * @throws IllegalArgumentException if [name] doesn't exist or is
         * not an input type.
         */
        fun inputType(
            name: String,
            schema: ViaductSchema
        ): GraphQLInputObjectType {
            val result = requireNotNull(schema.schema.getType(name)) {
                "Type $name does not exist in schema."
            }
            return requireNotNull(result as? GraphQLInputObjectType) {
                "Type $name ($result) is not an input type."
            }
        }
    }
}
