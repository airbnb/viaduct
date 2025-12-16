package viaduct.api.internal

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import kotlin.reflect.KClass
import viaduct.api.ViaductTenantUsageException
import viaduct.api.globalid.GlobalID
import viaduct.apiannotations.InternalApi
import viaduct.graphql.schema.baseGraphqlScalarTypeMapping

private fun graphqlScalarTypeToKotlinClass(
    typeName: String,
    fieldDefinition: GraphQLFieldDefinition,
    parentType: GraphQLObjectType
): KClass<*>? =
    if (typeName == "ID") {
        if (isGlobalID(fieldDefinition, parentType)) {
            GlobalID::class
        } else {
            String::class
        }
    } else {
        baseGraphqlScalarTypeMapping[typeName]
    }

@InternalApi
@JvmInline
value class DynamicValueBuilderTypeChecker(val ctx: InternalContext) {
    data class FieldContext(
        val fieldDefinition: GraphQLFieldDefinition,
        val parentType: GraphQLObjectType
    )

    fun checkType(
        type: GraphQLType,
        value: Any?,
        context: FieldContext
    ) {
        if (value == null) {
            if (GraphQLTypeUtil.isNonNull(type)) {
                throw IllegalArgumentException(
                    "Got null builder value for non-null type ${GraphQLTypeUtil.simplePrint(type)} for field ${context.fieldDefinition.name}"
                )
            }
            return
        }

        when (val unwrappedType = GraphQLTypeUtil.unwrapNonNull(type)) {
            is GraphQLScalarType -> checkScalar(unwrappedType, value, context)
            is GraphQLEnumType -> checkEnum(unwrappedType, value, context)
            is GraphQLList -> checkList(unwrappedType, value, context)
            is GraphQLCompositeType -> checkObject(unwrappedType, value, context)
        }
    }

    private fun checkScalar(
        type: GraphQLScalarType,
        value: Any,
        context: FieldContext
    ) {
        if (type.name == "BackingData") {
            val expectedKotlinClass = getKotlinTypeForBackingData(context.fieldDefinition)
            if (!expectedKotlinClass.isInstance(value)) {
                fieldError(expectedKotlinClass, value::class, context)
            }
        } else {
            val expectedKotlinClass = graphqlScalarTypeToKotlinClass(
                typeName = type.name,
                fieldDefinition = context.fieldDefinition,
                parentType = context.parentType
            ) ?: throw IllegalArgumentException(
                "GraphQL scalar type ${type.name} mapping to Kotlin type not found for field ${context.fieldDefinition.name}"
            )
            if (!expectedKotlinClass.isInstance(value)) {
                fieldError(expectedKotlinClass, value::class, context)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkEnum(
        type: GraphQLEnumType,
        value: Any,
        context: FieldContext
    ) {
        val valueString = value.toString()

        // First check: Is the value valid in the runtime GraphQL schema?
        if (type.getValue(valueString) == null) {
            throw IllegalArgumentException(
                "Invalid enum value '$valueString' for type ${type.name} for field ${context.fieldDefinition.name}"
            )
        }

        // Second check: Can we convert to compiled Java enum? (version skew tolerance)
        // During schema version skew, the runtime schema may have values that don't exist
        // in the compiled enum class. This is acceptable - we validate against the runtime
        // schema and allow the value to pass through as a string.
        try {
            val enumClass = ctx.reflectionLoader.reflectionFor(type.name).kcls as KClass<out Enum<*>>
            java.lang.Enum.valueOf(enumClass.java, valueString)
        } catch (e: IllegalArgumentException) {
            // Value exists in runtime schema but not in compiled enum - this is OK during version skew
            // The value will be serialized as a string in FieldCompleter
            // No error thrown here
        }
    }

    private fun checkList(
        type: GraphQLList,
        value: Any,
        context: FieldContext
    ) {
        if (value !is List<*>) {
            throw IllegalArgumentException("Got non-list builder value $value for list type for field ${context.fieldDefinition.name}")
        }
        value.forEach {
            checkType(GraphQLTypeUtil.unwrapOne(type), it, context)
        }
    }

    private fun checkObject(
        type: GraphQLCompositeType,
        value: Any,
        context: FieldContext
    ) {
        if (!isViaductObjectBuilderValue(type, value) && !isObjectBase(type, value)) {
            throw IllegalArgumentException(
                "Expected ${type.name} or ValueObjectBuilder<${type.name}> for builder value for field ${context.fieldDefinition.name}, got $value"
            )
        }
    }

    private fun isViaductObjectBuilderValue(
        type: GraphQLType,
        value: Any?
    ): Boolean {
        return value is ViaductObjectBuilder<*> && isValidObjectType(type, value.graphqlType)
    }

    private fun isObjectBase(
        type: GraphQLType,
        value: Any?
    ): Boolean {
        return value is ObjectBase && isValidObjectType(type, value.engineObject.graphQLObjectType)
    }

    fun isValidObjectType(
        type: GraphQLType,
        targetType: GraphQLObjectType,
    ): Boolean {
        return when (type) {
            is GraphQLObjectType -> targetType.name == type.name
            // in case it is an interface, check if the value is an instance of the interface
            is GraphQLInterfaceType -> targetType.interfaces.contains(type)
            // in case it is a union, check if the value is a possible type
            is GraphQLUnionType -> type.isPossibleType(targetType)
            else -> false
        }
    }

    private fun getKotlinTypeForBackingData(fieldDefinition: GraphQLFieldDefinition): KClass<*> {
        val directive = fieldDefinition.appliedDirectives.find { it.name == "backingData" }
        if (directive == null) {
            throw ViaductTenantUsageException(
                "Backing data field ${fieldDefinition.name} must have @backingData directive defined in schema. None found."
            )
        }
        val classPath = (directive.arguments.find { it.name == "class" })?.getValue<String>()
        if (classPath == null) {
            throw ViaductTenantUsageException(
                "Backing data field ${fieldDefinition.name}'s @backingData directive must define a `class` argument of string type."
            )
        }
        return Class.forName(classPath).kotlin
    }

    private fun fieldError(
        expected: KClass<*>,
        actual: KClass<*>,
        context: FieldContext,
    ) {
        throw IllegalArgumentException(
            "Expected value of type ${expected.simpleName} for field ${context.fieldDefinition.name}, got ${actual.simpleName}"
        )
    }
}
