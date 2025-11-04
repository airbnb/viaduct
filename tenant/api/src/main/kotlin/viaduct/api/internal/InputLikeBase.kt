package viaduct.api.internal

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import java.time.OffsetDateTime
import kotlin.reflect.KClass
import viaduct.api.ViaductFrameworkException
import viaduct.api.ViaductTenantUsageException
import viaduct.api.globalid.GlobalID
import viaduct.api.handleTenantAPIErrors
import viaduct.api.types.InputLike
import viaduct.api.types.NodeObject

/**
 * Base class for input & field argument GRTs
 */
@Suppress("UNCHECKED_CAST")
abstract class InputLikeBase : InputLike {
    protected abstract val context: InternalContext
    abstract val inputData: Map<String, Any?>
    protected abstract val graphQLInputObjectType: GraphQLInputObjectType

    @Suppress("unused")
    protected fun validateInputDataAndThrowAsFrameworkError() {
        try {
            validateInputData(graphQLInputObjectType, inputData)
        } catch (e: IllegalStateException) {
            throw ViaductFrameworkException("Failed to init ${graphQLInputObjectType.name} ($e)", e)
        }
    }

    fun isPresent(fieldName: String): Boolean {
        return inputData.containsKey(fieldName)
    }

    protected fun <T> get(
        fieldName: String,
        baseFieldTypeClass: KClass<*>
    ): T =
        handleTenantAPIErrors("InputLikeBase.get failed for ${graphQLInputObjectType.name}.$fieldName") {
            val fieldDefinition = graphQLInputObjectType.getField(fieldName) ?: throw IllegalArgumentException(
                "Field $fieldName not found on type ${graphQLInputObjectType.name}"
            )
            wrap(fieldDefinition.type, inputData[fieldName], baseFieldTypeClass) as T
        }

    private fun wrap(
        type: GraphQLType,
        value: Any?,
        baseFieldTypeClass: KClass<*>
    ): Any? {
        if (value == null) {
            if (GraphQLTypeUtil.isNonNull(type)) {
                throw IllegalArgumentException("Got null value for non-null type ${GraphQLTypeUtil.simplePrint(type)}")
            }
            return null
        }

        return when (val unwrappedType = GraphQLTypeUtil.unwrapNonNull(type)) {
            is GraphQLScalarType -> wrapScalar(unwrappedType, value, baseFieldTypeClass)
            is GraphQLEnumType -> wrapEnum(context, unwrappedType, value)
            is GraphQLList -> wrapList(unwrappedType, value, baseFieldTypeClass)
            is GraphQLInputObjectType -> wrapInput(unwrappedType, value)
            else -> throw RuntimeException("Unexpected type ${GraphQLTypeUtil.simplePrint(unwrappedType)}")
        }
    }

    private fun wrapScalar(
        type: GraphQLScalarType,
        value: Any,
        baseFieldTypeClass: KClass<*>
    ): Any {
        // The DateTime scalar type coerces to OffsetDateTime, but we use Instant for GRTs
        if (type.name == "DateTime") {
            if (value is OffsetDateTime) {
                return value.toInstant()
            } else {
                throw RuntimeException("Expecting OffsetDateTime for DateTime scalar, got $value")
            }
        } else if (baseFieldTypeClass == GlobalID::class) {
            return context.globalIDCodec.deserialize<NodeObject>(value as String)
        }
        // For all other types, graphql-java and the engine should already have coerced the value
        return value
    }

    private fun wrapList(
        type: GraphQLList,
        value: Any,
        baseFieldTypeClass: KClass<*>
    ): List<*> {
        if (value !is List<*>) {
            throw IllegalArgumentException("Got non-list value $value for list type")
        }
        return value.map {
            wrap(GraphQLTypeUtil.unwrapOne(type), it, baseFieldTypeClass)
        }
    }

    private fun wrapInput(
        type: GraphQLInputObjectType,
        value: Any,
    ): Any {
        when (value) {
            is InputLikeBase -> {
                return value
            }

            is Map<*, *> -> {
                val klazz = context.reflectionLoader.reflectionFor(type.name).kcls
                val inputConstructor = klazz.java.declaredConstructors.first {
                    it.parameterCount == 3 &&
                        it.parameterTypes[0] == InternalContext::class.java &&
                        it.parameterTypes[1] == Map::class.java &&
                        it.parameterTypes[2] == GraphQLInputObjectType::class.java
                }
                inputConstructor.isAccessible = true
                return inputConstructor.newInstance(context, value, type)
            }

            else -> {
                throw IllegalArgumentException("Expected InputLikeBase or Map for value, got $value")
            }
        }
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
            val fieldType = graphQLInputObjectType.getField(fieldName)?.type ?: throw IllegalArgumentException(
                "Field $fieldName not found on type ${graphQLInputObjectType.name}"
            )
            inputData.put(fieldName, unwrap(fieldType, value))
        }

        private fun unwrap(
            type: GraphQLType,
            value: Any?
        ): Any? {
            if (value == null) {
                if (GraphQLTypeUtil.isNonNull(type)) {
                    throw IllegalArgumentException(
                        "Got null builder value for non-null type ${GraphQLTypeUtil.simplePrint(type)}"
                    )
                }
                return null
            }

            return when (val unwrappedType = GraphQLTypeUtil.unwrapNonNull(type)) {
                is GraphQLScalarType, is GraphQLEnumType -> unwrapScalar(value)
                is GraphQLList -> unwrapList(unwrappedType, value)
                is GraphQLInputObjectType -> unwrapInput(value)
                else -> throw ViaductFrameworkException("Unexpected schema type ${GraphQLTypeUtil.simplePrint(unwrappedType)}")
            }
        }

        private fun unwrapScalar(value: Any): Any {
            if (value is GlobalID<*>) {
                return context.globalIDCodec.serialize(value)
            }
            return value
        }

        private fun unwrapList(
            type: GraphQLList,
            value: Any
        ): List<*> {
            if (value !is List<*>) {
                throw IllegalArgumentException("Got non-list builder value $value for list type")
            }
            return value.map {
                unwrap(GraphQLTypeUtil.unwrapOne(type), it)
            }
        }

        private fun unwrapInput(value: Any): Map<*, *> {
            if (value is InputLikeBase) {
                return value.inputData
            } else {
                throw IllegalArgumentException("Expected InputLikeBase for builder value, got $value")
            }
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
    getRequiredFieldNamesFromInputObjectType(graphQLInputObjectType).forEach {
        if (inputData[it] == null) {
            throw IllegalStateException("Field ${graphQLInputObjectType.name}.$it is required")
        }
    }
    getDefaultFieldNamesFromInputObjectType(graphQLInputObjectType).forEach {
        if (!inputData.containsKey(it)) {
            throw IllegalStateException("Field ${graphQLInputObjectType.name}.$it should have default value")
        }
    }
}

private fun getRequiredFieldNamesFromInputObjectType(graphQLInputObjectType: GraphQLInputObjectType): List<String> {
    return graphQLInputObjectType.fieldDefinitions.filter { GraphQLTypeUtil.isNonNull(it.type) }.map { it.name }
}

private fun getDefaultFieldNamesFromInputObjectType(graphQLInputObjectType: GraphQLInputObjectType): List<String> {
    return graphQLInputObjectType.fieldDefinitions.filter { it.hasSetDefaultValue() }.map { it.name }
}
