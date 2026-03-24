package viaduct.api

import kotlinx.coroutines.CancellationException
import viaduct.apiannotations.StableApi

/**
 * Executes the given [block] and returns its result, or `null` if an exception is thrown.
 *
 * Use this for graceful degradation when fetching supplementary data that can safely be omitted
 * on failure. For example, when accessing GRT fields that may throw due to policy check errors,
 * this allows returning `null` instead of propagating the exception.
 *
 * [CancellationException] is always rethrown to preserve structured concurrency.
 *
 * Example:
 * ```kotlin
 * val countryOfResidence: String? = fetchOrNull {
 *     viewer?.getUser()?.getOwner()?.getCountryForTax()?.getCountryOfResidence()
 * }
 * ```
 */
@StableApi
suspend inline fun <T> fetchOrNull(block: suspend () -> T): T? = fetchOrDefault<T?>(null) { block() }

/**
 * Executes the given [block] and returns its result, or [default] if an exception is thrown.
 *
 * Unlike `fetchOrNull { ... } ?: default`, this preserves the distinction between a block that
 * returns `null` vs a block that throws - only exceptions trigger the default.
 *
 * [CancellationException] is always rethrown to preserve structured concurrency.
 *
 * Example:
 * ```kotlin
 * val hasProfilePicture: Boolean = fetchOrDefault(false) {
 *     (user.getCanViewProfilePicture() ?: false) &&
 *         (user.getUserRepresentationUrl()?.getHasProfilePicture() ?: false)
 * }
 * ```
 */
@StableApi
suspend inline fun <T> fetchOrDefault(
    default: T,
    block: suspend () -> T
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("Detekt.TooGenericExceptionCaught") e: Exception
    ) {
        default
    }
