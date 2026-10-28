package viaduct.engine.runtime2.correctresolution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerObserver

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

    private val inputsByOwner = ConcurrentHashMap<Pair<ResolverTarget, ResolverOccurrenceId>, ConcurrentLinkedQueue<Map<String, CheckerInput>>>()

    override fun onCheckerInvocation(
        observation: CheckerInvocationObservation,
        inputs: Map<String, CheckerInput>,
    ) {
        val owner = observation.checkedTarget to ResolverOccurrenceId.at(observation.logicalQueryRoot, observation.occurrencePath)
        inputsByOwner.computeIfAbsent(owner) { ConcurrentLinkedQueue() }.add(inputs.toMap())
        delegate.onCheckerInvocation(observation, inputs)
    }

    fun checkerInputs(
        target: ResolverTarget,
        occurrenceId: ResolverOccurrenceId
    ): List<Map<String, CheckerInput>> = inputsByOwner[target to occurrenceId]?.toList().orEmpty()

    fun hasCheckerInputs(): Boolean = inputsByOwner.isNotEmpty()

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
