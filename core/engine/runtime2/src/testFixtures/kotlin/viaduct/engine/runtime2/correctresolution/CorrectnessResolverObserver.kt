package viaduct.engine.runtime2.correctresolution

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolutionTask
import viaduct.engine.runtime2.resolution.framework.ResolutionTaskState
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.RootFieldReferenceInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOERContext
import viaduct.engine.runtime2.resolution.framework.SharedOrchestrationTask

/**
 * Records invocation identities plus Query roots, their independent-input or shared-OER ownership
 * role, concrete shared-owner addresses, checker inputs and Query roots, and reference hops. Query
 * and reference records preserve duplicates; invocation identities form a set. Checker targets
 * distinguish field and type checks at the same path. Full invocation events retain the exact object and
 * Query inputs supplied at runtime. The Query-fragment callback records preparation and optional
 * shared-scope ownership; independent-input preparation retains the same result evidence and separate role
 * evidence. Correctness consumers read snapshots directly from this recorder. Use a fresh recorder
 * for each semantic operation.
 *
 * A subclass that overrides a recording callback must call the corresponding `super` implementation
 * to preserve this recorder's correctness evidence before adding its own records. Consumers that need
 * only a subset of these records should implement [ResolutionObserver] directly instead.
 */
open class CorrectnessResolverObserver(
    private val delegate: ResolutionObserver = ResolutionObserver.NOP,
) : ResolutionObserver {
    override val onTaskPrepared: ((ResolutionTask) -> Unit)? get() = delegate.onTaskPrepared
    override val onTaskExecution: ((ResolutionTask, ResolutionTaskState, Throwable?) -> Unit)? get() = delegate.onTaskExecution

    private val checkerQueryResults =
        ConcurrentHashMap<Pair<ResolverTarget, ResolverOccurrenceId>, ConcurrentLinkedQueue<ObjectEngineResult>>()
    private val checkerInputsByOwner =
        ConcurrentHashMap<Pair<ResolverTarget, ResolverOccurrenceId>, ConcurrentLinkedQueue<Map<String, CheckerInput>>>()

    override val onCheckerInvocation: (CheckerInvocationObservation, Map<String, CheckerInput>) -> Unit = { observation, inputs ->
        val owner = observation.checkedTarget to ResolverOccurrenceId.at(observation.logicalQueryRoot, observation.occurrencePath)
        checkerInputsByOwner.computeIfAbsent(owner) { ConcurrentLinkedQueue() }.add(inputs.toMap())
        delegate.onCheckerInvocation?.invoke(observation, inputs)
    }

    fun checkerInputs(
        target: ResolverTarget,
        occurrenceId: ResolverOccurrenceId,
    ): List<Map<String, CheckerInput>> = checkerInputsByOwner[target to occurrenceId]?.toList().orEmpty()

    fun hasCheckerInputs(): Boolean = checkerInputsByOwner.isNotEmpty()

    override val onCheckerQueryFragmentPrepared: (ResolverTarget, ResolverOccurrenceId, ObjectEngineResult) -> Unit = { target, owner, result ->
        checkerQueryResults.computeIfAbsent(target to owner) { ConcurrentLinkedQueue() }.add(result)
        delegate.onCheckerQueryFragmentPrepared?.invoke(target, owner, result)
    }

    fun queryFragmentResults(
        checkerTarget: ResolverTarget,
        checkerOccurrenceId: ResolverOccurrenceId,
    ): List<ObjectEngineResult> = checkerQueryResults[checkerTarget to checkerOccurrenceId]?.toList().orEmpty()

    fun allCheckerQueryFragmentResults(): Map<Pair<ResolverTarget, ResolverOccurrenceId>, List<ObjectEngineResult>> = checkerQueryResults.mapValues { (_, results) -> results.toList() }

    /** Retains checker evidence when adversarial tests replace only the resolver observations. */
    fun copyCheckerObservationsFrom(other: CorrectnessResolverObserver) {
        other.checkerQueryResults.forEach { (owner, results) ->
            checkerQueryResults.computeIfAbsent(owner) { ConcurrentLinkedQueue() }.addAll(results)
        }
        other.checkerInputsByOwner.forEach { (owner, inputs) ->
            checkerInputsByOwner.computeIfAbsent(owner) { ConcurrentLinkedQueue() }.addAll(inputs)
        }
    }

    private val invokedOccurrences = ConcurrentHashMap.newKeySet<ResolverOccurrenceId>()
    private val invocations =
        ConcurrentHashMap<ResolverOccurrenceId, ConcurrentLinkedQueue<ResolverInvocationObservation>>()

    override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
        invokedOccurrences += observation.resolverOccurrenceId
        invocations
            .computeIfAbsent(observation.resolverOccurrenceId) { ConcurrentLinkedQueue() }
            .add(observation)
        delegate.onResolverInvocation?.invoke(observation)
    }

    /** Exact attempted invocations; count-sensitive consumers must retain their own event log. */
    fun invokedResolverOccurrences(): Set<ResolverOccurrenceId> = invokedOccurrences.toSet()

    fun resolverInvocations(resolverOccurrenceId: ResolverOccurrenceId): List<ResolverInvocationObservation> = invocations[resolverOccurrenceId]?.toList().orEmpty()

    fun hasResolverInvocations(): Boolean = invocations.isNotEmpty()

    fun allResolverInvocations(): List<ResolverInvocationObservation> = invocations.values.flatMap { observations -> observations.toList() }

    private val queryResults =
        ConcurrentHashMap<ResolverOccurrenceId, ConcurrentLinkedQueue<ObjectEngineResult>>()
    private val queryOERs =
        ConcurrentHashMap<ObjectEngineResult, SharedOERContext>()
    private val queryOERDepths =
        ConcurrentHashMap<ObjectEngineResult, Int>()
    private val associatedQueryResults = ConcurrentHashMap<ObjectEngineResult, ObjectEngineResult>()
    private val queryFragmentObservations =
        ConcurrentLinkedQueue<QueryFragmentObservation>()
    private val independentQueryObservations =
        ConcurrentLinkedQueue<IndependentQueryObservation>()
    private val queryScopeObservations =
        ConcurrentLinkedQueue<QueryScopeObservation>()
    private val queryOwnerAddressObservations =
        ConcurrentLinkedQueue<QueryOwnerAddressObservation>()
    private val rootFieldReferenceInvocations =
        ConcurrentLinkedQueue<RootFieldReferenceInvocationObservation>()

    override val onQueryFragmentPrepared: (ResolverOccurrenceId, ObjectEngineResult, OEROccurrence?) -> Unit = { owner, result, scope ->
        recordQueryFragmentResult(owner, result)
        queryFragmentObservations.add(QueryFragmentObservation(owner, result))
        if (scope != null) queryScopeObservations.add(QueryScopeObservation(owner, result, scope))
        delegate.onQueryFragmentPrepared?.invoke(owner, result, scope)
    }

    override val onIndependentQueryFragmentPrepared: (ResolverOccurrenceId, ObjectEngineResult) -> Unit = { owner, result ->
        recordQueryFragmentResult(owner, result)
        queryFragmentObservations.add(QueryFragmentObservation(owner, result))
        independentQueryObservations.add(IndependentQueryObservation(owner, result))
        delegate.onIndependentQueryFragmentPrepared?.invoke(owner, result)
    }

    override val onQueryFragmentOwnerAddress: (ResolverOccurrenceId, OEROccurrence, ObjectEngineResult.ObjectKey) -> Unit = { resolverOccurrenceId, resolverOER, resolverKey ->
        queryOwnerAddressObservations.add(
            QueryOwnerAddressObservation(resolverOccurrenceId, resolverOER, resolverKey),
        )
        delegate.onQueryFragmentOwnerAddress?.invoke(resolverOccurrenceId, resolverOER, resolverKey)
    }

    private fun recordQueryFragmentResult(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    ) {
        queryResults
            .computeIfAbsent(resolverOccurrenceId) { ConcurrentLinkedQueue() }
            .add(result)
    }

    override val onQueryOERPrepared: (SharedOrchestrationTask<*>, Int?) -> Unit = { task, depth ->
        recordQueryOERPrepared(task.queryOER, task.objectOER.occurrence, depth)
        delegate.onQueryOERPrepared?.invoke(task, depth)
    }

    /** Accepts synthetic evidence for adversarial oracle tests. */
    fun recordQueryOERPrepared(
        queryOER: SharedOERContext,
        owner: OEROccurrence?,
        depth: Int?,
    ) {
        if (owner != null) {
            check(associatedQueryResults.putIfAbsent(owner.target, queryOER.occurrence.target) == null) {
                "Object occurrence was associated with a Query OER twice"
            }
        }
        val result = queryOER.occurrence.target
        check(queryOERs.putIfAbsent(result, queryOER) == null) { "Query OER was prepared twice" }
        if (depth != null) {
            require(depth > 0) { "Query-OER depth must be positive" }
            check(queryOERDepths.putIfAbsent(result, depth) == null) { "Query-OER depth was recorded twice" }
        }
    }

    fun queryOER(result: ObjectEngineResult): SharedOERContext? = queryOERs[result]

    fun allQueryOERs(): Map<ObjectEngineResult, SharedOERContext> = queryOERs.toMap()

    fun associatedQueryResult(owner: ObjectEngineResult): ObjectEngineResult? = associatedQueryResults[owner] ?: queryOERs[owner]?.occurrence?.target

    fun allQueryOERDepths(): Map<ObjectEngineResult, Int> = queryOERDepths.toMap()

    fun queryFragmentResults(resolverOccurrenceId: ResolverOccurrenceId): List<ObjectEngineResult> = queryResults[resolverOccurrenceId]?.toList().orEmpty()

    fun allQueryFragmentResults(): Map<ResolverOccurrenceId, List<ObjectEngineResult>> = queryResults.mapValues { (_, results) -> results.toList() }

    fun allQueryFragmentScopes(): Map<ResolverOccurrenceId, List<OEROccurrence>> =
        queryScopeObservations
            .groupBy(QueryScopeObservation::resolverOccurrenceId)
            .mapValues { (_, observations) -> observations.map(QueryScopeObservation::owningOccurrence) }

    /** [independentQueryOwners] must come from source-replayed root-field-reference invocations. */
    fun queryFragmentOwnershipIsConsistent(independentQueryOwners: Set<ResolverOccurrenceId>): Boolean {
        val genericObservations = queryFragmentObservations.toList()
        val independentObservations = independentQueryObservations.toList()
        val scopeObservations = queryScopeObservations.toList()
        val ownerAddressObservations = queryOwnerAddressObservations.toList()
        if (
            genericObservations.size != independentObservations.size + scopeObservations.size ||
            !genericObservations.all { generic ->
                independentObservations.count { it.matches(generic) } +
                    scopeObservations.count { it.matches(generic) } == 1
            }
        ) {
            return false
        }

        val scopeByResult = IdentityHashMap<ObjectEngineResult, OEROccurrenceAddress>()
        val resultByScope = mutableMapOf<OEROccurrenceAddress, ObjectEngineResult>()
        if (!scopeObservations.all { observation ->
                val address = observation.owningOccurrence.address()
                val existingScope = scopeByResult[observation.result]
                val existingResult = resultByScope[address]
                val resultIsConsistent = existingScope == null || existingScope == address
                val scopeIsConsistent = existingResult == null || existingResult === observation.result
                if (resultIsConsistent && scopeIsConsistent) {
                    scopeByResult[observation.result] = address
                    resultByScope[address] = observation.result
                    true
                } else {
                    false
                }
            }
        ) {
            return false
        }
        val ownerByIndependentResult =
            IdentityHashMap<ObjectEngineResult, ResolverOccurrenceId>()
        val independentResultByOwner =
            mutableMapOf<ResolverOccurrenceId, ObjectEngineResult>()
        if (!independentObservations.all { observation ->
                val existingOwner = ownerByIndependentResult[observation.result]
                val existingResult = independentResultByOwner[observation.resolverOccurrenceId]
                val hasSharedRole = scopeByResult.containsKey(observation.result)
                val isJustifiedByReference = observation.resolverOccurrenceId in independentQueryOwners
                val resultIsConsistent =
                    existingOwner == null || existingOwner == observation.resolverOccurrenceId
                val ownerIsConsistent = existingResult == null || existingResult === observation.result
                if (isJustifiedByReference && !hasSharedRole && resultIsConsistent && ownerIsConsistent) {
                    ownerByIndependentResult[observation.result] = observation.resolverOccurrenceId
                    independentResultByOwner[observation.resolverOccurrenceId] = observation.result
                    true
                } else {
                    false
                }
            }
        ) {
            return false
        }

        val associatedQueryResults =
            Collections.newSetFromMap(
                IdentityHashMap<ObjectEngineResult, Boolean>(),
            ).apply { addAll(scopeByResult.keys) }
        // Query-side owners retain their containing object's association instead of introducing a
        // new scope rooted at that association's Query result.
        if (resultByScope.keys.any { scope -> scope.isRootOfAssociatedQuery(associatedQueryResults) }) {
            return false
        }

        return scopeObservations.all { scope ->
            val matchingAddresses = ownerAddressObservations.filter {
                it.resolverOccurrenceId == scope.resolverOccurrenceId
            }
            matchingAddresses.size == 1 && matchingAddresses.single().belongsTo(scope)
        } && ownerAddressObservations.all { address ->
            scopeObservations.count {
                it.resolverOccurrenceId == address.resolverOccurrenceId
            } == 1
        }
    }

    override val onRootFieldReferenceInvocation: (RootFieldReferenceInvocationObservation) -> Unit = { observation ->
        rootFieldReferenceInvocations.add(observation)
        delegate.onRootFieldReferenceInvocation?.invoke(observation)
    }

    fun rootFieldReferenceInvocations(): List<RootFieldReferenceInvocationObservation> = rootFieldReferenceInvocations.toList()
}

private data class OEROccurrenceAddress(
    val root: ObjectEngineResult,
    val path: List<PathComponent>,
    val target: ObjectEngineResult,
) {
    fun isRootOfAssociatedQuery(associatedQueryResults: Set<ObjectEngineResult>): Boolean = root === target && path.isEmpty() && associatedQueryResults.contains(target)
}

private fun OEROccurrence.address(): OEROccurrenceAddress = OEROccurrenceAddress(root = root, path = path, target = target)

private data class QueryFragmentObservation(
    val resolverOccurrenceId: ResolverOccurrenceId,
    val result: ObjectEngineResult,
)

private data class IndependentQueryObservation(
    val resolverOccurrenceId: ResolverOccurrenceId,
    val result: ObjectEngineResult,
) {
    fun matches(observation: QueryFragmentObservation): Boolean = resolverOccurrenceId == observation.resolverOccurrenceId && result === observation.result
}

private data class QueryScopeObservation(
    val resolverOccurrenceId: ResolverOccurrenceId,
    val result: ObjectEngineResult,
    val owningOccurrence: OEROccurrence,
) {
    fun matches(observation: QueryFragmentObservation): Boolean = resolverOccurrenceId == observation.resolverOccurrenceId && result === observation.result
}

private data class QueryOwnerAddressObservation(
    val resolverOccurrenceId: ResolverOccurrenceId,
    val resolverOER: OEROccurrence,
    val resolverKey: ObjectEngineResult.ObjectKey,
) {
    fun belongsTo(scope: QueryScopeObservation): Boolean {
        if (
            resolverOccurrenceId !=
            ResolverOccurrenceId.at(resolverOER.root, resolverOER.coordinate(resolverKey))
        ) {
            return false
        }
        val sameObjectOccurrence =
            resolverOER.root === scope.owningOccurrence.root &&
                resolverOER.path == scope.owningOccurrence.path &&
                resolverOER.target === scope.owningOccurrence.target
        val queryOccurrence =
            resolverOER.root === scope.result &&
                resolverOER.path.isEmpty() &&
                resolverOER.target === scope.result
        return sameObjectOccurrence || queryOccurrence
    }
}
