package viaduct.errors

import graphql.GraphQLError
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import viaduct.apiannotations.StableApi

/**
 * Tagging interface for exceptions that have been classified as either framework or tenant errors.
 * Allows code to check `e is WrappedException` instead of checking both
 * [FrameworkException] and [TenantException] separately.
 */
@StableApi
interface WrappedException

/**
 * Marker interface for exceptions that should be attributed to tenant code.
 */
@StableApi
interface TenantException : WrappedException

/**
 * Used in the tenant API and dependencies to indicate that an error is due to framework code
 * and shouldn't be attributed to tenant code.
 */
@StableApi
class FrameworkException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause), WrappedException

/**
 * Used in framework code to indicate that an error is due to invalid usage of the tenant API
 * by tenant code.
 */
@StableApi
class TenantUsageException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause), TenantException

/**
 * Used to wrap non-framework exceptions that are thrown while executing tenant resolver code.
 * This is tied to a specific tenant-written resolver.
 */
@StableApi
class TenantResolverException constructor(
    override val cause: Throwable,
    val resolver: String,
) : Exception(cause), TenantException {
    // The call chain of resolvers, e.g. "User.fullName > User.firstName" means
    // User.fullName's resolver called User.firstName's resolver which threw an exception
    val resolversCallChain: String by lazy {
        generateSequence(this) { it.cause as? TenantResolverException }
            .map { it.resolver }
            .joinToString(" > ")
    }
}

/**
 * Thrown when a tenant resolver reads a field that was set to an error state — either because
 * the producing resolver threw an exception, returned an error FieldValue, or failed to set a
 * required field. The [graphQLErrors] list is assembled from the FieldErrorExceptions (or
 * equivalent error FieldValues) produced upstream and must not be dropped or re-wrapped in any
 * exception that discards it.
 */
@StableApi
class ErroneousFieldException(
    val graphQLErrors: List<GraphQLError>,
) : Exception(), TenantException

/**
 * Use this to wrap all entry points into the tenant API. This will catch any exception
 * and attribute it to the framework unless it's a [TenantException].
 */
fun <T> handleTenantAPIErrors(
    message: String,
    block: () -> T,
): T {
    @Suppress("Detekt.TooGenericExceptionCaught")
    try {
        return block()
    } catch (e: Throwable) {
        if (e is TenantException) throw e
        throw FrameworkException("$message ($e)", e)
    }
}

/**
 * Same as [handleTenantAPIErrors] but for suspend functions.
 */
suspend fun <T> handleTenantAPIErrorsSuspend(
    message: String,
    block: suspend () -> T,
): T {
    @Suppress("Detekt.TooGenericExceptionCaught")
    try {
        return block()
    } catch (e: Throwable) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        if (e is TenantException) throw e
        throw FrameworkException("$message ($e)", e)
    }
}

/**
 * Catches any exception thrown by [resolveFn] (which must be called via reflection) and wraps it
 * in [TenantResolverException] unless it's a [FrameworkException].
 */
@StableApi
suspend fun wrapResolveException(
    resolverId: String,
    resolveFn: suspend () -> Any?,
): Any? {
    return try {
        resolveFn()
    } catch (e: Exception) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        // Since the resolver function is called via reflection, exceptions thrown from inside
        // the resolver may be wrapped in an InvocationTargetException.
        val resolverException = if (e is InvocationTargetException) {
            e.targetException
        } else {
            e
        }
        if (resolverException is FrameworkException) throw resolverException
        throw TenantResolverException(resolverException, resolverId)
    }
}
