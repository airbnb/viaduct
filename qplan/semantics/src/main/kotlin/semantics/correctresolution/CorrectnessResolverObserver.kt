package semantics.correctresolution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import semantics.shared.OEROccurrence
import semantics.shared.ResolverInvocationObservation
import semantics.shared.ResolverObserver
import semantics.shared.RootFieldReferenceInvocationObservation
import semantics.shared.SharedOERContext

/**
 * Records invocation identities plus Query roots, their independent-input or shared-OER ownership
 * role, concrete shared-owner addresses, and reference hops. Query and reference records preserve
 * duplicates; invocation identities form a set. Full invocation events retain the exact object and
 * Query inputs supplied at runtime. The two-argument Query callback is the generic compatibility
 * seam; role-specific callbacks delegate through it and retain the evidence needed to validate
 * ownership. Correctness consumers read snapshots directly from this recorder. Use a fresh recorder
 * for each semantic operation.
 *
 * A subclass that overrides a recording callback must call the corresponding `super` implementation
 * to preserve this recorder's correctness evidence before adding its own records. Consumers that need
 * only a subset of these records should implement [ResolverObserver] directly instead.
 */
open class CorrectnessResolverObserver(
    private val delegate: ResolverObserver = ResolverObserver.NOP,
) : ResolverObserver {
    private val invokedOccurrences = ConcurrentHashMap.newKeySet<ResolverOccurrenceId>()
    private val invocations =
        ConcurrentHashMap<ResolverOccurrenceId, ConcurrentLinkedQueue<ResolverInvocationObservation>>()

    override fun onResolverInvocation(observation: ResolverInvocationObservation) {
        invokedOccurrences += observation.resolverOccurrenceId
        invocations
            .computeIfAbsent(observation.resolverOccurrenceId) { ConcurrentLinkedQueue() }
            .add(observation)
        delegate.onResolverInvocation(observation)
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

    override fun onQueryFragmentPrepared(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    ) {
        recordQueryFragmentResult(resolverOccurrenceId, result)
        queryFragmentObservations.add(
            QueryFragmentObservation(resolverOccurrenceId, result),
        )
    }

    override fun onIndependentQueryFragmentPrepared(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    ) {
        onQueryFragmentPrepared(resolverOccurrenceId, result)
        independentQueryObservations.add(
            IndependentQueryObservation(resolverOccurrenceId, result),
        )
    }

    override fun onQueryFragmentPrepared(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
        owningOccurrence: OEROccurrence,
    ) {
        onQueryFragmentPrepared(resolverOccurrenceId, result)
        queryScopeObservations.add(
            QueryScopeObservation(resolverOccurrenceId, result, owningOccurrence),
        )
    }

    override fun onQueryFragmentOwnerAddress(
        resolverOccurrenceId: ResolverOccurrenceId,
        resolverOER: OEROccurrence,
        resolverKey: ObjectEngineResult.ObjectKey,
    ) {
        queryOwnerAddressObservations.add(
            QueryOwnerAddressObservation(resolverOccurrenceId, resolverOER, resolverKey),
        )
    }

    private fun recordQueryFragmentResult(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    ) {
        queryResults
            .computeIfAbsent(resolverOccurrenceId) { ConcurrentLinkedQueue() }
            .add(result)
        delegate.onQueryFragmentPrepared(resolverOccurrenceId, result)
    }

    override fun onQueryOERPrepared(
        queryOER: SharedOERContext,
        owningOccurrence: OEROccurrence,
        queryOERDepth: Int?,
    ) {
        check(associatedQueryResults.putIfAbsent(owningOccurrence.target, queryOER.occurrence.target) == null) {
            "Object occurrence was associated with a Query OER twice"
        }
        onQueryOERPrepared(queryOER, queryOERDepth)
        delegate.onQueryOERPrepared(queryOER, owningOccurrence, queryOERDepth)
    }

    override fun onQueryOERPrepared(
        queryOER: SharedOERContext,
        queryOERDepth: Int?,
    ) {
        val result = queryOER.occurrence.target
        check(queryOERs.putIfAbsent(result, queryOER) == null) {
            "Query OER was prepared twice"
        }
        queryOERDepth?.let { depth ->
            require(depth > 0) { "Query-OER depth must be positive" }
            check(queryOERDepths.putIfAbsent(result, depth) == null) {
                "Query-OER depth was recorded twice"
            }
        }
    }

    fun queryOER(result: ObjectEngineResult): SharedOERContext? = queryOERs[result]

    fun allQueryOERs(): Map<ObjectEngineResult, SharedOERContext> = queryOERs.toMap()

    fun associatedQueryResult(owner: ObjectEngineResult): ObjectEngineResult? = associatedQueryResults[owner] ?: queryOERs[owner]?.occurrence?.target

    fun allQueryOERDepths(): Map<ObjectEngineResult, Int> = queryOERDepths.toMap()

    fun queryFragmentResults(resolverOccurrenceId: ResolverOccurrenceId): List<ObjectEngineResult> = queryResults[resolverOccurrenceId]?.toList().orEmpty()

    fun allQueryFragmentResults(): Map<ResolverOccurrenceId, List<ObjectEngineResult>> = queryResults.mapValues { (_, results) -> results.toList() }

    internal fun allQueryFragmentScopes(): Map<ResolverOccurrenceId, List<OEROccurrence>> =
        queryScopeObservations
            .groupBy(QueryScopeObservation::resolverOccurrenceId)
            .mapValues { (_, observations) -> observations.map(QueryScopeObservation::owningOccurrence) }

    /** [independentQueryOwners] must come from source-replayed root-field-reference invocations. */
    internal fun queryFragmentOwnershipIsConsistent(independentQueryOwners: Set<ResolverOccurrenceId>): Boolean {
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

        val scopeByResult = java.util.IdentityHashMap<ObjectEngineResult, OEROccurrenceAddress>()
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
            java.util.IdentityHashMap<ObjectEngineResult, ResolverOccurrenceId>()
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
            java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<ObjectEngineResult, Boolean>(),
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

    override fun onRootFieldReferenceInvocation(observation: RootFieldReferenceInvocationObservation) {
        rootFieldReferenceInvocations.add(observation)
        delegate.onRootFieldReferenceInvocation(observation)
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
