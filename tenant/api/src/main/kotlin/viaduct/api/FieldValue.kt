package viaduct.api

import viaduct.api.exception.FieldError
import viaduct.apiannotations.StableApi

/**
 * Represents the value of a resolved GraphQL object field
 */
@StableApi
sealed interface FieldValue<out T> {
    /**
     * Returns the value on success, or throws an exception for an error value
     */
    fun get(): T

    /**
     * Whether this is an error value or not
     */
    val isError: Boolean

    companion object {
        /**
         * Constructs a FieldValue that resolved without an error
         */
        fun <T> ofValue(value: T): FieldValue<T> {
            return FieldValueImpl(value)
        }

        /**
         * Constructs a FieldValue that resolved with the [error]. Use [FieldError]
         * to customize the GraphQL error response.
         */
        fun ofError(error: Exception): FieldValue<Nothing> {
            return FieldErrorValueImpl(error)
        }
    }
}

private class FieldValueImpl<T>(
    private val value: T
) : FieldValue<T> {
    override fun get() = value

    override val isError = false
}

private class FieldErrorValueImpl<T>(
    private val error: Exception
) : FieldValue<T> {
    override fun get(): T {
        // TODO (https://app.asana.com/1/150975571430/task/1210815621831967?focus=true): Think through the following:
        // 1. Does this do what we want in terms of having the correct stack trace?
        // 2. Should this always wrap exceptions in FieldError?
        // 3. How does this interact with ViaductTenantResolverException and ViaductFrameworkException?
        throw error
    }

    override val isError = true
}
