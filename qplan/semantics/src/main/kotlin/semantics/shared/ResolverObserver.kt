package semantics.shared

import model.ObjectEngineResult
import model.ObjectEngineResult.ObjectKey
import model.PathComponent
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.SelectionForest

/** Evidence connecting one symbolic reference hop to its independently rooted invocation. */
data class RootFieldReferenceInvocationObservation(
    val publicationRoot: ObjectEngineResult,
    val publicationPath: List<PathComponent>,
    val reference: RootFieldReferenceData,
    val invocationRoot: ObjectEngineResult,
    val invocationPath: List<PathComponent>,
    val invocationKey: ObjectKey,
    val suppliedDemand: SelectionForest,
)

/**
 * Receives semantically passive observations from one resolver operation.
 *
 * Replacing a normally returning, non-mutating observer with [NOP] preserves semantic
 * resolution results. Because callbacks are synchronous, an observer may still affect failure or
 * latency by throwing or blocking.
 * Implementations vary by observation use case, not by resolver family. Each callback defaults
 * to no-op so an observer can handle only the events it needs.
 *
 * Callbacks can run concurrently; implementations that retain observations must make their
 * recording thread-safe. Independent resolver invocations have no guaranteed event order
 * beyond the lifecycle relationships documented on each callback.
 * Referenced OERs are live and may be unfinished: immutable observation properties do not
 * make the referenced result graphs immutable or provide a snapshot of them.
 */
interface ResolverObserver {
    /**
     * Records one shared resolver Query OER after joint closure and before field dispatch.
     * [queryOERDepth] is one for the Query OER associated with the independently rooted top-level
     * object, and increases when an object discovered inside a Query OER owns another Query OER.
     * Depth is tracked by the depth-first families for scheduler validation and is otherwise null.
     */
    fun onQueryOERPrepared(
        queryOER: SharedOERContext,
        queryOERDepth: Int? = null,
    ) = Unit

    /** Records the paired root even when an owner fails before it can materialize Query input. */
    fun onQueryOERPrepared(
        queryOER: SharedOERContext,
        owningOccurrence: OEROccurrence,
        queryOERDepth: Int? = null,
    ) = onQueryOERPrepared(queryOER, queryOERDepth)

    /**
     * Compatibility callback for a nonempty declared Query fragment and its live root. Both
     * shared-scope and independently rooted preparation delegate here, so observers interested in
     * ownership must implement the corresponding role-specific callback instead. The root's
     * selected cells and values may be unfinished. This is not an observation of a nested
     * ctx.query call or of completed materialization.
     */
    fun onQueryFragmentPrepared(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    ) = Unit

    /** Records an independently rooted reference-target Query input. */
    fun onIndependentQueryFragmentPrepared(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    ) = onQueryFragmentPrepared(resolverOccurrenceId, result)

    /**
     * Associates an ordinary declared Query fragment with both its shared Query OER and the object
     * orchestration that owns that Query scope. Delegates to the two-argument compatibility
     * callback before recording the shared ownership role.
     */
    fun onQueryFragmentPrepared(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
        owningOccurrence: OEROccurrence,
    ) = onQueryFragmentPrepared(resolverOccurrenceId, result)

    /**
     * Records the concrete resolver address associated with a shared Query-fragment owner. The
     * containing-scope association and this address are separate so validation can prove that an
     * owner actually belongs to its claimed scope.
     */
    fun onQueryFragmentOwnerAddress(
        resolverOccurrenceId: ResolverOccurrenceId,
        resolverOER: OEROccurrence,
        resolverKey: ObjectKey,
    ) = Unit

    /**
     * Records each attempted resolver call immediately before entering FieldValueResolver.invoke.
     * Emit inside the execution coroutine, after input preparation, with no suspension,
     * dispatch, or interruptible coroutine-entry boundary between this event and the call.
     */
    fun onResolverInvocation(observation: ResolverInvocationObservation) = Unit

    /**
     * Associates a publication with a reference hop after its helper returns. This does not
     * record a return value or guarantee resolver entry: input errors can short-circuit the helper.
     */
    fun onRootFieldReferenceInvocation(observation: RootFieldReferenceInvocationObservation) = Unit

    /** Observer that discards every event. */
    object NOP : ResolverObserver
}
