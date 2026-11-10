package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult.ObjectKey
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.registry.CheckerInput

/**
 * Receives semantically passive observations from one resolution operation.
 * Replacing a normally returning, non-mutating observer with [NOP] preserves semantic results;
 * synchronous callbacks can still affect failure or latency by throwing or blocking.
 * Observers subscribe by use case, not resolver family; null callbacks skip argument evaluation
 * and observation construction at emission sites.
 * Callbacks may run concurrently: recorders must be thread-safe, and independent invocations
 * have no guaranteed event order beyond the lifecycle relationships documented below.
 * Referenced tasks and OERs are live and may be unfinished; immutable observation properties do not
 * make the result graphs immutable or snapshot them.
 */
interface ResolutionObserver {
    /** Records a prepared task before dispatch; its properties include its creator and OER location. */
    val onTaskPrepared: ((ResolutionTask) -> Unit)?
        get() = null

    /**
     * Records task entry and termination. Cancellation may repeat FINISHED or finish a task before
     * entry. A null cause does not imply successful field output. Fatal orchestration errors
     * propagate without a required FINISHED.
     */
    val onTaskExecution: ((task: ResolutionTask, state: ResolutionTaskState, cause: Throwable?) -> Unit)?
        get() = null

    /**
     * Records a shared resolver Query OER after joint closure and before field dispatch, including
     * when its owner later fails before materializing Query input. The task supplies the paired
     * Query OER, owning object occurrence, and creator chain.
     * `queryOERDepth` is one for the Query OER associated with the independently rooted top-level
     * object, increasing when an object discovered inside a Query OER owns another Query OER.
     * Only the depth-first family reports this scheduler-validation depth; otherwise it is null.
     */
    val onQueryOERPrepared: ((task: SharedOrchestrationTask<*>, queryOERDepth: Int?) -> Unit)?
        get() = null

    /**
     * Records a nonempty declared Query fragment and its live root, whose selected cells and values
     * may be unfinished. A non-null `owningOccurrence` associates the fragment with its shared Query
     * OER and the object orchestration owning that scope; null asserts no shared-scope ownership.
     * Shared-scope preparation and the default independent-input callback both reach this callback.
     * This event observes neither a nested `ctx.query` call nor completed input materialization.
     */
    val onQueryFragmentPrepared: ((resolverOccurrenceId: ResolverOccurrenceId, result: ObjectEngineResult, owningOccurrence: OEROccurrence?) -> Unit)?
        get() = null

    /** Records independently rooted reference-target Query input; defaults to [onQueryFragmentPrepared] with a null owner. */
    val onIndependentQueryFragmentPrepared: ((resolverOccurrenceId: ResolverOccurrenceId, result: ObjectEngineResult) -> Unit)?
        get() = onQueryFragmentPrepared?.let { record -> { owner, result -> record(owner, result, null) } }

    /**
     * Records the concrete resolver address associated with a shared Query-fragment owner.
     * Kept separate from the scope association so validation can prove that an owner belongs to
     * its claimed scope.
     */
    val onQueryFragmentOwnerAddress: ((resolverOccurrenceId: ResolverOccurrenceId, resolverOER: OEROccurrence, resolverKey: ObjectKey) -> Unit)?
        get() = null

    /** Records the terminal cell read by a field resolver for a FromField variable, including null or error encountered along the path. */
    val onProviderRead: ((ResolverProviderReadObservation) -> Unit)?
        get() = null

    /**
     * Records each attempted resolver call inside its execution coroutine, after input preparation
     * and immediately before entering `FieldValueResolver.invoke`, with no intervening suspension,
     * dispatch, or interruptible coroutine-entry boundary.
     */
    val onResolverInvocation: ((ResolverInvocationObservation) -> Unit)?
        get() = null

    /**
     * Associates a publication with a reference hop after its helper returns. Records no return
     * value and does not guarantee resolver entry: input errors can short-circuit the helper.
     */
    val onRootFieldReferenceInvocation: ((RootFieldReferenceInvocationObservation) -> Unit)?
        get() = null

    /**
     * Records an attempted field or type checker call after input preparation and immediately
     * before invocation, with no intervening suspension or dispatch. Retains actual named input
     * projections for comparison with independently materialized inputs.
     */
    val onCheckerInvocation: ((observation: CheckerInvocationObservation, inputs: Map<String, CheckerInput>) -> Unit)?
        get() = null

    /** Associates one checker occurrence with its orchestration's shared Query OER. */
    val onCheckerQueryFragmentPrepared: ((checkerTarget: ResolverTarget, checkerOccurrenceId: ResolverOccurrenceId, result: ObjectEngineResult) -> Unit)?
        get() = null

    /** Observer that discards every event. */
    object NOP : ResolutionObserver
}
