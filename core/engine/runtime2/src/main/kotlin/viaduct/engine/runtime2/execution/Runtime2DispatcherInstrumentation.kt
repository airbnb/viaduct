package viaduct.engine.runtime2.execution

import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.instrumentation.resolver.ResolverFunction
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.DispatcherExecutionContext
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineObjectDataFactory
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.NodeResolverDispatcher
import viaduct.engine.runtime.instrumentation.resolver.InstrumentedEngineObjectData
import viaduct.engine.runtime.instrumentation.resolver.InstrumentedNodeResolverDispatcher

/** Rebuilds resolver instrumentation around dispatchers without assuming the old runtime context. */
internal fun DispatcherRegistry.instrumentForRuntime2(instrumentation: ViaductResolverInstrumentation): DispatcherRegistry =
    object : DispatcherRegistry by this {
        override fun getFieldResolverDispatcher(
            typeName: String,
            fieldName: String,
        ): FieldResolverDispatcher? =
            this@instrumentForRuntime2
                .getFieldResolverDispatcher(typeName, fieldName)
                ?.let { Runtime2InstrumentedFieldDispatcher(it, instrumentation, typeName to fieldName) }

        override fun getNodeResolverDispatcher(typeName: String): NodeResolverDispatcher? {
            val registered =
                this@instrumentForRuntime2.getNodeResolverDispatcher(typeName) ?: return null
            val dispatcher =
                if (registered is InstrumentedNodeResolverDispatcher) {
                    registered.dispatcher
                } else {
                    registered
                }
            return Runtime2InstrumentedNodeDispatcher(dispatcher, instrumentation)
        }
    }

private class Runtime2InstrumentedFieldDispatcher(
    private val delegate: FieldResolverDispatcher,
    private val instrumentation: ViaductResolverInstrumentation,
    private val coordinate: Coordinate,
) : FieldResolverDispatcher by delegate {
    override suspend fun resolve(
        arguments: Map<String, Any?>,
        objectValueFactory: EngineObjectDataFactory,
        queryValueFactory: EngineObjectDataFactory,
        selections: EngineSelectionSet?,
        context: EngineExecutionContext,
    ): Any? {
        val state =
            instrumentation.createInstrumentationState(
                ViaductResolverInstrumentation.CreateInstrumentationStateParameters(),
            )
        val instrumentationContext = ResolverInstrumentationContext(instrumentation, state)
        val objectFactory = objectValueFactory.instrumented(instrumentationContext)
        val queryFactory = queryValueFactory.instrumented(instrumentationContext)
        val resolverContext =
            Runtime2InstrumentedExecutionContext(
                context.requireDispatcherExecutionContext(),
                ExecutionAttribution.fromResolver(resolverMetadata.name),
                instrumentation,
                state,
            )
        return instrumentation
            .instrumentResolverExecution(
                ResolverFunction {
                    delegate.resolve(
                        arguments,
                        objectFactory,
                        queryFactory,
                        selections,
                        resolverContext,
                    )
                },
                ViaductResolverInstrumentation.InstrumentExecuteResolverParameters(
                    resolverMetadata = resolverMetadata,
                    fieldCoordinate = coordinate,
                ),
                state,
            ).resolve()
    }
}

private class Runtime2InstrumentedNodeDispatcher(
    private val delegate: NodeResolverDispatcher,
    private val instrumentation: ViaductResolverInstrumentation,
) : NodeResolverDispatcher by delegate {
    override suspend fun resolve(
        id: String,
        selections: EngineSelectionSet,
        context: EngineExecutionContext,
    ): EngineObjectData {
        val state =
            instrumentation.createInstrumentationState(
                ViaductResolverInstrumentation.CreateInstrumentationStateParameters(),
            )
        val resolverContext =
            Runtime2InstrumentedExecutionContext(
                context.requireDispatcherExecutionContext(),
                ExecutionAttribution.fromResolver(resolverMetadata.name),
                instrumentation,
                state,
            )
        return instrumentation
            .instrumentResolverExecution(
                ResolverFunction { delegate.resolve(id, selections, resolverContext) },
                ViaductResolverInstrumentation.InstrumentExecuteResolverParameters(
                    resolverMetadata = resolverMetadata,
                ),
                state,
            ).resolve()
    }
}

private class Runtime2InstrumentedExecutionContext(
    private val delegate: DispatcherExecutionContext,
    attribution: ExecutionAttribution,
    private val instrumentation: ViaductResolverInstrumentation,
    private val state: ViaductResolverInstrumentation.InstrumentationState,
) : DispatcherExecutionContext by delegate {
    override val fieldScope: EngineExecutionContext.FieldExecutionScope =
        object : EngineExecutionContext.FieldExecutionScope by delegate.fieldScope {
            override val attribution = attribution
        }

    override suspend fun resolveSelectionSet(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData.Sync =
        InstrumentedEngineObjectData.Sync(
            delegate.resolveSelectionSet(selectionSet, options),
            instrumentation,
            state,
        )
}

private fun EngineObjectDataFactory.instrumented(context: ResolverInstrumentationContext): EngineObjectDataFactory =
    EngineObjectDataFactory { supplied ->
        InstrumentedEngineObjectData.Sync(
            create(supplied ?: context),
            context.instrumentation,
            context.state,
        )
    }

private fun EngineExecutionContext.requireDispatcherExecutionContext(): DispatcherExecutionContext =
    this as? DispatcherExecutionContext
        ?: error("Runtime2 dispatcher instrumentation requires a DispatcherExecutionContext")
