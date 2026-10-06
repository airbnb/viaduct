package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.runtime2.model.registry.Assumptions

/**
 * Structurally immutable configuration and state references for one semantic operation.
 * More specific contexts implement this contract by delegating to their owning operation.
 * [D] preserves the dispatcher type. Nothing terminates the recursive task/context bounds.
 */
interface SharedOperationContext<out D : SharedTaskDispatcher<Nothing, Nothing, Nothing>> {
    val world: Assumptions
    val variableBindings: VariableBindingsState
    val resolverObserver: ResolverObserver
    val checkerObserver: CheckerObserver
    val engineExecutionContext: EngineExecutionContext?
    val dispatcher: D

    companion object {
        /** Creates a standalone semantic operation without task-dispatch capability. */
        @JvmStatic
        fun create(
            world: Assumptions,
            variableBindings: VariableBindingsState = VariableBindingsState(),
            resolverObserver: ResolverObserver = ResolverObserver.NOP,
            checkerObserver: CheckerObserver = CheckerObserver.NOP,
            engineExecutionContext: EngineExecutionContext? = null,
        ): SharedOperationContext<Nothing> =
            object : SharedOperationContext<Nothing> {
                override val world = world
                override val variableBindings = variableBindings
                override val resolverObserver = resolverObserver
                override val checkerObserver = checkerObserver
                override val engineExecutionContext = engineExecutionContext
                override val dispatcher: Nothing
                    get() = error("This operation does not dispatch resolver tasks")
            }

        /** Creates an operation with a concretely typed dispatcher and stable shared state references. */
        @JvmStatic
        fun <D : SharedTaskDispatcher<Nothing, Nothing, Nothing>> create(
            world: Assumptions,
            dispatcher: D,
            variableBindings: VariableBindingsState = VariableBindingsState(),
            resolverObserver: ResolverObserver = ResolverObserver.NOP,
            checkerObserver: CheckerObserver = CheckerObserver.NOP,
            engineExecutionContext: EngineExecutionContext? = null,
        ): SharedOperationContext<D> =
            object : SharedOperationContext<D> {
                override val world = world
                override val variableBindings = variableBindings
                override val resolverObserver = resolverObserver
                override val checkerObserver = checkerObserver
                override val engineExecutionContext = engineExecutionContext
                override val dispatcher = dispatcher
            }
    }
}
