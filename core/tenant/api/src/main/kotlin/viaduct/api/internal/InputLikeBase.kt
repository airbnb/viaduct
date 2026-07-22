package viaduct.api.internal

import graphql.language.Value
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLTypeUtil
import viaduct.api.types.FieldPresenceProbe
import viaduct.api.types.InputLike
import viaduct.apiannotations.Attribution
import viaduct.apiannotations.AttributionContext
import viaduct.apiannotations.InternalApi
import viaduct.errors.FrameworkException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleFrameworkErrors
import viaduct.mapping.graphql.GJValueConv
import viaduct.mapping.graphql.IR

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
            validateInputData(graphQLInputObjectType, inputData)
        } catch (e: IllegalStateException) {
            throw FrameworkException("Failed to init ${graphQLInputObjectType.name} ($e)", e)
        }
    }

    override fun isFieldPresent(fieldName: String): Boolean = inputData.containsKey(fieldName)

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

        val irValue: IR.Value = if (isFieldPresent(fieldName)) {
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
                validateInputData(graphQLInputObjectType, inputData)
            } catch (e: IllegalStateException) {
                throw TenantUsageException("Failed to build ${graphQLInputObjectType.name} ($e)", e)
            }
        }
    }
}

private fun validateInputData(
    graphQLInputObjectType: GraphQLInputObjectType,
    inputData: Map<String, Any?>
) {
    if (graphQLInputObjectType.isOneOf) {
        // @oneOf: exactly one field must be supplied, and that field's value must be non-null. This
        // mirrors graphql-java's ValuesResolverOneOfValidation, which first checks key cardinality
        // and then, separately, rejects a null value for the single supplied key. Fail fast here so
        // tenants learn of the violation when they build the input, rather than only at graphql-java
        // coercion time. graphql-java remains the execution-time backstop.
        val presentFields = graphQLInputObjectType.fields
            .map { it.name }
            .filter { inputData.containsKey(it) }
        if (presentFields.size != 1) {
            throw IllegalStateException(
                "Exactly one field must be set for @oneOf type ${graphQLInputObjectType.name}, but ${presentFields.size} were: $presentFields"
            )
        }
        val onlyField = presentFields.first()
        if (inputData[onlyField] == null) {
            throw IllegalStateException(
                "Field '$onlyField' for @oneOf type ${graphQLInputObjectType.name} must have a non-null value"
            )
        }
    }
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
