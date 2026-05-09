package viaduct.errors

import graphql.GraphQLError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.StableApi

/**
 * Tagging interface for exceptions that have been classified as either framework or tenant errors.
 * Allows code to check `e is PassthroughException` instead of checking both
 * [FrameworkException] and [TenantException] separately.
 */
@InternalApi
interface PassthroughException

/**
 * Marker interface for exceptions that should be attributed to tenant code.
 */
@InternalApi
interface TenantException

/**
 * Used in the tenant API and dependencies to indicate that an error is due to framework code
 * and shouldn't be attributed to tenant code.
 */
@InternalApi
@OptIn(InternalApi::class)
class FrameworkException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause), PassthroughException

/**
 * Used in framework code to indicate that an error is due to invalid usage of the tenant API
 * by tenant code.
 */
@StableApi
@OptIn(InternalApi::class)
open class TenantUsageException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause), TenantException

/**
 * Used to wrap non-framework exceptions that are thrown while executing tenant resolver code.
 * This is tied to a specific tenant-written resolver.
 */
@InternalApi
@OptIn(InternalApi::class)
class TenantResolverException(
    override val cause: Throwable,
    val resolver: String,
) : Exception(cause), PassthroughException {
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
@InternalApi
@OptIn(InternalApi::class)
class ErroneousFieldException(
    val graphQLErrors: List<GraphQLError>,
) : Exception(), TenantException

/**
 * Throws [TenantUsageException] if [value] is null, otherwise returns [value].
 * Use at tenant API boundaries where a null value indicates invalid tenant usage rather
 * than a framework bug.
 */
@InternalApi
fun <T : Any> ensureNotNull(
    value: T?,
    lazyMessage: () -> Any
): T = value ?: throw TenantUsageException(lazyMessage().toString())

/**
 * Use this to wrap all entry points into the tenant API. This will catch any exception
 * and attribute it to the framework unless it's a [PassthroughException].
 */
@InternalApi
@OptIn(InternalApi::class)
fun <T> handleFrameworkErrors(
    message: String,
    block: () -> T,
): T {
    @Suppress("Detekt.TooGenericExceptionCaught")
    try {
        return block()
    } catch (e: Exception) {
        if (e is PassthroughException || e is TenantException) throw e
        throw FrameworkException("$message ($e)", e)
    }
}

/**
 * Same as [handleFrameworkErrors] but for suspend functions.
 */
@InternalApi
@OptIn(InternalApi::class)
suspend fun <T> handleFrameworkErrorsSuspend(
    message: String,
    block: suspend () -> T,
): T {
    @Suppress("Detekt.TooGenericExceptionCaught")
    try {
        return block()
    } catch (e: Exception) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        if (e is PassthroughException || e is TenantException) throw e
        throw FrameworkException("$message ($e)", e)
    }
}

/**
 * Use this to wrap calls into tenant code from the framework. Catches any exception and
 * attributes it to tenant code unless it is already a [PassthroughException].
 */
@InternalApi
@OptIn(InternalApi::class)
fun <T> handleTenantErrors(
    opName: String,
    block: () -> T,
): T {
    @Suppress("Detekt.TooGenericExceptionCaught")
    try {
        return block()
    } catch (e: Exception) {
        if (e is PassthroughException) throw e
        throw TenantResolverException(e, opName)
    }
}

/**
 * Same as [handleTenantErrors] but for suspend functions.
 */
@InternalApi
@OptIn(InternalApi::class)
suspend fun <T> handleTenantErrorsSuspend(
    opName: String,
    block: suspend () -> T,
): T {
    @Suppress("Detekt.TooGenericExceptionCaught")
    try {
        return block()
    } catch (e: Exception) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        if (e is PassthroughException) throw e
        throw TenantResolverException(e, opName)
    }
}

/**
 * Evaluates [block] and returns its value as [Result.success].
 *
 * If [block] throws an [Exception], [mapException] determines the throwable stored in
 * [Result.failure]. The default behavior preserves the original exception unchanged.
 */
@InternalApi
suspend fun <T> resultOfSuspend(
    mapException: (Exception) -> Throwable = { it },
    block: suspend () -> T,
): Result<T> {
    @Suppress("Detekt.TooGenericExceptionCaught")
    return try {
        Result.success(block())
    } catch (e: Exception) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        Result.failure(mapException(e))
    }
}

/**
 * Produces a [Result] whose failure value is already attributed for executor result paths.
 *
 * [PassthroughException] (including [FrameworkException]) and [TenantException] are returned
 * unchanged in [Result.failure].
 * Any other [Exception] is wrapped in [TenantResolverException].
 */
@InternalApi
@OptIn(InternalApi::class)
suspend fun <T> handleTenantErrorsResultSuspend(
    opName: String,
    block: suspend () -> T,
): Result<T> =
    resultOfSuspend(
        mapException = { e ->
            if (e is PassthroughException || e is TenantException) {
                e
            } else {
                TenantResolverException(e, opName)
            }
        },
        block = block,
    )
