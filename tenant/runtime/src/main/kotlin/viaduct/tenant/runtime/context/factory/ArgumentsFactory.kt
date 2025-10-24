package viaduct.tenant.runtime.context.factory

import kotlin.reflect.KClass
import viaduct.api.internal.InternalContext
import viaduct.api.types.Arguments
import viaduct.tenant.runtime.getGRTConstructor
import viaduct.tenant.runtime.toInputLikeGRT

class ArgumentsArgs(
    /** A service-scoped [InternalContext] */
    val internalContext: InternalContext,
    /** A request-scoped map of untyped arguments provided to a resolver */
    val arguments: Map<String, Any?>,
)

object ArgumentsFactory {
    /** a Factory that always returns [Arguments.NoArguments] */
    val NoArguments: Factory<Any, Arguments.NoArguments> =
        Factory.const(Arguments.NoArguments)

    /**
     * If the provided [argumentsCls] has the expected constructor, return a [Factory]
     * that marshals argument values into the provided class.
     * Otherwise, returns null
     */
    fun ifClass(argumentsCls: KClass<out Arguments>): Factory<ArgumentsArgs, Arguments>? =
        try {
            argumentsCls.getGRTConstructor() // Validate constructor exists
            forClass(argumentsCls)
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Create a Factory that returns instances of [Arguments] generated from the
     * provided [argumentsCls].
     */
    fun forClass(argumentsCls: KClass<out Arguments>): Factory<ArgumentsArgs, Arguments> {
        if (argumentsCls != Arguments.NoArguments::class) {
            argumentsCls.getGRTConstructor() // Validate constructor exists
        }
        return Factory { args ->
            args.arguments.toInputLikeGRT(args.internalContext, argumentsCls)
        }
    }
}
