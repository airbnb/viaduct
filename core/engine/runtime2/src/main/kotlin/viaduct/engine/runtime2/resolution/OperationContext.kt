package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.registry.ParentConstructionDemandTemplate
import viaduct.engine.runtime2.model.registry.PreparedParentDemand
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/**
 * One Resolution execution scope: scheduling, observation, cycle checking, and binding readiness.
 * Child execution scopes share the logical operation's configuration and mutable state references.
 */
internal interface OperationContext : SharedOperationContext<CoroutineTaskDispatcher> {
    val requestScope: CoroutineScope
    val cycleChecker: CycleCheckState
    val bindingsState: BindingDeclarationsState
    val passiveValues: PassiveValueResolutionLogic
    val demandCache: OperationDemandCache

    /** Derives nested execution under its calling field task while retaining operation state. */
    fun forChildScope(requestScope: CoroutineScope): OperationContext = create(this, requestScope, cycleChecker, bindingsState, demandCache)

    companion object {
        fun create(
            base: SharedOperationContext<*>,
            requestScope: CoroutineScope,
            cycleChecker: CycleCheckState = CycleCheckState.create(),
            bindingsState: BindingDeclarationsState = BindingDeclarationsState(),
            demandCache: OperationDemandCache = OperationDemandCache(),
        ): OperationContext {
            val operationDelegate = SharedOperationContext.create(
                world = base.world,
                variableBindings = base.variableBindings,
                resolutionObserver = base.resolutionObserver,
                engineExecutionContext = base.engineExecutionContext,
                dispatcher = CoroutineTaskDispatcher(
                    requestScope = requestScope,
                ),
            )
            return object :
                OperationContext,
                SharedOperationContext<CoroutineTaskDispatcher> by operationDelegate {
                override val requestScope = requestScope
                override val cycleChecker = cycleChecker
                override val bindingsState = bindingsState
                override val passiveValues = PassiveValueResolutionLogic(this)
                override val demandCache = demandCache
            }
        }
    }
}

/** Dependency choices are stable within one operation; recursive traversal state stays local. */
internal class OperationDemandCache {
    val successorDemands by lazy { ConcurrentHashMap<SuccessorBoundary, SelectionForest>() }
    val parentDemands by lazy { ConcurrentHashMap<ParentConstructionDemandTemplate, PreparedParentDemand>() }
}
