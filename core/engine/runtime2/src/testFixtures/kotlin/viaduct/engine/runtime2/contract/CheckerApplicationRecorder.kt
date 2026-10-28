package viaduct.engine.runtime2.contract

import java.util.concurrent.ConcurrentLinkedQueue
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerObserver

/** Thread-safe exact checker-application evidence, deliberately separate from value correctness. */
class CheckerApplicationRecorder : CheckerObserver {
    private val observations = ConcurrentLinkedQueue<CheckerInvocationObservation>()

    override fun onCheckerInvocation(observation: CheckerInvocationObservation) {
        observations += observation
    }

    fun checkerApplications(): List<CheckerInvocationObservation> = observations.toList()

    /** Compares multisets so both missing and duplicate applications fail the judgment. */
    fun hasExactlyCheckerApplications(expected: Collection<CheckerInvocationObservation>): Boolean =
        observations.groupingBy { it }.eachCount() ==
            expected.groupingBy { it }.eachCount()
}
