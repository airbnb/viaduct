package semantics.resolver26

import kotlinx.coroutines.CoroutineScope
import semantics.shared.CycleCheckState
import semantics.shared.SharedOperationContext

/**
 * One Resolver26 execution scope: scheduling, observation, cycle checking, and binding readiness.
 * Child execution scopes share the logical operation's configuration and mutable state references.
 */
internal interface OperationContext : SharedOperationContext<CoroutineTaskDispatcher<OrchestrationTask, SymbolicFieldPublicationOccurrence, SymbolicFieldCheckerPublicationOccurrence>> {
    val cycleChecker: CycleCheckState
    val bindingsState: BindingDeclarationsState
    val passiveValues: PassiveValueResolutionLogic

    /** Derives nested execution under its calling field task while retaining operation state. */
    fun forChildScope(requestScope: CoroutineScope): OperationContext = create(this, requestScope, cycleChecker, bindingsState)

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
                dispatcher = CoroutineTaskDispatcher<OrchestrationTask, SymbolicFieldPublicationOccurrence, SymbolicFieldCheckerPublicationOccurrence>(
                    requestScope = requestScope,
                    runFieldResolver = FieldResolverTask::execute,
                    cancelFieldResolver = FieldResolverTask::cancel,
                    runFieldChecker = FieldCheckerTask::execute,
                    cancelFieldChecker = FieldCheckerTask::cancel,
                ),
            )
            return object :
                OperationContext,
                SharedOperationContext<CoroutineTaskDispatcher<OrchestrationTask, SymbolicFieldPublicationOccurrence, SymbolicFieldCheckerPublicationOccurrence>> by operationDelegate {
                override val cycleChecker = cycleChecker
                override val bindingsState = bindingsState
                override val passiveValues = PassiveValueResolutionLogic(this)
            }
        }
    }
}
