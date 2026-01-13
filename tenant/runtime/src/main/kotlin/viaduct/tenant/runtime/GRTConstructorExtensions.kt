package viaduct.tenant.runtime

import graphql.schema.GraphQLInputObjectType
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.valueParameters
import viaduct.api.internal.InputTypeFactory
import viaduct.api.internal.InternalContext
import viaduct.api.types.Arguments
import viaduct.api.types.Input
import viaduct.api.types.InputLike
import viaduct.api.types.Object
import viaduct.apiannotations.TestingApi
import viaduct.engine.api.EngineObject

/**
 * Gets the primary constructor for a GRT (GraphQL Representational Type) class.
 *
 * Works for both [Object] and [InputLike] GRTs.
 * Throws an exception if there isn't a primary constructor.
 * Also, if [this] isn't @FakeGRT, throws an exception if [this] isn't one of
 * the two expected types and also ensures that the parameters of those
 * constructors are what the code generator does.
 *
 * @param strict when false skips validation (defaults to true)
 */
@TestingApi
fun <T : Any> KClass<out T>.getGRTConstructor(): KFunction<T> =
    requireNotNull(this.primaryConstructor) {
        "Primary constructor for type $this is not found."
    }.requireValidGRTConstructorFor(this)

// Private, optimized version for toXyzGRT functions where [this] is already known to be a subclass of [kind]
private fun <T : Any> KClass<out T>.getGRTConstructor(kind: KClass<*>): KFunction<T> =
    requireNotNull(this.primaryConstructor) {
        "Primary constructor for type $this is not found."
    }.requireValidGRTConstructorFor(this, kind)

/**
 * Wraps an EngineObject in an object GRT by calling the GRT's primary constructor.
 * Performs same checking as [KClass.getGRTConstructor], plus checks that [kcls]
 * is for the GraphQL type of [EngineObject].
 * For [FakeGRT]s most checks are skipped (useful for testing).
 */
fun <T : Object> EngineObject.toObjectGRT(
    internalContext: InternalContext,
    kcls: KClass<T>
): T {
    require(kcls.hasAnnotation<FakeGRT>() || kcls.simpleName == this.graphQLObjectType.name) {
        "KClass name (${kcls.simpleName}) does not match object-type name (${this.graphQLObjectType.name})."
    }
    require(kcls.isSubclassOf(Object::class)) {
        "KClass $kcls is not in { Object }."
    }
    return kcls.getGRTConstructor(Object::class).call(internalContext, this)
}

/**
 * Wraps a [Map] in a compound input GRT by calling the GRT's primary constructor.
 * Performs same checking as [KClass.getGRTConstructor], plus checks that [kcls]
 * is an actual type in the schema.
 * For [Arguments.NoArguments] just returns it.
 * For [FakeGRT]s most checks are skipped (useful for testing).
 */
fun <T : InputLike> Map<String, Any?>.toInputLikeGRT(
    internalContext: InternalContext,
    kcls: KClass<T>,
): T {
    if (kcls == Arguments.NoArguments::class) {
        @Suppress("UNCHECKED_CAST")
        return Arguments.NoArguments as T
    }

    val inputType = when {
        kcls.hasAnnotation<FakeGRT>() -> null
        kcls.isSubclassOf(Arguments::class) -> InputTypeFactory.argumentsInputType(kcls.simpleName!!, internalContext.schema)
        kcls.isSubclassOf(Input::class) -> InputTypeFactory.inputObjectInputType(kcls.simpleName!!, internalContext.schema)
        else -> throw IllegalArgumentException("KClass $kcls is not in { Arguments, Input}.")
    }
    return kcls.getGRTConstructor(InputLike::class).call(internalContext, this, inputType)
}

/**
 * Checks that [kcls] is one of [Object] or [InputLike], and checks that [this]
 * conforms to the signature required for that kind of GRT.
 * For [FakeGRT]s all checks are skipped (useful for testing).
 */
fun <T : Any> KFunction<T>.requireValidGRTConstructorFor(kcls: KClass<out T>): KFunction<T> {
    val kind = when {
        kcls.isSubclassOf(Object::class) -> Object::class
        kcls.isSubclassOf(InputLike::class) -> InputLike::class
        else -> throw IllegalArgumentException("$kcls is not in { Object, InputLike }.")
    }
    return this.requireValidGRTConstructorFor(kcls, kind)
}

// Private, optimized version for toXyzGRT functions where [kcls] is already known to be a subclass of [kind]
private fun <T : Any> KFunction<T>.requireValidGRTConstructorFor(
    kcls: KClass<out T>,
    kind: KClass<*>,
): KFunction<T> {
    if (kcls.hasAnnotation<FakeGRT>()) return this

    require(kcls == this.returnType.classifier) {
        // `requireValidGRTConstructorFor` is almost always called to check the results that
        // come back from [kcls.primaryConstructor], which means by definition the return type
        // of [this] will agree with [kcls].  However in the past there have been tests that were
        // paranoid about testing this condition, so we're including it for completeness.
        "Return type (${this.returnType.classifier}) does not match expected type ($kcls)."
    }

    val classifiers = this.valueParameters.mapNotNull { it.type.classifier as? KClass<*> }
    when {
        kind == Object::class -> {
            require(
                classifiers.size == 2 &&
                    classifiers[0].isSubclassOf(InternalContext::class) &&
                    classifiers[1].isSubclassOf(EngineObject::class)
            ) {
                "Primary constructor ($this) for object type does not conform to expected signature."
            }
        }
        kind == InputLike::class -> {
            require(
                classifiers.size == 3 &&
                    classifiers[0].isSubclassOf(InternalContext::class) &&
                    classifiers[1].isSubclassOf(Map::class) &&
                    classifiers[2].isSubclassOf(GraphQLInputObjectType::class)
            ) {
                "Primary constructor for input-like type does not conform to expected signature."
            }
        }
        else -> throw IllegalArgumentException("Unexpected $kind (private error).")
    }
    return this
}
