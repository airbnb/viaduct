package viaduct.errors

import graphql.schema.GraphQLObjectType
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.StableApi

/**
 * Thrown when tenant code attempts to access a field that was not set.
 *
 * Note: the contents of the [details] parameter are not guaranteed to be stable.
 */
@StableApi
class UnsetFieldException
    @InternalApi
    constructor(
        private val fieldName: String,
        internal val objectType: GraphQLObjectType,
        private val details: String? = null,
    ) : TenantUsageException(buildMessage(fieldName, objectType, details)) {
        /** The name of the GraphQL type that contains the unset field */
        val typeName: String = objectType.name

        companion object {
            private fun buildMessage(
                fieldName: String,
                objectType: GraphQLObjectType,
                details: String?
            ): String {
                val isField = objectType.getField(fieldName) != null
                val extra = details?.let { ": $it" } ?: ""
                return if (isField) {
                    "Attempted to access field ${objectType.name}.$fieldName but it was not set$extra"
                } else {
                    "Attempted to access aliased field $fieldName but it was not set$extra"
                }
            }
        }
    }
