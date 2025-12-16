package viaduct.api

import viaduct.api.context.VariablesProviderContext
import viaduct.api.types.Arguments
import viaduct.apiannotations.StableApi

/**
 * A base interface for dynamic provisioning of variable values.
 * All implementations of VariablesProvider must be annotated with [Variables].
 *
 * If a viaduct resolver declares a VariablesProvider, it will be invoked at request time to
 * provide additional variable values that may be used in an [Resolver.objectValueFragment].
 *
 * Example:
 * ```kotlin
 * @Resolver("baz(${'$'}x)")
 * class FooBarResolver : FooResolvers.Bar() {
 *
 *   @Variables("x: Int")
 *   class Vars : VariablesProvider<Arguments.NoArguments> {
 *     override suspend fun provide(context: VariablesProviderContext<Arguments.NoArguments>): Map<String, Any?> =
 *       mapOf("x" to 42)
 *   }
 * }
 * ```
 */
@StableApi
fun interface VariablesProvider<T : Arguments> {
    /**
     * Return a Map with provided variable values.
     * The map must contain exactly the keys in the [Variables] annotation on the containing
     * VariablesProvider class.
     */
    suspend fun provide(context: VariablesProviderContext<T>): Map<String, Any?>
}
