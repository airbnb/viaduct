package semantics.correctresolution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.registry.ResolverTarget
import semantics.shared.CheckerInvocationObservation
import semantics.shared.CheckerObserver

/**
 * Associates each checker owner with the shared Query OER needed to replay its relation.
 * The target distinguishes a field check from the returned object's type check at the same path.
 */
class CorrectnessCheckerObserver(
    private val delegate: CheckerObserver = CheckerObserver.NOP,
) : CheckerObserver {
    private val queryResults =
        ConcurrentHashMap<Pair<ResolverTarget, ResolverOccurrenceId>, ConcurrentLinkedQueue<ObjectEngineResult>>()

    override fun onCheckerInvocation(observation: CheckerInvocationObservation) {
        delegate.onCheckerInvocation(observation)
    }

    override fun onCheckerQueryFragmentPrepared(
        checkerTarget: ResolverTarget,
        checkerOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    ) {
        queryResults
            .computeIfAbsent(checkerTarget to checkerOccurrenceId) { ConcurrentLinkedQueue() }
            .add(result)
        delegate.onCheckerQueryFragmentPrepared(checkerTarget, checkerOccurrenceId, result)
    }

    fun queryFragmentResults(
        checkerTarget: ResolverTarget,
        checkerOccurrenceId: ResolverOccurrenceId,
    ): List<ObjectEngineResult> = queryResults[checkerTarget to checkerOccurrenceId]?.toList().orEmpty()

    fun allQueryFragmentResults(): Map<Pair<ResolverTarget, ResolverOccurrenceId>, List<ObjectEngineResult>> = queryResults.mapValues { (_, results) -> results.toList() }
}
