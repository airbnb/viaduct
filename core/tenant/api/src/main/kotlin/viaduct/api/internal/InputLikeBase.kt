package viaduct.api.internal

import graphql.GraphQLContext
import graphql.execution.ValuesResolver
import graphql.language.Value
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.InputValueWithState
import java.util.Locale
import viaduct.api.types.FieldPresenceProbe
import viaduct.api.types.InputLike
import viaduct.apiannotations.Attribution
import viaduct.apiannotations.AttributionContext
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineSchema
import viaduct.errors.FrameworkException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleFrameworkErrors
import viaduct.mapping.graphql.GJValueConv
import viaduct.mapping.graphql.IR
import viaduct.tenant.runtime.jvm.InputDataValidator

/**
 * Base class for input & field argument GRTs
 */
@InternalApi
@Suppress("UNCHECKED_CAST")
abstract class InputLikeBase : InputLike, FieldPresenceProbe {
    protected abstract val context: InternalContext
    abstract val inputData: Map<String, Any?>
    abstract val graphQLInputObjectType: GraphQLInputObjectType

    @Suppress("unused")
    protected fun validateInputDataAndThrowAsFrameworkError() {
        try {
            InputDataValidator.validateFields(graphQLInputObjectType, inputData)
        } catch (e: IllegalStateException) {
            throw FrameworkException("Failed to init ${graphQLInputObjectType.name} ($e)", e)
        }
    }

    override fun isFieldPresent(fieldName: String): Boolean =
        inputData.containsKey(fieldName) ||
            graphQLInputObjectType.getField(fieldName)?.hasSetDefaultValue() == true

    protected fun <T> get(fieldName: String): T =
        handleFrameworkErrors("InputLikeBase.get failed for ${graphQLInputObjectType.name}.$fieldName") {
            readFieldValue(fieldName)
        }

    @Attribution(AttributionContext.FRAMEWORK)
    private fun <T> readFieldValue(fieldName: String): T {
        // FrameworkException, not TenantUsageException: fieldName is a hardcoded literal baked in
        // by the code generator from the schema — never a runtime value from the operation. A
        // missing field indicates GRT/schema drift, not tenant API misuse.
        val fieldDefinition = graphQLInputObjectType.getField(fieldName) ?: throw FrameworkException(
            "Field $fieldName not found on type ${graphQLInputObjectType.name}"
        )

        val irValue: IR.Value = if (inputData.containsKey(fieldName)) {
            val conv = EngineValueConv(context.schema, fieldDefinition.type, null)
            conv(inputData[fieldName])
        } else if (fieldDefinition.hasSetDefaultValue()) {
            defaultValueToIR(fieldDefinition.inputFieldDefaultValue, fieldDefinition.type, context.schema)
        } else {
            IR.Value.Null
        }

        val grtConv = context.grtConvFactory.createForInputField(context, fieldDefinition)
        return grtConv.invert(irValue) as T
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
        ) = handleFrameworkErrors("InputLikeBase.Builder.put failed for ${graphQLInputObjectType.name}.$fieldName") {
            writeFieldValue(fieldName, value)
        }

        @Attribution(AttributionContext.FRAMEWORK)
        private fun writeFieldValue(
            fieldName: String,
            value: Any?
        ) {
            // FrameworkException for the same reason as readFieldValue above.
            val field = graphQLInputObjectType.getField(fieldName)
                ?: throw FrameworkException("Field $fieldName not found on type ${graphQLInputObjectType.name}")
            val conv = context.grtConvFactory.createForInputField(context, field) andThen EngineValueConv(context.schema, field.type, null).inverse()
            inputData.put(fieldName, conv(value))
        }

        @Suppress("unused")
        protected fun validateInputDataAndThrowAsTenantError() {
            try {
                InputDataValidator.validateFields(graphQLInputObjectType, inputData)
            } catch (e: IllegalStateException) {
                throw TenantUsageException("Failed to build ${graphQLInputObjectType.name} ($e)", e)
            }
        }
    }
}

internal fun defaultValueToIR(
    default: InputValueWithState,
    type: GraphQLInputType,
    schema: EngineSchema
): IR.Value =
    if (default.isLiteral) {
        GJValueConv(type)(default.value as Value<*>)
    } else {
        val value = ValuesResolver.valueToInternalValue(default, type, GraphQLContext.getDefault(), Locale.getDefault())
        EngineValueConv(schema, type, null)(value)
    }
