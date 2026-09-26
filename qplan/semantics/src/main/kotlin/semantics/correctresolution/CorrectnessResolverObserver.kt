package semantics.correctresolution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import semantics.shared.ResolverInvocationObservation
import semantics.shared.ResolverObserver
import semantics.shared.RootFieldReferenceInvocationObservation
import semantics.shared.SharedOERContext

/**
 * Records invocation identities plus Query roots and reference hops. Query and reference records
 * preserve duplicates; invocation identities form a set. Subclasses can retain full invocation
 * events when counts, arguments, or inputs are needed. Correctness consumers read snapshots
 * directly from this recorder. Use a fresh recorder for each semantic operation.
 *
 * A subclass that overrides a recording callback must call the corresponding `super` implementation
 * to preserve this recorder's correctness evidence before adding its own records. Consumers that need
 * only a subset of these records should implement [ResolverObserver] directly instead.
 */
open class CorrectnessResolverObserver : ResolverObserver {
    private val invokedOccurrences = ConcurrentHashMap.newKeySet<ResolverOccurrenceId>()

    override fun onResolverInvocation(observation: ResolverInvocationObservation) {
        invokedOccurrences += observation.resolverOccurrenceId
    }

    /** Exact attempted invocations; count-sensitive consumers must retain their own event log. */
    fun invokedResolverOccurrences(): Set<ResolverOccurrenceId> = invokedOccurrences.toSet()

    private val queryResults =
        ConcurrentHashMap<ResolverOccurrenceId, ConcurrentLinkedQueue<ObjectEngineResult>>()
    private val queryOERs =
        ConcurrentHashMap<ObjectEngineResult, SharedOERContext>()
    private val rootFieldReferenceInvocations =
        ConcurrentLinkedQueue<RootFieldReferenceInvocationObservation>()

    override fun onQueryFragmentPrepared(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    ) {
        queryResults
            .computeIfAbsent(resolverOccurrenceId) { ConcurrentLinkedQueue() }
            .add(result)
    }

    override fun onQueryOERPrepared(queryOER: SharedOERContext) {
        val result = queryOER.occurrence.target
        check(queryOERs.putIfAbsent(result, queryOER) == null) {
            "Query OER was prepared twice"
        }
    }

    fun queryOER(result: ObjectEngineResult): SharedOERContext? =
        queryOERs[result]

    fun allQueryOERs(): Map<ObjectEngineResult, SharedOERContext> =
        queryOERs.toMap()

    fun queryFragmentResults(
        resolverOccurrenceId: ResolverOccurrenceId,
    ): List<ObjectEngineResult> = queryResults[resolverOccurrenceId]?.toList().orEmpty()

    fun allQueryFragmentResults(): Map<ResolverOccurrenceId, List<ObjectEngineResult>> =
        queryResults.mapValues { (_, results) -> results.toList() }

    override fun onRootFieldReferenceInvocation(
        observation: RootFieldReferenceInvocationObservation,
    ) {
        rootFieldReferenceInvocations.add(observation)
    }

    fun rootFieldReferenceInvocations(): List<RootFieldReferenceInvocationObservation> =
        rootFieldReferenceInvocations.toList()
}
