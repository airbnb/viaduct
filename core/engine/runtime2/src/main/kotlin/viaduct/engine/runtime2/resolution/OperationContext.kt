package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.CoroutineScope
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/**
 * One Resolution execution scope: scheduling, observation, cycle checking, and binding readiness.
 * Child execution scopes share the logical operation's configuration and mutable state references.
 */
internal interface OperationContext : SharedOperationContext<CoroutineTaskDispatcher> {
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
                dispatcher = CoroutineTaskDispatcher(
                    requestScope = requestScope,
                ),
            )
            return object :
                OperationContext,
                SharedOperationContext<CoroutineTaskDispatcher> by operationDelegate {
                override val cycleChecker = cycleChecker
                override val bindingsState = bindingsState
                override val passiveValues = PassiveValueResolutionLogic(this)
            }
        }
    }
}
