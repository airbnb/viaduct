package model

import viaduct.engine.api.CheckerResult
import viaduct.engine.api.combine

/**
 * One synchronous attempt to materialize a checked engine-result cell.
 *
 * The semantic union contains nullable [EngineResult] values and [EngineResultIsPending]. The pending
 * sentinel means that at least one required slot promise has not completed. It is not an
 * [EngineResult] and must never be published into a result cell.
 */
typealias EngineResultMaterializationAttempt = Any?

/** A checked cell cannot yet be materialized without suspension. */
data object EngineResultIsPending

/**
 * Returns the checked value currently available from this cell, or [EngineResultIsPending] when a
 * required slot is unfinished.
 *
 * The field result belongs to this cell; when its completed raw value is an object, the type result
 * belongs to that [ObjectEngineResult]. When both results
 * exist, the type result combines with the field result through the production [CheckerResult]
 * contract. A completed applicable field error can short-circuit an unfinished raw value. Access
 * failures are represented as [ErrorEngineResult] so downstream materializers handle raw and
 * access-check failures uniformly.
 */
fun EngineResultCell.materializeCheckedValue(
    isErrorForConsumer: (CheckerResult.Error) -> Boolean,
): EngineResultMaterializationAttempt {
    val fieldPromise = fieldCheckerResult
    if (!fieldPromise.isCompleted) return EngineResultIsPending

    val fieldResult = fieldPromise.get()
    val valuePromise = value
    if (!valuePromise.isCompleted) {
        val fieldError = fieldResult?.asError
        return if (fieldError != null && isErrorForConsumer(fieldError)) {
            ErrorEngineResult.of(EngineErrorData.of(fieldError.error))
        } else {
            EngineResultIsPending
        }
    }

    val value = valuePromise.get()
    val typePromise = (value as? ObjectEngineResult)?.typeCheckerResult
    if (typePromise?.isCompleted == false) return EngineResultIsPending
    val typeResult = typePromise?.get()
    val combinedResult =
        when {
            typeResult == null -> fieldResult
            fieldResult == null -> typeResult
            else -> typeResult.combine(fieldResult)
        }
    val error = combinedResult?.asError
    if (error != null && isErrorForConsumer(error)) {
        return ErrorEngineResult.of(EngineErrorData.of(error.error))
    }

    return value
}

/**
 * Awaits and returns this cell's checked value.
 *
 * The field-checker slot is awaited before the value slot so an applicable field denial can
 * return without waiting for an unfinished raw value. Once a raw object is available, its
 * type-checker promise is also awaited before the value is returned.
 */
suspend fun EngineResultCell.awaitCheckedValue(
    isErrorForConsumer: (CheckerResult.Error) -> Boolean,
): EngineResult? {
    fieldCheckerResult.awaitPreservingTerminalFailure()

    val attempt = materializeCheckedValue(isErrorForConsumer)
    if (attempt !== EngineResultIsPending) return attempt

    val value = value.awaitPreservingTerminalFailure()
    if (value is ObjectEngineResult) {
        value.typeCheckerResult.awaitPreservingTerminalFailure()
    }
    val completed = materializeCheckedValue(isErrorForConsumer)
    check(completed !== EngineResultIsPending) {
        "Completed checked-value slots produced a pending materialization attempt"
    }
    return completed
}

private suspend fun <T> Promise<T>.awaitPreservingTerminalFailure(): T =
    try {
        await()
    } catch (failure: Exception) {
        if (isCompleted) {
            try {
                get()
            } catch (terminalFailure: Exception) {
                throw terminalFailure
            }
        }
        throw failure
    }
