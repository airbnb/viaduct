package semantics.correctresolution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import semantics.shared.CheckerInvocationObservation
import semantics.shared.CheckerObserver

/** Associates each checker owner with the shared Query OER needed to replay its relation. */
class CorrectnessCheckerObserver(
    private val delegate: CheckerObserver = CheckerObserver.NOP,
) : CheckerObserver {
    private val queryResults =
        ConcurrentHashMap<ResolverOccurrenceId, ConcurrentLinkedQueue<ObjectEngineResult>>()

    override fun onCheckerInvocation(observation: CheckerInvocationObservation) {
        delegate.onCheckerInvocation(observation)
    }

    override fun onCheckerQueryFragmentPrepared(
        checkerOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    ) {
        queryResults
            .computeIfAbsent(checkerOccurrenceId) { ConcurrentLinkedQueue() }
            .add(result)
        delegate.onCheckerQueryFragmentPrepared(checkerOccurrenceId, result)
    }

    fun queryFragmentResults(
        checkerOccurrenceId: ResolverOccurrenceId,
    ): List<ObjectEngineResult> = queryResults[checkerOccurrenceId]?.toList().orEmpty()

    fun allQueryFragmentResults(): Map<ResolverOccurrenceId, List<ObjectEngineResult>> =
        queryResults.mapValues { (_, results) -> results.toList() }
}
