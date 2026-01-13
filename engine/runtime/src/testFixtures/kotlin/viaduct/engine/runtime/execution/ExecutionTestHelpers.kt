@file:OptIn(ExperimentalCoroutinesApi::class)
@file:Suppress("ForbiddenImport", "DEPRECATION")

package viaduct.engine.runtime.execution

import com.github.benmanes.caffeine.cache.Caffeine
import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.GraphQLContext
import graphql.execution.AsyncExecutionStrategy
import graphql.execution.AsyncSerialExecutionStrategy
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.SimpleDataFetcherExceptionHandler
import graphql.execution.instrumentation.ChainedInstrumentation
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.execution.preparsed.PreparsedDocumentProvider
import graphql.parser.ParserOptions
import graphql.scalars.ExtendedScalars
import graphql.schema.DataFetcher
import graphql.schema.TypeResolver
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.kotest.property.Arb
import io.kotest.property.arbitrary.map
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.graphql.graphQLExecutionInput
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecuteSelectionSetOptions
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.TemporaryBypassAccessCheck
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.instrumentation.ViaductModernInstrumentation
import viaduct.engine.runtime.CheckerDispatcher
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.instrumentation.ChainedViaductModernInstrumentation
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.FlagManager

object ExecutionTestHelpers {
    suspend fun executeViaductModernGraphQL(
        sdl: String,
        resolvers: Map<String, Map<String, DataFetcher<*>>>,
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        typeResolvers: Map<String, TypeResolver> = emptyMap(),
        fieldCheckerDispatchers: Map<Coordinate, CheckerDispatcher> = emptyMap(),
        typeCheckerDispatchers: Map<String, CheckerDispatcher> = emptyMap(),
        instrumentations: List<ViaductModernInstrumentation> = emptyList(),
        flagManager: FlagManager = FlagManager.default,
        dispatcherRegistry: DispatcherRegistry? = null,
        requiredSelectionSetRegistry: RequiredSelectionSetRegistry = RequiredSelectionSetRegistry.Empty
    ): ExecutionResult {
        val schema = createSchema(sdl, resolvers, typeResolvers)
        val baseDispatcherRegistry = dispatcherRegistry ?: DispatcherRegistry.Impl(
            fieldResolverDispatchers = emptyMap(),
            nodeResolverDispatchers = emptyMap(),
            fieldCheckerDispatchers = fieldCheckerDispatchers,
            typeCheckerDispatchers = typeCheckerDispatchers
        )
        // Wrap the dispatcher registry to delegate RSS calls to the provided registry
        val effectiveDispatcherRegistry = if (requiredSelectionSetRegistry != RequiredSelectionSetRegistry.Empty) {
            TestDispatcherRegistryWithRSS(baseDispatcherRegistry, requiredSelectionSetRegistry)
        } else {
            baseDispatcherRegistry
        }
        val modernGraphQL = createViaductGraphQL(
            schema,
            instrumentations = instrumentations,
            flagManager = flagManager
        )
        return executeQuery(schema, modernGraphQL, query, variables, effectiveDispatcherRegistry)
    }

    /**
     * A test helper class that implements [DispatcherRegistry] by delegating dispatcher lookups
     * to a base registry while allowing [RequiredSelectionSetRegistry] calls to be overridden.
     * This allows tests to use MockRequiredSelectionSetRegistry without requiring inheritance.
     */
    private class TestDispatcherRegistryWithRSS(
        private val delegate: DispatcherRegistry,
        private val rssDelegate: RequiredSelectionSetRegistry
    ) : DispatcherRegistry by delegate {
        override fun getFieldResolverRequiredSelectionSets(
            typeName: String,
            fieldName: String
        ): List<RequiredSelectionSet> {
            val delegateResult = rssDelegate.getFieldResolverRequiredSelectionSets(typeName, fieldName)
            return delegateResult.ifEmpty { delegate.getFieldResolverRequiredSelectionSets(typeName, fieldName) }
        }

        override fun getFieldCheckerRequiredSelectionSets(
            typeName: String,
            fieldName: String,
            executeAccessChecksInModstrat: Boolean
        ): List<RequiredSelectionSet> {
            val delegateResult = rssDelegate.getFieldCheckerRequiredSelectionSets(typeName, fieldName, executeAccessChecksInModstrat)
            return delegateResult.ifEmpty { delegate.getFieldCheckerRequiredSelectionSets(typeName, fieldName, executeAccessChecksInModstrat) }
        }

        override fun getTypeCheckerRequiredSelectionSets(
            typeName: String,
            executeAccessChecksInModstrat: Boolean
        ): List<RequiredSelectionSet> {
            val delegateResult = rssDelegate.getTypeCheckerRequiredSelectionSets(typeName, executeAccessChecksInModstrat)
            return delegateResult.ifEmpty { delegate.getTypeCheckerRequiredSelectionSets(typeName, executeAccessChecksInModstrat) }
        }
    }

    fun createSchema(
        sdl: String,
        resolvers: Map<String, Map<String, DataFetcher<*>>>,
        typeResolvers: Map<String, TypeResolver> = emptyMap()
    ): ViaductSchema = createSchema(sdl, createRuntimeWiring(resolvers, typeResolvers))

    fun createSchema(
        sdl: String,
        runtimeWiring: RuntimeWiring
    ): ViaductSchema {
        val typeDefinitionRegistry = SchemaParser().parse(sdl)
        return ViaductSchema(SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring))
    }

    val supportedScalars = listOf(
        ExtendedScalars.Date,
        ExtendedScalars.Time,
        ExtendedScalars.Json,
        ExtendedScalars.GraphQLShort,
        ExtendedScalars.GraphQLByte
    )

    fun createRuntimeWiring(
        resolvers: Map<String, Map<String, DataFetcher<*>>>,
        typeResolvers: Map<String, TypeResolver>
    ): RuntimeWiring {
        return RuntimeWiring.newRuntimeWiring().apply {
            resolvers.forEach { (typeName, fieldResolvers) ->
                type(typeName) { builder ->
                    fieldResolvers.forEach { (fieldName, dataFetcher) ->
                        builder.dataFetcher(fieldName, dataFetcher)
                    }
                    builder
                }
            }
            typeResolvers.forEach { (typeName, typeResolver) ->
                type(typeName) { builder ->
                    builder.typeResolver(typeResolver)
                }
            }
            supportedScalars.forEach(::scalar)
        }.build()
    }

    fun createViaductGraphQL(
        schema: ViaductSchema,
        preparsedDocumentProvider: PreparsedDocumentProvider = DocumentCache(),
        instrumentations: List<ViaductModernInstrumentation> = emptyList(),
        gjInstrumentations: List<Instrumentation> = emptyList(),
        coroutineInterop: CoroutineInterop = DefaultCoroutineInterop,
        flagManager: FlagManager = FlagManager.default
    ): GraphQL {
        val execParamFactory = ExecutionParameters.Factory(
            flagManager
        )
        val accessCheckRunner = AccessCheckRunner(coroutineInterop)

        @Suppress("DEPRECATION")
        val executionStrategyFactory = ViaductExecutionStrategy.Factory.Impl(
            dataFetcherExceptionHandler = ExceptionHandlerWithFuture(),
            executionParametersFactory = execParamFactory,
            accessCheckRunner = accessCheckRunner,
            coroutineInterop = coroutineInterop,
            temporaryBypassAccessCheck = TemporaryBypassAccessCheck.Default
        )
        return GraphQL.newGraphQL(schema.schema)
            .preparsedDocumentProvider(preparsedDocumentProvider)
            .queryExecutionStrategy(
                executionStrategyFactory.create(isSerial = false)
            )
            .mutationExecutionStrategy(
                executionStrategyFactory.create(isSerial = true)
            )
            .subscriptionExecutionStrategy(
                executionStrategyFactory.create(isSerial = false)
            )
            .instrumentation(mkInstrumentation(instrumentations, gjInstrumentations))
            .build()
    }

    private fun mkInstrumentation(
        viaductModernInstrumentations: List<ViaductModernInstrumentation> = emptyList(),
        gjInstrumentations: List<Instrumentation> = emptyList()
    ): Instrumentation =
        // The different instrumentation interfaces are not compatible, particularly when multiple instances of
        // one flavor are merged into a chained representation which has to coexist with any instrumentations
        // of the other flavor.
        // As a cheap workaround to allow providing either form of interface to these fixtures, require that
        // only one flavor is provided
        if (viaductModernInstrumentations.isNotEmpty()) {
            require(gjInstrumentations.isEmpty()) {
                "Cannot combine viaductModernInstrumentations with gjInstrumentations"
            }
            ChainedViaductModernInstrumentation(viaductModernInstrumentations)
        } else if (gjInstrumentations.isNotEmpty()) {
            require(viaductModernInstrumentations.isEmpty()) {
                "Cannot combine viaductModernInstrumentations with gjInstrumentations"
            }
            ChainedInstrumentation(gjInstrumentations)
        } else {
            SimplePerformantInstrumentation.INSTANCE
        }

    fun createGJGraphQL(
        schema: ViaductSchema,
        preparsedDocumentProvider: PreparsedDocumentProvider = DocumentCache(),
        instrumentations: List<Instrumentation> = emptyList()
    ): GraphQL {
        return GraphQL.newGraphQL(schema.schema)
            .preparsedDocumentProvider(preparsedDocumentProvider)
            .instrumentation(ChainedInstrumentation(instrumentations))
            .queryExecutionStrategy(AsyncExecutionStrategy(ExceptionHandlerWithFuture()))
            .mutationExecutionStrategy(AsyncSerialExecutionStrategy(ExceptionHandlerWithFuture()))
            .subscriptionExecutionStrategy(AsyncSerialExecutionStrategy(ExceptionHandlerWithFuture()))
            .build()
    }

    suspend fun executeQuery(
        schema: ViaductSchema,
        graphQL: GraphQL,
        query: String,
        variables: Map<String, Any?>,
        dispatcherRegistry: DispatcherRegistry = DispatcherRegistry.Empty
    ): ExecutionResult {
        // clear query plan cache
        QueryPlan.resetCache()
        val executionInput = createExecutionInput(schema, query, variables, dispatcherRegistry = dispatcherRegistry)
        return graphQL.executeAsync(executionInput).await()
    }

    fun createExecutionInput(
        schema: ViaductSchema,
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        operationName: String? = null,
        context: GraphQLContext = GraphQLContext.getDefault(),
        dispatcherRegistry: DispatcherRegistry = DispatcherRegistry.Empty
    ): ExecutionInput =
        ExecutionInput.newExecutionInput()
            .query(query)
            .operationName(operationName)
            .variables(variables)
            .localContext(createLocalContext(schema, dispatcherRegistry))
            .graphQLContext { b ->
                // executing large queries can trigger GJ's ddos prevention
                // Configure ParserOptions to use the sdl configuration, which has
                // no size limits on what it will parse
                // Add this first so that it can be overridden by the context argument
                b.put(ParserOptions::class.java, ParserOptions.getDefaultSdlParserOptions())

                context.stream().forEach { (k, v) -> b.put(k, v) }
            }
            .build()

    fun createLocalContext(
        schema: ViaductSchema,
        dispatcherRegistry: DispatcherRegistry = DispatcherRegistry.Empty
    ): CompositeLocalContext =
        ContextMocks(
            myFullSchema = schema,
            myFlagManager = FlagManager.default,
            myDispatcherRegistry = dispatcherRegistry
        ).localContext

    fun <T> runExecutionTest(block: suspend CoroutineScope.() -> T): T =
        runBlocking {
            withThreadLocalCoroutineContext {
                block()
            }
        }

    private class ExceptionHandlerWithFuture : DataFetcherExceptionHandler {
        @OptIn(DelicateCoroutinesApi::class)
        override fun handleException(handlerParameters: DataFetcherExceptionHandlerParameters?): CompletableFuture<DataFetcherExceptionHandlerResult?>? {
            return scopedFuture {
                SimpleDataFetcherExceptionHandler().handleException(handlerParameters).await()
            }
        }
    }
}

/** methods for generating a [TypeResolver] */
object TypeResolvers {
    /** create a [TypeResolver] that always resolves to the provided type */
    fun const(name: String): TypeResolver = TypeResolver { it.schema.getObjectType(name) }

    /** a [TypeResolver] that will use a `__typename` entry in the current object data to resolve a type */
    val typename: TypeResolver = TypeResolver { env ->
        val data = env.getObject() as Map<String, Any?>
        val typename = data["__typename"]!! as String
        env.schema.getObjectType(typename)
    }
}

object DataFetchers {
    /** a DataFetcher that always returns an empty Map of `String` to `Any?` */
    val emptyMap: DataFetcher<Any?> = DataFetcher { emptyMap<String, Any?>() }
}

/** generate an [Arb] of [ExecutionInput] that is configured for running on viaduct */
fun Arb.Companion.viaductExecutionInput(
    schema: ViaductSchema,
    cfg: Config = Config.default,
): Arb<ExecutionInput> = Arb.graphQLExecutionInput(schema.schema, cfg).asViaductExecutionInput(schema)

fun Arb<ExecutionInput>.asViaductExecutionInput(schema: ViaductSchema): Arb<ExecutionInput> =
    map { input ->
        input.transform {
            it.localContext(ExecutionTestHelpers.createLocalContext(schema))
            it.graphQLContext(
                mapOf(
                    // to enable testing very large queries, use the "sdl" parser options, which
                    // supports parsing large inputs
                    ParserOptions::class.java to ParserOptions.getDefaultSdlParserOptions()
                )
            )
        }
    }

fun ExecutionInput.dump(): String =
    """
       |OperationName: $operationName
       |Variables: $variables
       |Document:
       |$query
    """.trimMargin()

// Sharing a document cache reduces arbitrary conformance test time by about 20%
class DocumentCache : PreparsedDocumentProvider {
    private val cache = Caffeine
        .newBuilder()
        .maximumSize(10)
        .build<String, PreparsedDocumentEntry>()

    override fun getDocumentAsync(
        executionInput: ExecutionInput,
        parseAndValidateFunction: Function<ExecutionInput, PreparsedDocumentEntry>
    ): CompletableFuture<PreparsedDocumentEntry?>? =
        CompletableFuture.completedFuture(
            cache.get(executionInput.query) {
                parseAndValidateFunction.apply(executionInput)
            }
        )
}

object CheckerDispatchers {
    fun success(requiredSelectionSets: Map<String, RequiredSelectionSet?> = emptyMap()): CheckerDispatcher {
        val dispatcher = object : CheckerDispatcher {
            override val requiredSelectionSets = requiredSelectionSets
            override lateinit var executor: CheckerExecutor

            override suspend fun execute(
                arguments: Map<String, Any?>,
                objectDataMap: Map<String, EngineObjectData>,
                context: EngineExecutionContext,
                checkerType: viaduct.engine.api.CheckerExecutor.CheckerType
            ): CheckerResult = CheckerResult.Success
        }
        dispatcher.executor = object : CheckerExecutor {
            override suspend fun execute(
                arguments: Map<String, Any?>,
                objectDataMap: Map<String, EngineObjectData>,
                context: EngineExecutionContext,
                checkerType: CheckerExecutor.CheckerType
            ): CheckerResult = CheckerResult.Success

            override val checkerMetadata = null
            override val requiredSelectionSets = dispatcher.requiredSelectionSets
        }
        return dispatcher
    }
}

/**
 * Test-only extension to execute a Query selection set.
 *
 * This is a convenience wrapper for tests that previously used the deprecated
 * `EngineExecutionContext.query()` method. For production code, use
 * [EngineExecutionContext.executeSelectionSet] directly.
 */
suspend fun EngineExecutionContext.query(
    resolverId: String,
    selectionSet: RawSelectionSet
): EngineObjectData =
    executeSelectionSet(
        resolverId,
        selectionSet,
        ExecuteSelectionSetOptions.DEFAULT
    )

/**
 * Test-only extension to execute a Mutation selection set.
 *
 * This is a convenience wrapper for tests that previously used the deprecated
 * `EngineExecutionContext.mutation()` method. For production code, use
 * [EngineExecutionContext.executeSelectionSet] directly.
 */
suspend fun EngineExecutionContext.mutation(
    resolverId: String,
    selectionSet: RawSelectionSet
): EngineObjectData =
    executeSelectionSet(
        resolverId,
        selectionSet,
        ExecuteSelectionSetOptions.MUTATION
    )
