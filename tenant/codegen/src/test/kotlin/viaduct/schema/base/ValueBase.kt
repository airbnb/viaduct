package viaduct.schema.base

import com.fasterxml.jackson.annotation.JsonIgnore
import graphql.schema.DataFetchingEnvironment

abstract class ValueBase(
    @JsonIgnore val objectTypeName: String
) : ViaductGeneratedObject {
    @JsonIgnore
    protected lateinit var backingMap: Map<String, Any?>

    open suspend fun resolveFieldInternalOnlyDoNotUse(
        fieldName: String,
        arguments: FieldArguments,
        env: DataFetchingEnvironment?
    ): Any? {
        throw ResolveFieldException()
    }

    override fun getFieldResolverInternalOnlyDoNotUse(
        fieldName: String,
        arguments: FieldArguments,
        env: DataFetchingEnvironment?
    ): FieldResolver {
        throw GetFieldResolverException()
    }

    @get:JsonIgnore
    val allFieldNames: Set<String> get() = backingMap.keys

    fun getField(fieldName: String): Any? = backingMap[fieldName]

    override fun hashCode() = backingMap.hashCode()

    override fun equals(other: Any?) =
        if (other === this) {
            true
        } else if (other is ValueBase) {
            backingMap == other.backingMap
        } else {
            false
        }

    class GetFieldResolverException :
        Exception("Attempting to call getFieldResolverInternalOnlyDoNotUse in the stub ValueBase")

    class ResolveFieldException :
        Exception("Attempting to call resolveFieldInternalOnlyDoNotUse in the stub ValueBase")
}
