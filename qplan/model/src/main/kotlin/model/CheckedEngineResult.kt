package model

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * Unclaimed checker slots default open. When both claimed checker results exist, the type result
 * combines with the field result through the production [CheckerResult] contract. An applicable
 * checker error short-circuits without consulting the value slot and is represented as an
 * [ErrorEngineResult] so downstream materializers handle raw and access-check failures uniformly.
 * The value slot is inspected only after the checker results allow this consumer.
 */
fun EngineResultCell.materializeCheckedValue(
    isErrorForConsumer: (CheckerResult.Error) -> Boolean,
): EngineResultMaterializationAttempt {
    val fieldPromise =
        if (isFieldCheckerResultSet()) {
            getFieldCheckerResult()
        } else {
            null
        }
    val typePromise =
        if (isTypeCheckerResultSet()) {
            getTypeCheckerResult()
        } else {
            null
        }
    if (fieldPromise?.isCompleted == false || typePromise?.isCompleted == false) {
        return EngineResultIsPending
    }

    val fieldResult = fieldPromise?.get()
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

    val valuePromise = getValue()
    if (!valuePromise.isCompleted) return EngineResultIsPending
    return valuePromise.get()
}

/**
 * Awaits and returns this cell's checked value.
 *
 * Claimed checker slots are awaited before the value slot so an applicable denial can return
 * without waiting for or consulting the raw value. Unclaimed checker slots default open.
 */
suspend fun EngineResultCell.awaitCheckedValue(
    isErrorForConsumer: (CheckerResult.Error) -> Boolean,
): EngineResult? {
    val checkerPromises =
        buildList {
            if (isFieldCheckerResultSet()) add(getFieldCheckerResult())
            if (isTypeCheckerResultSet()) add(getTypeCheckerResult())
        }
    coroutineScope {
        checkerPromises
            .filterNot(Promise<*>::isCompleted)
            .map { promise ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    promise.awaitPreservingTerminalFailure()
                }
            }.awaitAll()
    }

    val attempt = materializeCheckedValue(isErrorForConsumer)
    if (attempt !== EngineResultIsPending) return attempt

    getValue().awaitPreservingTerminalFailure()
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
