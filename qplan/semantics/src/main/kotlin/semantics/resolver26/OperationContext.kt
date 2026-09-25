package semantics.resolver26

import kotlinx.coroutines.CoroutineScope
import semantics.shared.CycleCheckState
import semantics.shared.SharedOperationContext

/**
 * One Resolver26 execution scope: scheduling, observation, cycle checking, and binding readiness.
 * Child execution scopes share the logical operation's configuration and mutable state references.
 */
internal interface OperationContext : SharedOperationContext<CoroutineTaskDispatcher<OrchestrationTask, FieldPublicationOccurrence, Nothing>> {
    val cycleChecker: CycleCheckState
    val bindingsState: BindingDeclarationsState

    /** Derives nested execution under its calling field task while retaining operation state. */
    fun forChildScope(requestScope: CoroutineScope): OperationContext =
        create(this, requestScope, cycleChecker, bindingsState)

    companion object {
        fun create(
            base: SharedOperationContext<*>,
            requestScope: CoroutineScope,
            cycleChecker: CycleCheckState = CycleCheckState.create(),
            bindingsState: BindingDeclarationsState = BindingDeclarationsState(),
        ): OperationContext {
            val operationDelegate = SharedOperationContext.create(
                world = base.world,
                variableBindings = base.variableBindings,
                resolverObserver = base.resolverObserver,
                checkerObserver = base.checkerObserver,
                dispatcher = CoroutineTaskDispatcher<OrchestrationTask, FieldPublicationOccurrence, Nothing>(
                    requestScope = requestScope,
                    runFieldResolver = FieldResolverTask::execute,
                    cancelFieldResolver = FieldResolverTask::cancel,
                ),
            )
            return object : OperationContext,
                SharedOperationContext<CoroutineTaskDispatcher<OrchestrationTask, FieldPublicationOccurrence, Nothing>> by operationDelegate {
                override val cycleChecker = cycleChecker
                override val bindingsState = bindingsState
            }
        }
    }
}
