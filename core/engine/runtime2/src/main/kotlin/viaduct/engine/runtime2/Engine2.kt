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
import graphql.language.FragmentDefinition
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import java.util.concurrent.CompletableFuture
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
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.FullSchema
import viaduct.engine.api.NodeReference
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolutionPolicy
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
import viaduct.engine.runtime2.airbnb.DispatcherResolverRegistryFactory
import viaduct.engine.runtime2.airbnb.Engine2ResolverRegistryFactory
import viaduct.engine.runtime2.execution.Engine2ExecutionContext
import viaduct.engine.runtime2.execution.OperationSelectionCache
import viaduct.engine.runtime2.execution.QPlanCallerCancellation
import viaduct.engine.runtime2.execution.QPlanCallerCancellationKey
import viaduct.engine.runtime2.execution.QPlanExecutionStrategy
import viaduct.engine.runtime2.execution.QPlanInstrumentation
import viaduct.engine.runtime2.execution.QPlanWiringFactory
import viaduct.engine.runtime2.execution.Runtime2DispatchStrategy
import viaduct.engine.runtime2.execution.Runtime2ExecutionHandle
import viaduct.engine.runtime2.execution.completeResolvedData
import viaduct.engine.runtime2.execution.instrumentForRuntime2
import viaduct.engine.runtime2.execution.runtime2NodeReferenceOf
import viaduct.engine.runtime2.execution.runtime2RootFieldReferenceOf
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
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
    /** Reuses decoded operations per cached GraphQL document and the demand analyses derived from them. */
    usePlanCache: Boolean = false,
) : Engine, EngineGraphQLJavaCompat {
    private val schemas = ViaductAndGJSchema.fromGraphQLSchema(fullSchema.schema)
    private val engineSelectionSetFactory = EngineSelectionSetFactoryImpl(fullSchema)
    private val runtime2DispatcherRegistry =
        dispatcherRegistry.instrumentForRuntime2(resolverInstrumentation)
    private val resolverRegistries = resolverRegistryFactory.create(fullSchema, schemas, runtime2DispatcherRegistry, fieldSelectivityProvider)
    private val bootstrapRegistry = resolverRegistries.forBootstrap()
    private val world = Assumptions.of(
        schemas.loweredSchema,
        bootstrapRegistry,
        usePrecomputedConstructionDemand = usePrecomputedConstructionDemand,
        usePlanCache = usePlanCache,
    )
    private val operationSelectionCache = if (usePlanCache) OperationSelectionCache() else null
    private val requestContextKey = RequestContextKey(this)
    private val qplanInstrumentation = QPlanInstrumentation()
    private val graphql =
        GraphQL
            .newGraphQL(QPlanWiringFactory(schemas.loweredSchema).wire(schema.schema))
            .preparsedDocumentProvider(documentProvider)
            .queryExecutionStrategy(createExecutionStrategy())
            .mutationExecutionStrategy(createExecutionStrategy())
            .subscriptionExecutionStrategy(createExecutionStrategy())
            .instrumentation(
                additionalInstrumentation?.let {
                    ChainedInstrumentation(listOf(qplanInstrumentation, it))
                } ?: qplanInstrumentation,
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
                            // Capture the context after the wrapper selects the request's dispatcher.
                            QPlanExecutionStrategy(
                                world = world,
                                sourceSchema = schemas.graphQLSchema,
                                resolverCoroutineContext = coroutineContext,
                                engineExecutionContextFactory = ::invocationContext,
                                dataFetcherExceptionHandler = dataFetcherExceptionHandler,
                                worldForExecution = ::worldForExecution,
                                operationSelectionCache = operationSelectionCache,
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

    private fun worldForExecution(invocation: ExecutionContext): Assumptions {
        val registry = resolverRegistries.forExecution(invocation)
        return if (registry === bootstrapRegistry) {
            world
        } else {
            Assumptions.of(
                schemas.loweredSchema,
                registry,
                usePrecomputedConstructionDemand = world.usePrecomputedConstructionDemand,
                usePlanCache = world.usePlanCache,
            )
        }
    }

    override fun getGraphQL(): GraphQL = graphql

    override fun createEngineExecutionContext(
        requestContext: Any?,
        incrementalExecution: Boolean,
    ): EngineExecutionContext = RequestEngineExecutionContext(requestContext)

    private fun invocationContext(invocation: ExecutionContext): DispatcherExecutionContext {
        val request = invocation.graphQLContext.get<RequestContext?>(requestContextKey)?.engineExecutionContext
            ?: (invocation.getLocalContext<Any?>() as? CompositeLocalContext)
                ?.get<RequestEngineExecutionContext>()
                ?.takeIf { it.engine === this }
            ?: RequestEngineExecutionContext(invocation.getContext<Any?>())
        return object : DispatcherExecutionContext by request, Engine2ExecutionContext {
            override val graphQLExecutionContext = invocation
        }
    }

    override suspend fun execute(
        executionInput: ExecutionInput,
        incrementalExecution: Boolean,
    ): ExecutionResult {
        val engineContext = RequestEngineExecutionContext(executionInput.requestContext)
        val callerCancellation = QPlanCallerCancellation()
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
                            context.put(QPlanCallerCancellationKey, callerCancellation)
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
        override val fieldScope: EngineExecutionContext.FieldExecutionScope = RootFieldExecutionScope

        override suspend fun resolveSelectionSet(
            selectionSet: EngineSelectionSet,
            options: ResolveSelectionSetOptions,
        ): EngineObjectData.Sync = error("Selection execution requires an invocation-local QPlanEngineExecutionContext")

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
         * Runtime2 currently gives each dispatcher invocation a fresh immediate loader so the
         * production loader cache cannot merge distinct semantic occurrences. This is a
         * transitional physical-dispatch policy, not a permanent prohibition on safe reuse.
         */
        override fun fieldDataLoader(resolver: FieldResolverExecutor): FieldDataLoader = FieldDataLoader(resolver, dispatchStrategy = Runtime2DispatchStrategy)

        /** See [fieldDataLoader] for the transitional per-invocation loader policy. */
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

private object RootFieldExecutionScope : EngineExecutionContext.FieldExecutionScope {
    override val fragments = emptyMap<String, FragmentDefinition>()
    override val variables = emptyMap<String, Any?>()
    override val resolutionPolicy = ResolutionPolicy.STANDARD
    override val attribution = ExecutionAttribution.DEFAULT
}
