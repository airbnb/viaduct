package viaduct.api.internal

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import viaduct.api.reflect.Type
import viaduct.api.types.Input
import viaduct.api.types.Object
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObject
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.IR

/** wrap the provided value, which may be a string or already-wrapped enum value, in an GRT enum facade */
@Suppress("UNCHECKED_CAST")
@InternalApi
fun wrapEnum(
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

/** wrap a map of unwrapped engine data in a GRT facade with type [T] */
@InternalApi
fun <T : Input> wrapInputObject(
    ctx: InternalContext,
    type: Type<T>,
    graphQLType: GraphQLInputObjectType,
    value: Map<String, Any?>,
): T {
    val cls = type.kcls

    val ctor = cls.java.declaredConstructors.first {
        it.parameterCount == 3 &&
            it.parameterTypes[0] == InternalContext::class.java &&
            it.parameterTypes[1] == Map::class.java &&
            it.parameterTypes[2] == GraphQLInputObjectType::class.java
    }.also {
        it.isAccessible = true
    }

    @Suppress("UNCHECKED_CAST")
    return ctor.newInstance(ctx, value, graphQLType) as T
}

/** wrap the provided [value] in a GRT facade with type [T] */
@InternalApi
fun <T : Object> wrapOutputObject(
    ctx: InternalContext,
    type: Type<T>,
    value: EngineObject
): T {
    require(type.name == value.type.name) {
        "Expected value with GraphQL type ${type.name}, got ${value.type.name}"
    }

    val cls = type.kcls
    require(cls.isSubclassOf(ObjectBase::class)) {
        "Expected baseFieldTypeClass that's a subtype of ObjectBase, got $cls"
    }

    return cls.primaryConstructor!!.call(ctx, value)
}

@Suppress("UNCHECKED_CAST")
@InternalApi
val Conv<*, *>.asAnyConv: Conv<Any?, IR.Value> get() =
    this as Conv<Any?, IR.Value>
