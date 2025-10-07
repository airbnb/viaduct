package viaduct.tenant.runtime

import graphql.schema.GraphQLInputObjectType
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.valueParameters
import viaduct.api.internal.InternalContext
import viaduct.api.reflect.Type
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.EngineObject

/**
 * Gets the required constructor for a GRT (GraphQL Representational Type) class.
 * The constructor must take exactly two parameters: InternalContext and EngineObject.
 */
fun <T : CompositeOutput> KClass<out T>.getGRTConstructor(): KFunction<T> {
    val constructor = primaryConstructor
    if (constructor == null) {
        throw IllegalArgumentException("Primary constructor for type ${this.simpleName} is not found.")
    }

    val params = constructor.valueParameters
    val classifiers = params.mapNotNull { it.type.classifier as? KClass<*> }
    if (classifiers.size != 2 ||
        !classifiers[0].isSubclassOf(InternalContext::class) ||
        !classifiers[1].isSubclassOf(EngineObject::class)
    ) {
        throw IllegalArgumentException("Primary constructor for type ${this.simpleName} is not found.")
    }

    return constructor
}

/**
 * Wraps an EngineObject into a tenant GRT object by calling the object's primary constructor
 * with the provided InternalContext and this EngineObject.
 */
fun <T : CompositeOutput> EngineObject.toGRT(
    internalContext: InternalContext,
    type: Type<T>
): T = type.kcls.getGRTConstructor().call(internalContext, this)

/**
 * Gets the required constructor for an Arguments GRT (Generated Runtime Type) class.
 * The constructor must take exactly three parameters: InternalContext, Map, and GraphQLInputObjectType.
 * Special case: Arguments.NoArguments is handled separately and throws an exception.
 */
fun <T : Arguments> KClass<out T>.getArgumentsGRTConstructor(): KFunction<T> {
    // Special case: NoArguments is an object and doesn't have a GRT constructor
    if (this == Arguments.NoArguments::class) {
        throw IllegalArgumentException("Arguments.NoArguments is a singleton object and does not have a GRT constructor.")
    }

    val constructor = primaryConstructor
    if (constructor == null) {
        throw IllegalArgumentException("Primary constructor for type ${this.simpleName} is not found.")
    }

    val params = constructor.valueParameters
    val classifiers = params.mapNotNull { it.type.classifier as? KClass<*> }
    if (classifiers.size != 3 ||
        !classifiers[0].isSubclassOf(InternalContext::class) ||
        !classifiers[1].isSubclassOf(Map::class) ||
        !classifiers[2].isSubclassOf(GraphQLInputObjectType::class)
    ) {
        throw IllegalArgumentException("Primary constructor for type ${this.simpleName} is not found.")
    }

    return constructor
}

/**
 * Returns the extension function on Map<String, Any?>.(internalContext: InternalContext): A
 * that turns raw arguments (the reciever object) plus a context into a GRT for the
 * indicated graphql arguments.
 *
 * For testing purposes this will handle the NoArguments case correctly, but in production
 * code you should really optimize for that.
 */
fun <A : Arguments> KClass<A>.makeArgumentsGRTFactory(): Map<String, Any?>.(internalContext: InternalContext) -> A {
    if (this == Arguments.NoArguments::class) {
        @Suppress("UNCHECKED_CAST")
        return { _: InternalContext -> Arguments.NoArguments as A }
    }

    val constructor = this.getArgumentsGRTConstructor()

    return { internalContext: InternalContext ->
        val graphqlInputObjectType = Arguments.inputType(
            this@makeArgumentsGRTFactory.simpleName!!,
            internalContext.schema
        )
        constructor.call(internalContext, this, graphqlInputObjectType)
    }
}
