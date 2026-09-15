package viaduct.errors

import java.util.concurrent.Callable
import viaduct.apiannotations.InternalApi

/**
 * Java-friendly entry points to [handleFrameworkErrors] and [handleTenantErrors].
 *
 * The Kotlin top-level helpers take a `() -> T` lambda, which Java callers cannot pass.
 * These @JvmStatic overloads accept a [Callable] instead.
 *
 * [Callable] is used (rather than [java.util.function.Supplier]) because it declares
 * `throws Exception`, matching the "catch anything, attribute it" semantics of both
 * [handleFrameworkErrors] and [handleTenantErrors].
 */
@InternalApi
object HandleErrors {
    /**
     * Wraps Java-side framework code that may call into, or return data to, tenant code.
     * Delegates to the Kotlin top-level [handleFrameworkErrors]: [PassthroughException] and
     * [TenantException] pass through unchanged; anything else becomes a [FrameworkException].
     */
    @JvmStatic
    fun <T> framework(
        message: String,
        block: Callable<T>
    ): T = handleFrameworkErrors(message) { block.call() }

    /** Framework attribution for generated accessors, where cancellation must remain cancellation. */
    @JvmStatic
    fun <T> accessor(
        message: String,
        block: Callable<T>
    ): T = handleAccessorErrors(message) { block.call() }

    /**
     * Wraps Java-side calls into tenant-written code. Delegates to the Kotlin top-level
     * [handleTenantErrors]: [PassthroughException] passes through unchanged; anything else
     * becomes a [TenantResolverException] attributed to [opName].
     */
    @JvmStatic
    fun <T> tenant(
        opName: String,
        block: Callable<T>
    ): T = handleTenantErrors(opName) { block.call() }

    /**
     * Java-friendly entry point to [nullOnDataFailure]: data-side failures become `null`, while
     * tenant bugs, framework bugs, and cancellation propagate. Named differently from the Kotlin
     * top-level function so that calls to the latter from within this object stay unambiguous.
     */
    @JvmStatic
    fun <T> dataFailureToNull(block: Callable<T>): T? = nullOnDataFailure { block.call() }
}
