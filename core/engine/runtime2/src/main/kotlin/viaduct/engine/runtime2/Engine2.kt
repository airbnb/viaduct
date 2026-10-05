package viaduct.engine.runtime2

import graphql.ExecutionInput as GraphQLExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.execution.AbortExecutionException
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStrategy
import graphql.execution.ExecutionStrategyParameters
import graphql.execution.instrumentation.ChainedInstrumentation
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.preparsed.PreparsedDocumentProvider
import graphql.language.FragmentDefinition
import graphql.schema.GraphQLObjectType
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
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
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.FieldSelectivityProvider
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.DispatcherExecutionContext
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldDataLoader
import viaduct.engine.runtime.NodeDataLoader
import viaduct.engine.runtime.NodeEngineObjectDataImpl
import viaduct.engine.runtime.ObjectRootFieldReference
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.engine.runtime2.bootstrap.dispatcherRegistryInputs
import viaduct.engine.runtime2.bootstrap.resolverRegistryOf
import viaduct.engine.runtime2.execution.QPlanCallerCancellation
import viaduct.engine.runtime2.execution.QPlanCallerCancellationKey
import viaduct.engine.runtime2.execution.QPlanExecutionStrategy
import viaduct.engine.runtime2.execution.QPlanInstrumentation
import viaduct.engine.runtime2.execution.QPlanWiringFactory
import viaduct.engine.runtime2.execution.Runtime2ExecutionHandle
import viaduct.engine.runtime2.execution.instrumentForRuntime2
import viaduct.engine.runtime2.model.Assumptions
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
    dataFetcherExceptionHandler: DataFetcherExceptionHandler,
    additionalInstrumentation: Instrumentation? = null,
) : Engine {
    private val schemas = ViaductAndGJSchema.fromGraphQLSchema(fullSchema.schema)
    private val engineSelectionSetFactory = EngineSelectionSetFactoryImpl(fullSchema)
    private val runtime2DispatcherRegistry =
        dispatcherRegistry.instrumentForRuntime2(resolverInstrumentation)
    private val world =
        dispatcherRegistryInputs(
            fullSchema = fullSchema,
            schemas = schemas,
            dispatcherRegistry = runtime2DispatcherRegistry,
            fieldSelectivityProvider = fieldSelectivityProvider,
        ).let { inputs ->
            Assumptions.of(
                schemas.loweredSchema,
                resolverRegistryOf(
                    schema = schemas,
                    fieldResolvers = inputs.fieldResolvers,
                    nodeResolvers = inputs.nodeResolvers,
                    fieldCheckers = inputs.fieldCheckers,
                    typeCheckers = inputs.typeCheckers,
                    variableProviders = inputs.variableProviders,
                ),
            )
        }
    private val requestContextKey = RequestContextKey(this)
    private val qplanInstrumentation = QPlanInstrumentation()
    private val graphql =
        GraphQL
            .newGraphQL(QPlanWiringFactory(schemas.loweredSchema).wire(schema.schema))
            .preparsedDocumentProvider(documentProvider)
            .queryExecutionStrategy(
                QPlanExecutionStrategy(
                    world = world,
                    sourceSchema = schemas.graphQLSchema,
                    resolverCoroutineContext = Dispatchers.Default,
                    engineExecutionContextFactory = { executionContext ->
                        executionContext.graphQLContext.get<RequestContext>(requestContextKey).engineExecutionContext
                    },
                    dataFetcherExceptionHandler = dataFetcherExceptionHandler,
                ),
            ).mutationExecutionStrategy(
                QPlanExecutionStrategy(
                    world = world,
                    sourceSchema = schemas.graphQLSchema,
                    resolverCoroutineContext = Dispatchers.Default,
                    engineExecutionContextFactory = { executionContext ->
                        executionContext.graphQLContext.get<RequestContext>(requestContextKey).engineExecutionContext
                    },
                    dataFetcherExceptionHandler = dataFetcherExceptionHandler,
                ),
            ).subscriptionExecutionStrategy(UnsupportedSubscriptionExecutionStrategy)
            .instrumentation(
                additionalInstrumentation?.let {
                    ChainedInstrumentation(listOf(qplanInstrumentation, it))
                } ?: qplanInstrumentation,
            ).build()

    override suspend fun execute(executionInput: ExecutionInput): ExecutionResult {
        val engineContext = RequestEngineExecutionContext(executionInput.requestContext)
        val callerCancellation = QPlanCallerCancellation()
        val builder =
            GraphQLExecutionInput
                .newExecutionInput()
                .query(executionInput.operationText)
                .variables(executionInput.variables)
                .graphQLContext { context ->
                    context.put(requestContextKey, RequestContext(engineContext))
                    context.put(QPlanCallerCancellationKey, callerCancellation)
                }
        executionInput.operationName?.let(builder::operationName)
        @Suppress("DEPRECATION")
        executionInput.requestContext?.let(builder::context)
        return try {
            graphql.executeAsync(builder.build()).await()
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
        ): NodeReference = NodeEngineObjectDataImpl(id, graphQLObjectType, dispatcherRegistry, fieldScope.caller)

        override fun createRootFieldReference(
            rootFieldPath: List<String>,
            type: GraphQLObjectType,
            args: Map<String, Any?>,
        ): RootFieldReference = ObjectRootFieldReference(rootFieldPath, type, args, fieldScope.caller)

        override fun hasModernNodeResolver(typeName: String): Boolean = dispatcherRegistry.getNodeResolverDispatcher(typeName) != null

        /**
         * Runtime2 currently gives each dispatcher invocation a fresh immediate loader so the
         * production loader cache cannot merge distinct semantic occurrences. This is a
         * transitional physical-dispatch policy, not a permanent prohibition on safe reuse.
         */
        override fun fieldDataLoader(resolver: FieldResolverExecutor): FieldDataLoader = FieldDataLoader(resolver)

        /** See [fieldDataLoader] for the transitional per-invocation loader policy. */
        override fun nodeDataLoader(resolver: NodeResolverExecutor): NodeDataLoader = NodeDataLoader(resolver)
    }
}

private data class RequestContextKey(
    val engine: Engine2,
)

private data class RequestContext(
    val engineExecutionContext: DispatcherExecutionContext,
)

private object UnsupportedSubscriptionExecutionStrategy : ExecutionStrategy() {
    override fun execute(
        executionContext: ExecutionContext,
        parameters: ExecutionStrategyParameters,
    ): CompletableFuture<ExecutionResult> =
        CompletableFuture.completedFuture(
            AbortExecutionException("Engine2 does not support subscriptions").toExecutionResult(),
        )
}

private object RootFieldExecutionScope : EngineExecutionContext.FieldExecutionScope {
    override val fragments = emptyMap<String, FragmentDefinition>()
    override val variables = emptyMap<String, Any?>()
    override val resolutionPolicy = ResolutionPolicy.STANDARD
    override val attribution = ExecutionAttribution.DEFAULT
}
