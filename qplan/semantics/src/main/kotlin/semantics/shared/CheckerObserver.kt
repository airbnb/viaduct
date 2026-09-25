package semantics.shared

import model.Arguments
import model.ObjectEngineResult
import model.PathComponent
import viaduct.graphql.schema.ViaductSchema

/** The access-check result slot produced by one checker invocation. */
enum class CheckerKind {
    FIELD,
    TYPE,
}

/** Exact occurrence evidence captured immediately before a checker function is entered. */
data class CheckerInvocationObservation(
    val checkerKind: CheckerKind,
    val logicalQueryRoot: ObjectEngineResult,
    val occurrencePath: List<PathComponent>,
    val arguments: Arguments.Resolved,
    val checkedCoordinate: ViaductSchema.ObjectField,
)

/**
 * Receives semantically passive checker observations from one operation.
 *
 * Replacing a normally returning, non-mutating observer with [NOP] preserves semantic resolution
 * results. Callbacks are synchronous and may run concurrently.
 */
fun interface CheckerObserver {
    /**
     * Records one attempted checker call after input preparation and immediately before invocation,
     * with no suspension or dispatch boundary between this event and the checker function.
     */
    fun onCheckerInvocation(observation: CheckerInvocationObservation)

    /** Observer that discards every event. */
    object NOP : CheckerObserver {
        override fun onCheckerInvocation(observation: CheckerInvocationObservation) = Unit
    }
}
