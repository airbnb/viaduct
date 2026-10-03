package viaduct.engine.runtime2.execution

import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.DispatcherExecutionContext
import viaduct.engine.runtime.FieldDataLoader
import viaduct.engine.runtime.NodeDataLoader
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.fragmentFromDocument

/** Engine API facade whose selection execution is owned by the active Resolution field task. */
internal class QPlanEngineExecutionContext(
    private val delegate: EngineExecutionContext,
    private val schemas: ViaductAndGJSchema,
    private val resolutionContext: ResolutionExecutionContext,
) : EngineExecutionContext by delegate,
    DispatcherExecutionContext {
    override val executionHandle: EngineExecutionContext.ExecutionHandle by lazy {
        Runtime2ExecutionHandle(delegate.engine, schemas, resolutionContext)
    }

    override fun fieldDataLoader(resolver: FieldResolverExecutor): FieldDataLoader =
        requireNotNull(delegate as? DispatcherExecutionContext) {
            "Dispatcher invocation requires a DispatcherExecutionContext"
        }.fieldDataLoader(resolver)

    override fun nodeDataLoader(resolver: NodeResolverExecutor): NodeDataLoader =
        requireNotNull(delegate as? DispatcherExecutionContext) {
            "Dispatcher invocation requires a DispatcherExecutionContext"
        }.nodeDataLoader(resolver)

    override suspend fun resolveSelectionSet(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData.Sync {
        return (executionHandle as Runtime2ExecutionHandle).resolveSelectionSet(selectionSet, options)
    }
}

/** Invocation-local capability used by [Engine] nested-execution methods. */
internal class Runtime2ExecutionHandle(
    val engine: Engine,
    private val schemas: ViaductAndGJSchema,
    private val resolutionContext: ResolutionExecutionContext,
) : EngineExecutionContext.ExecutionHandle {
    suspend fun resolveSelectionSet(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData.Sync {
        require(options.operationType == Engine.OperationType.QUERY) {
            "Qplan selection execution currently supports Query only"
        }
        require(selectionSet.type == schemas.loweredSchema.requireQueryTypeDef().name) {
            "Cannot execute selections with type ${selectionSet.type} on schema root type ${schemas.loweredSchema.requireQueryTypeDef().name}"
        }
        val fragment =
            schemas.fragmentFromDocument(
                document = selectionSet.toFragment().parsedDocument,
                bindings = selectionSet.variables,
            )
        return resolutionContext.resolveSelectionSet(fragment.materializeSelections)
    }
}
