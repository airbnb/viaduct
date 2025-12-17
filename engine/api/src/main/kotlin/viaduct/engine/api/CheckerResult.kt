package viaduct.engine.api

import viaduct.apiannotations.TestingApi

/**
 * Represents the result of CheckerExecutor.execute, which will be stored into the checker slot
 * of the OER. This is an SPI to be implemented by implementors of Viaduct Tenant APIs.
 *
 * There are two flavors of results: successes, and errors. Successes are simple to understand, but
 * there's a subtlety to errors. In particular, we check errors in two different contexts: when
 * we're populating the output of an operation, and when we read a value from a resolver.
 * [CheckerResult.Error] results always cause an error when populating the result of an operation.
 * However, the engine allows policies that bypass checks in some circumstances
 * when reading values from a resolver.
 *
 * The Viaduct engine allows access checks to be placed on types _and_ fields.  Where checks are
 * placed on both, and they both fail, then the engine needs to understand how to combine those
 * two failures into a final [CheckerResult] for the field. The [combine] function is used for
 * that purpose.
 */
sealed interface CheckerResult {
    /**
     * Returns [this] if [this] is an error, null otherwise.
     * Convience function, e.g., `result.asError?.let { throw it.error }`.
     */
    val asError: CheckerResult.Error?

    /**
     * The interface that needs to be implemented by Viaduct API implementations.
     */
    interface Error : CheckerResult {
        /** Error associated with this result. */
        val error: Exception

        /**
         * Returns a boolean indicating whether this error should be treated as an error
         * given [CheckerResultContext].
         *
         * @param ctx Contextual information about the field this checker is applied to.
         */
        fun isErrorForResolver(ctx: CheckerResultContext): Boolean

        /**
         * If both the field and field's type results are errors, this function is called to
         * to produce a final result for the field. The result of the field's type-checker
         * will always be [this] argument of this call, while the result of the field
         * checker will always be passed as [fieldResult].
         */
        fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error

        override val asError: CheckerResult.Error get() = this
    }

    /**
     * Represents a successful check result that grants access.
     */
    @TestingApi
    object Success : CheckerResult {
        override val asError: Nothing? get() = null
    }
}

/**
 * Used inside the engine to combine results - only call [CheckerResult.Error]'s API impl
 * if needed, where the error impl is only responsible for combining errors.
 */
fun CheckerResult.combine(fieldResult: CheckerResult) =
    when {
        this is CheckerResult.Success -> fieldResult
        fieldResult is CheckerResult.Success -> this
        else -> this.asError!!.combine(fieldResult.asError!!)
    }
