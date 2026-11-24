@file:Suppress("DEPRECATION") // CoroutineInterop bridges the configured service's coroutine context.

package viaduct.engine.runtime2

import graphql.ExecutionInput as GraphQLExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStrategy
import graphql.execution.ExecutionStrategyParameters
import graphql.execution.instrumentation.ChainedInstrumentation
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.preparsed.PreparsedDocumentProvider
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import viaduct.engine.airbnb.EngineGraphQLJavaCompat
import viaduct.engine.api.CompleteSelectionSetOptions
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.FullSchema
import viaduct.engine.api.NodeReference
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolveRootFieldReferenceOptions
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.ResolverType
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.FieldSelectivityProvider
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.DispatcherExecutionContext
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldDataLoader
import viaduct.engine.runtime.NodeDataLoader
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.execution.WrappedCoroutineExecutionStrategy
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.engine.runtime.validation.IncrementalDirectiveValidation
import viaduct.engine.runtime2.airbnb.DispatcherResolverRegistryFactory
import viaduct.engine.runtime2.airbnb.Engine2ResolverRegistryFactory
import viaduct.engine.runtime2.execution.EmptyFieldExecutionScope
import viaduct.engine.runtime2.execution.Engine2ExecutionContext
import viaduct.engine.runtime2.execution.RequestLifetimeInstrumentation
import viaduct.engine.runtime2.execution.Runtime2CallerCancellation
import viaduct.engine.runtime2.execution.Runtime2CallerCancellationKey
import viaduct.engine.runtime2.execution.Runtime2DispatchStrategy
import viaduct.engine.runtime2.execution.Runtime2ExecutionHandle
import viaduct.engine.runtime2.execution.Runtime2ExecutionStrategy
import viaduct.engine.runtime2.execution.Runtime2ScopedFieldBatchExecutor
import viaduct.engine.runtime2.execution.Runtime2WiringFactory
import viaduct.engine.runtime2.execution.completeResolvedData
import viaduct.engine.runtime2.execution.instrumentForRuntime2
import viaduct.engine.runtime2.execution.runtime2NodeReferenceOf
import viaduct.engine.runtime2.execution.runtime2RootFieldReferenceOf
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.GlobalIDCodec

/** Dispatcher-backed implementation of the Engine API using runtime2 Resolution. */
class Engine2(
    override val schema: EngineSchema,
    private val fullSchema: FullSchema,
    private val dispatcherRegistry: DispatcherRegistry,
    documentProvider: PreparsedDocumentProvider,
    private val globalIDCodec: GlobalIDCodec,
    fieldSelectivityProvider: FieldSelectivityProvider = FieldSelectivityProvider.Never,
    resolverInstrumentation: ViaductResolverInstrumentation = ViaductResolverInstrumentation.DEFAULT,
    private val dataFetcherExceptionHandler: DataFetcherExceptionHandler,
    additionalInstrumentation: Instrumentation? = null,
    private val coroutineInterop: CoroutineInterop = DefaultCoroutineInterop,
    resolverRegistryFactory: Engine2ResolverRegistryFactory = DispatcherResolverRegistryFactory,
    usePrecomputedConstructionDemand: Boolean = true,
    private val flagManager: FlagManager = FlagManager.Disabled,
) : Engine, EngineGraphQLJavaCompat {
    private val schemas = ViaductAndGJSchema.fromGraphQLSchema(fullSchema.schema)
    private val engineSelectionSetFactory = EngineSelectionSetFactoryImpl(fullSchema)
    private val runtime2DispatcherRegistry =
        dispatcherRegistry.instrumentForRuntime2(resolverInstrumentation)
    private val resolverRegistry = resolverRegistryFactory.create(fullSchema, schemas, runtime2DispatcherRegistry, fieldSelectivityProvider)
    private val world = Assumptions.of(
        schemas.loweredSchema,
        resolverRegistry,
        usePrecomputedConstructionDemand = usePrecomputedConstructionDemand,
    )
    private val requestContextKey = RequestContextKey(this)
    private val requestLifetimeInstrumentation = RequestLifetimeInstrumentation()
    private val graphql =
        GraphQL
            .newGraphQL(Runtime2WiringFactory(schemas.loweredSchema).wire(schema.schema))
            .preparsedDocumentProvider(IncrementalDirectiveValidation(documentProvider, schema))
            .queryExecutionStrategy(createExecutionStrategy())
            .mutationExecutionStrategy(createExecutionStrategy())
            .subscriptionExecutionStrategy(createExecutionStrategy())
            .instrumentation(
                additionalInstrumentation?.let {
                    ChainedInstrumentation(listOf(requestLifetimeInstrumentation, it))
                } ?: requestLifetimeInstrumentation,
            ).build()

    @Deprecated("Airbnb use only")
    suspend fun completeResolvedData(
        data: EngineObjectData.Sync,
        environment: DataFetchingEnvironment,
    ): ExecutionResult = completeResolvedData(data, environment, dataFetcherExceptionHandler)

    private fun createExecutionStrategy(): ExecutionStrategy {
        val wrappedStrategy =
            WrappedCoroutineExecutionStrategy(
                object : ExecutionStrategy(dataFetcherExceptionHandler) {
                    override fun execute(
                        context: ExecutionContext,
                        parameters: ExecutionStrategyParameters,
                    ): CompletableFuture<ExecutionResult> =
                        coroutineInterop.scopedFuture {
                            val batchingEnabled = flagManager.isEnabled(FlagManager.Flags.ENGINE2_BATCHING)
                            // Capture the context after the wrapper selects the request's dispatcher.
                            Runtime2ExecutionStrategy(
                                world = world,
                                sourceSchema = schemas.graphQLSchema,
                                resolverCoroutineContext = coroutineContext,
                                engineExecutionContextFactory = { invocationContext(it, batchingEnabled) },
                                dataFetcherExceptionHandler = dataFetcherExceptionHandler,
                                batchingEnabled = batchingEnabled,
                            ).execute(context, parameters).await()
                        }
                },
                coroutineInterop,
                dataFetcherExceptionHandler,
            )
        return object : ExecutionStrategy(dataFetcherExceptionHandler) {
            override fun execute(
                context: ExecutionContext,
                parameters: ExecutionStrategyParameters,
            ): CompletableFuture<ExecutionResult> {
                val requestContext = context.graphQLContext.get<RequestContext?>(requestContextKey)
                    ?: return wrappedStrategy.execute(context, parameters)
                // Document loading may resume GraphQL outside the request coroutine.
                return CoroutineScope(requestContext.coroutineContext).future {
                    wrappedStrategy.execute(context, parameters).await()
                }
            }
        }
    }

    override fun getGraphQL(): GraphQL = graphql

    override fun createEngineExecutionContext(
        requestContext: Any?,
        incrementalExecution: Boolean,
    ): EngineExecutionContext = RequestEngineExecutionContext(requestContext)

    private fun invocationContext(
        invocation: ExecutionContext,
        batchingEnabled: Boolean
    ): DispatcherExecutionContext {
        // GraphQL Java substitutes its GraphQLContext when no legacy context was supplied.
        val legacyRequestContext = invocation.getContext<Any?>()?.takeUnless { it === invocation.graphQLContext }
        val request = invocation.graphQLContext.get<RequestContext?>(requestContextKey)?.engineExecutionContext
            ?: (invocation.getLocalContext<Any?>() as? CompositeLocalContext)
                ?.get<RequestEngineExecutionContext>()
                ?.takeIf { it.engine === this }
            ?: RequestEngineExecutionContext(legacyRequestContext)
        val fieldLoaders = ConcurrentHashMap<FieldResolverExecutor, FieldDataLoader>()
        return object : DispatcherExecutionContext by request, Engine2ExecutionContext {
            override val graphQLExecutionContext = invocation

            override fun fieldDataLoader(resolver: FieldResolverExecutor): FieldDataLoader =
                if (batchingEnabled && resolver.isBatching) {
                    fieldLoaders.computeIfAbsent(resolver) { FieldDataLoader(Runtime2ScopedFieldBatchExecutor(it)) }
                } else {
                    request.fieldDataLoader(resolver)
                }
        }
    }

    override suspend fun execute(
        executionInput: ExecutionInput,
        incrementalExecution: Boolean,
    ): ExecutionResult {
        val engineContext = RequestEngineExecutionContext(executionInput.requestContext)
        val callerCancellation = Runtime2CallerCancellation()
        return try {
            // Capture context through the service's Java/coroutine bridge, including any
            // framework-specific request context installed by its CoroutineInterop.
            coroutineInterop.scopedFuture {
                val builder =
                    GraphQLExecutionInput
                        .newExecutionInput()
                        .query(executionInput.operationText)
                        .variables(executionInput.variables)
                        .graphQLContext { context ->
                            context.put(requestContextKey, RequestContext(engineContext, coroutineContext))
                            context.put(Runtime2CallerCancellationKey, callerCancellation)
                        }
                executionInput.operationName?.let(builder::operationName)
                executionInput.requestContext?.let(builder::context)
                graphql.executeAsync(builder.build()).await()
            }.await()
        } catch (cancellation: CancellationException) {
            callerCancellation.cancel(cancellation)
            throw cancellation
        }
    }

    override suspend fun resolveSelectionSet(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData.Sync = executionHandle.requireOwned().resolveSelectionSet(selectionSet, options)

    override suspend fun resolveRootFieldReference(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        rootFieldPath: List<String>,
        arguments: Map<String, Any?>,
        selectionSet: EngineSelectionSet,
        options: ResolveRootFieldReferenceOptions,
    ): EngineObjectData? =
        throw UnsupportedOperationException(
            "Engine2 resolves root-field references returned by resolvers through runtime2 " +
                "Resolution; direct Engine.resolveRootFieldReference calls are not supported",
        )

    private fun EngineExecutionContext.ExecutionHandle.requireOwned(): Runtime2ExecutionHandle {
        val handle = this as? Runtime2ExecutionHandle
            ?: throw IllegalArgumentException("Execution handle does not belong to engine2")
        require(handle.engine === this@Engine2) { "Execution handle belongs to a different engine2 instance" }
        return handle
    }

    private inner class RequestEngineExecutionContext(
        override val requestContext: Any?,
    ) : DispatcherExecutionContext {
        override val fullSchema: FullSchema get() = this@Engine2.fullSchema
        override val scopedSchema: EngineSchema get() = schema
        override val activeSchema: EngineSchema get() = fullSchema
        override val engineSelectionSetFactory: EngineSelectionSet.Factory get() = this@Engine2.engineSelectionSetFactory
        override val globalIDCodec: GlobalIDCodec get() = this@Engine2.globalIDCodec
        override val engine: Engine get() = this@Engine2
        override val executionHandle: EngineExecutionContext.ExecutionHandle? = null
        override val fieldScope: EngineExecutionContext.FieldExecutionScope = EmptyFieldExecutionScope

        override suspend fun resolveSelectionSet(
            selectionSet: EngineSelectionSet,
            options: ResolveSelectionSetOptions,
        ): EngineObjectData.Sync = error("Selection execution requires an invocation-local Runtime2EngineExecutionContext")

        override fun projectOwnedSelections(
            selectionSet: EngineSelectionSet,
            resolverType: ResolverType,
        ): EngineSelectionSet =
            Runtime2ResolverSelectionProjector(fullSchema, dispatcherRegistry)
                .project(selectionSet, resolverType)

        override suspend fun completeSelectionSet(
            selectionSet: RequiredSelectionSet,
            arguments: Map<String, Any?>,
            options: CompleteSelectionSetOptions,
        ): ExecutionResult = throw UnsupportedOperationException("Engine2 does not yet support completeSelectionSet")

        override fun createNodeReference(
            id: String,
            graphQLObjectType: GraphQLObjectType,
        ): NodeReference = runtime2NodeReferenceOf(schemas.loweredSchema, id, graphQLObjectType)

        override fun createRootFieldReference(
            rootFieldPath: List<String>,
            type: GraphQLObjectType,
            args: Map<String, Any?>,
        ): RootFieldReference = runtime2RootFieldReferenceOf(schemas.loweredSchema, rootFieldPath, type, args)

        override fun hasModernNodeResolver(typeName: String): Boolean = dispatcherRegistry.getNodeResolverDispatcher(typeName) != null

        /**
         * Fallback for disabled batching and non-batching fields. Enabled batching uses the
         * invocation-local registry so independent GraphQL executions cannot share loaders.
         */
        override fun fieldDataLoader(resolver: FieldResolverExecutor): FieldDataLoader = FieldDataLoader(resolver, dispatchStrategy = Runtime2DispatchStrategy)

        /** Nodes retain immediate dispatch with a fresh loader per invocation. */
        override fun nodeDataLoader(resolver: NodeResolverExecutor): NodeDataLoader = NodeDataLoader(resolver, dispatchStrategy = Runtime2DispatchStrategy)
    }
}

private data class RequestContextKey(
    val engine: Engine2,
)

private data class RequestContext(
    val engineExecutionContext: DispatcherExecutionContext,
    val coroutineContext: CoroutineContext,
)
