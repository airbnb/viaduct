package viaduct.api.internal

import graphql.language.Value
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLTypeUtil
import viaduct.api.ViaductFrameworkException
import viaduct.api.ViaductTenantUsageException
import viaduct.api.handleTenantAPIErrors
import viaduct.api.types.InputLike
import viaduct.apiannotations.InternalApi
import viaduct.mapping.graphql.GJValueConv
import viaduct.mapping.graphql.IR

/**
 * Base class for input & field argument GRTs
 */
@InternalApi
@Suppress("UNCHECKED_CAST")
abstract class InputLikeBase : InputLike {
    protected abstract val context: InternalContext
    abstract val inputData: Map<String, Any?>
    abstract val graphQLInputObjectType: GraphQLInputObjectType

    @Suppress("unused")
    protected fun validateInputDataAndThrowAsFrameworkError() {
        try {
            validateInputData(graphQLInputObjectType, inputData)
        } catch (e: IllegalStateException) {
            throw ViaductFrameworkException("Failed to init ${graphQLInputObjectType.name} ($e)", e)
        }
    }

    fun isPresent(fieldName: String): Boolean = inputData.containsKey(fieldName)

    protected fun <T> get(fieldName: String): T =
        handleTenantAPIErrors("InputLikeBase.get failed for ${graphQLInputObjectType.name}.$fieldName") {
            val fieldDefinition = graphQLInputObjectType.getField(fieldName) ?: throw IllegalArgumentException(
                "Field $fieldName not found on type ${graphQLInputObjectType.name}"
            )

            val irValue: IR.Value = if (isPresent(fieldName)) {
                val conv = EngineValueConv(context.schema, fieldDefinition.type, null)
                conv(inputData[fieldName])
            } else if (fieldDefinition.hasSetDefaultValue()) {
                require(fieldDefinition.inputFieldDefaultValue.isLiteral) {
                    "Cannot get the default value for a field without a GJ value literal"
                }
                val gjValue = fieldDefinition.inputFieldDefaultValue.value as Value<*>
                val conv = GJValueConv(fieldDefinition.type)
                conv(gjValue)
            } else {
                IR.Value.Null
            }

            val grtConv = GRTConv(context, fieldDefinition)
            grtConv.invert(irValue) as T
        }

    override fun equals(other: Any?): Boolean {
        return if (other === this) {
            true
        } else if (other is InputLikeBase) {
            inputData == other.inputData
        } else {
            false
        }
    }

    override fun hashCode(): Int {
        return inputData.hashCode()
    }

    abstract class Builder {
        protected abstract val context: InternalContext
        protected abstract val inputData: MutableMap<String, Any?>
        protected abstract val graphQLInputObjectType: GraphQLInputObjectType

        protected fun put(
            fieldName: String,
            value: Any?
        ) = handleTenantAPIErrors("InputLikeBase.Builder.put failed for ${graphQLInputObjectType.name}.$fieldName") {
            val field = requireNotNull(graphQLInputObjectType.getField(fieldName)) {
                "Field $fieldName not found on type ${graphQLInputObjectType.name}"
            }
            val conv = GRTConv(context, field) andThen EngineValueConv(context.schema, field.type, null).inverse()
            inputData.put(fieldName, conv(value))
        }

        @Suppress("unused")
        protected fun validateInputDataAndThrowAsTenantError() {
            try {
                validateInputData(graphQLInputObjectType, inputData)
            } catch (e: IllegalStateException) {
                throw ViaductTenantUsageException("Failed to build ${graphQLInputObjectType.name} ($e)", e)
            }
        }
    }
}

private fun validateInputData(
    graphQLInputObjectType: GraphQLInputObjectType,
    inputData: Map<String, Any?>
) {
    graphQLInputObjectType.fields.forEach { f ->
        if (!inputData.containsKey(f.name)) {
            if (!f.hasSetDefaultValue() && GraphQLTypeUtil.isNonNull(f.type)) {
                throw IllegalStateException("Field ${graphQLInputObjectType.name}.${f.name} is required")
            }
        } else {
            if (inputData[f.name] == null && GraphQLTypeUtil.isNonNull(f.type)) {
                throw IllegalStateException("Field ${graphQLInputObjectType.name}.${f.name} is required")
            }
        }
    }
}
