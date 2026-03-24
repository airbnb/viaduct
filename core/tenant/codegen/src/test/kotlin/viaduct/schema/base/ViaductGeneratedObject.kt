package viaduct.schema.base

import graphql.schema.DataFetchingEnvironment

class ResolveFieldWithPolicyCheckInternalOnlyDoNotUseException : Exception()

class GetFieldResolverInternalOnlyDoNotUseException : Exception()

class GetFieldInputTypeParserInternalOnlyDoNotUseException : Exception()

class ViaductGeneratedObjectLoadException : Exception()

interface ViaductGeneratedObject {
    fun sourceForDerivedFields(): Any = this

    fun getGraphQLName(): String

    fun getFieldResolverInternalOnlyDoNotUse(
        fieldName: String,
        arguments: FieldArguments,
        env: DataFetchingEnvironment?
    ): FieldResolver {
        throw GetFieldResolverInternalOnlyDoNotUseException()
    }

    fun getFieldInputTypeParserInternalOnlyDoNotUse(
        fieldName: String,
        arguments: FieldArguments
    ): () -> ViaductInputType? =
        {
            throw GetFieldInputTypeParserInternalOnlyDoNotUseException()
        }
}

class FieldResolver

class FieldArguments(
    val values: Map<String, Any?>,
)
