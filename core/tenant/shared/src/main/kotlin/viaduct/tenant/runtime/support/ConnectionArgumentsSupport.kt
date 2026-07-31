package viaduct.tenant.runtime.support

import viaduct.apiannotations.InternalApi

/**
 * The offset/limit math for connection pagination arguments, shared by the Kotlin and Java Tenant
 * APIs so the two implementations agree and are tested once.
 *
 * The public `ForwardConnectionArguments` / `BackwardConnectionArguments` /
 * `MultidirectionalConnectionArguments` interfaces in each language delegate here, passing their
 * nullable `first`/`after`/`last`/`before` values. This object owns validation and the
 * offset/limit derivation; it has no dependency on either language's GRT types.
 *
 * A returned [OffsetBounds] with a negative [OffsetBounds.offset] is the "resolve from the tail"
 * signal consumed by the connection builder's `fromList` (backward pagination without a `before`
 * cursor and without a known total count).
 */
@InternalApi
object ConnectionArgumentsSupport {
    /** Offset/limit pair produced by the pagination math. */
    data class OffsetBounds(val offset: Int, val limit: Int)

    // ---- forward ----------------------------------------------------------------------------

    fun validateForward(
        first: Int?,
        after: String?
    ) {
        first?.let { require(it > 0) { "first must be positive, got: $it" } }
        after?.let {
            require(OffsetCursorCodec.isValid(it)) { "Invalid after cursor: $it" }
            require(OffsetCursorCodec.decode(it) < Int.MAX_VALUE) {
                "after cursor cannot advance beyond Int.MAX_VALUE: $it"
            }
        }
    }

    fun forwardOffsetLimit(
        first: Int?,
        after: String?,
        defaultPageSize: Int
    ): OffsetBounds {
        validateForward(first, after)
        val afterOffset = after?.let { OffsetCursorCodec.decode(it) + 1 } ?: 0
        val pageSize = first ?: defaultPageSize
        return OffsetBounds(afterOffset, pageSize)
    }

    // ---- backward ---------------------------------------------------------------------------

    fun validateBackward(
        last: Int?,
        before: String?
    ) {
        last?.let { require(it > 0) { "last must be positive, got: $it" } }
        before?.let { require(OffsetCursorCodec.isValid(it)) { "Invalid before cursor: $it" } }
    }

    /** True iff backward pagination needs the total count (no `before` cursor to anchor on). */
    fun backwardRequiresTotalCount(before: String?): Boolean = before == null

    fun backwardOffsetLimit(
        last: Int?,
        before: String?,
        defaultPageSize: Int
    ): OffsetBounds {
        validateBackward(last, before)
        val pageSize = last ?: defaultPageSize
        val beforeOffset = before?.let { OffsetCursorCodec.decode(it) }
            ?: return OffsetBounds(offset = -pageSize, limit = pageSize)
        val calculatedOffset = maxOf(0, beforeOffset - pageSize)
        val adjustedLimit = minOf(pageSize, beforeOffset)
        return OffsetBounds(calculatedOffset, adjustedLimit)
    }

    fun backwardOffsetLimit(
        last: Int?,
        before: String?,
        totalCount: Int,
        defaultPageSize: Int,
    ): OffsetBounds {
        validateBackward(last, before)
        require(totalCount >= 0) { "totalCount must be non-negative, got: $totalCount" }
        val beforeOffset = before?.let { OffsetCursorCodec.decode(it) }
        if (beforeOffset != null) return backwardOffsetLimit(last, before, defaultPageSize)
        val pageSize = last ?: defaultPageSize
        val calculatedOffset = maxOf(0, totalCount - pageSize)
        val adjustedLimit = minOf(pageSize, totalCount)
        return OffsetBounds(calculatedOffset, adjustedLimit)
    }

    // ---- multidirectional -------------------------------------------------------------------

    private fun isForward(
        first: Int?,
        after: String?
    ) = first != null || after != null

    private fun isBackward(
        last: Int?,
        before: String?
    ) = last != null || before != null

    fun validateMultidirectional(
        first: Int?,
        after: String?,
        last: Int?,
        before: String?
    ) {
        validateForward(first, after)
        validateBackward(last, before)
        if (isForward(first, after) && isBackward(last, before)) {
            throw IllegalArgumentException(
                "Cannot mix forward (first/after) and backward (last/before) pagination"
            )
        }
    }

    fun multidirectionalRequiresTotalCount(
        first: Int?,
        after: String?,
        last: Int?,
        before: String?
    ): Boolean =
        when {
            isForward(first, after) -> false
            isBackward(last, before) -> backwardRequiresTotalCount(before)
            else -> false
        }

    /**
     * Multidirectional offset/limit: forward args take precedence, else backward, else the default
     * first page. Validates once (Kotlin's inheritance-based version validated twice).
     */
    fun multidirectionalOffsetLimit(
        first: Int?,
        after: String?,
        last: Int?,
        before: String?,
        defaultPageSize: Int,
    ): OffsetBounds {
        validateMultidirectional(first, after, last, before)
        return when {
            isForward(first, after) -> forwardOffsetLimitNoValidate(first, after, defaultPageSize)
            isBackward(last, before) -> backwardOffsetLimitNoValidate(last, before, defaultPageSize)
            else -> OffsetBounds(offset = 0, limit = defaultPageSize)
        }
    }

    fun multidirectionalOffsetLimit(
        first: Int?,
        after: String?,
        last: Int?,
        before: String?,
        totalCount: Int,
        defaultPageSize: Int,
    ): OffsetBounds {
        validateMultidirectional(first, after, last, before)
        require(totalCount >= 0) { "totalCount must be non-negative, got: $totalCount" }
        return when {
            isForward(first, after) -> forwardOffsetLimitNoValidate(first, after, defaultPageSize)
            isBackward(last, before) -> backwardOffsetLimitNoValidate(last, before, totalCount, defaultPageSize)
            else -> OffsetBounds(offset = 0, limit = defaultPageSize)
        }
    }

    // Internal no-validate variants so multidirectional validates exactly once.
    private fun forwardOffsetLimitNoValidate(
        first: Int?,
        after: String?,
        defaultPageSize: Int
    ): OffsetBounds {
        val afterOffset = after?.let { OffsetCursorCodec.decode(it) + 1 } ?: 0
        return OffsetBounds(afterOffset, first ?: defaultPageSize)
    }

    private fun backwardOffsetLimitNoValidate(
        last: Int?,
        before: String?,
        defaultPageSize: Int
    ): OffsetBounds {
        val pageSize = last ?: defaultPageSize
        val beforeOffset = before?.let { OffsetCursorCodec.decode(it) }
            ?: return OffsetBounds(offset = -pageSize, limit = pageSize)
        return OffsetBounds(maxOf(0, beforeOffset - pageSize), minOf(pageSize, beforeOffset))
    }

    private fun backwardOffsetLimitNoValidate(
        last: Int?,
        before: String?,
        totalCount: Int,
        defaultPageSize: Int
    ): OffsetBounds {
        val beforeOffset = before?.let { OffsetCursorCodec.decode(it) }
        if (beforeOffset != null) return backwardOffsetLimitNoValidate(last, before, defaultPageSize)
        val pageSize = last ?: defaultPageSize
        return OffsetBounds(maxOf(0, totalCount - pageSize), minOf(pageSize, totalCount))
    }
}
