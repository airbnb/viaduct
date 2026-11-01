package viaduct.engine.runtime2.contract

import java.util.concurrent.ConcurrentLinkedQueue
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver

/** Thread-safe exact checker-application evidence, deliberately separate from value correctness. */
class CheckerApplicationRecorder(
    private val delegate: ResolutionObserver = ResolutionObserver.NOP,
) : ResolutionObserver by delegate {
    private val observations = ConcurrentLinkedQueue<CheckerInvocationObservation>()

    override val onCheckerInvocation: (CheckerInvocationObservation, Map<String, CheckerInput>) -> Unit = { observation, inputs ->
        observations += observation
        delegate.onCheckerInvocation?.invoke(observation, inputs)
    }

    fun checkerApplications(): List<CheckerInvocationObservation> = observations.toList()

    /** Compares multisets so both missing and duplicate applications fail the judgment. */
    fun hasExactlyCheckerApplications(expected: Collection<CheckerInvocationObservation>): Boolean =
        observations.groupingBy { it }.eachCount() ==
            expected.groupingBy { it }.eachCount()
}
