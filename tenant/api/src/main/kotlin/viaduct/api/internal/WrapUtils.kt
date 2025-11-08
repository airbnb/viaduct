package viaduct.api.internal

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import kotlin.reflect.KClass
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.IR

@Suppress("UNCHECKED_CAST")
internal fun wrapEnum(
    ctx: InternalContext,
    type: GraphQLEnumType,
    value: Any
): Enum<*>? {
    // If value is already an instance of the enum type's GRT, return it without conversion
    val enumClass = ctx.reflectionLoader.reflectionFor(type.name).kcls as KClass<out Enum<*>>
    if (enumClass.isInstance(value)) return value as Enum<*>

    val valueString = value.toString()
    return try {
        java.lang.Enum.valueOf(enumClass.java, valueString)
    } catch (e: IllegalArgumentException) {
        if (valueString == "UNDEFINED") {
            return null
        } else {
            throw e
        }
    }
}

internal fun isGlobalID(
    field: GraphQLFieldDefinition,
    parentType: GraphQLObjectType,
): Boolean {
    return field.name == "id" && parentType.interfaces.any { it.name == "Node" } || field.appliedDirectives.any { it.name == "idOf" }
}

@Suppress("UNCHECKED_CAST")
val Conv<*, *>.asAnyConv: Conv<Any?, IR.Value> get() =
    this as Conv<Any?, IR.Value>
