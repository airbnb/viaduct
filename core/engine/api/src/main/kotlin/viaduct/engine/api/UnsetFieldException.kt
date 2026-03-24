package viaduct.engine.api

import graphql.schema.GraphQLObjectType

class UnsetFieldException(private val fieldName: String, private val objectType: GraphQLObjectType, private val details: String? = null) : Exception() {
    private val isField = objectType.getField(fieldName) != null
    override val message: String
        get() {
            val extra = details?.let { ": $it" }
            return if (isField) {
                "Attempted to access field ${objectType.name}.$fieldName but it was not set$extra"
            } else {
                "Attempted to access aliased field $fieldName but it was not set$extra"
            }
        }
}
