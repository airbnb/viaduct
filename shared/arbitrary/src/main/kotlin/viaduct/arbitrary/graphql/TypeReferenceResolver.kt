package viaduct.arbitrary.graphql

import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLUnmodifiedType
import java.lang.IllegalArgumentException

/**
 * A TypeReferenceResolver can be used to resolve a [GraphQLTypeReference] into a
 * [GraphQLUnmodifiedType].
 *
 * This is useful for generating default values for input types that refer to types that
 * may not have been generated yet.
 */
interface TypeReferenceResolver : (GraphQLTypeReference) -> GraphQLUnmodifiedType? {
    fun resolve(t: GraphQLTypeReference): GraphQLUnmodifiedType? = invoke(t)

    /** Throws [IllegalArgumentException] if the supplied reference cannot be resolved */
    fun resolveOrThrow(t: GraphQLTypeReference): GraphQLUnmodifiedType = this.invoke(t) ?: throw IllegalArgumentException("Cannot resolve type reference ${t.name}")

    /** Build a [TypeReferenceResolver] from a [GraphQLTypes] */
    class fromTypes(
        val types: GraphQLTypes
    ) : TypeReferenceResolver {
        override fun invoke(ref: GraphQLTypeReference): GraphQLUnmodifiedType? = types.resolve(ref)
    }

    /** Build a [TypeReferenceResolver] from a [GraphQLSchema] */
    class fromSchema(
        val schema: GraphQLSchema
    ) : TypeReferenceResolver {
        override fun invoke(ref: GraphQLTypeReference): GraphQLUnmodifiedType? = schema.getType(ref.name)?.let { it as GraphQLUnmodifiedType }
    }

    companion object {
        /** a [TypeReferenceResolver] that is expected to be never be called and always throws */
        val none = object : TypeReferenceResolver {
            override fun invoke(p1: GraphQLTypeReference): GraphQLUnmodifiedType = throw UnsupportedOperationException("Unsupported operation: resolve a type from `TypeReferenceResolver.empty`")
        }
    }
}
