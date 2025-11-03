@file:OptIn(ExperimentalCoroutinesApi::class)
@file:Suppress("ForbiddenImport")

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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.graphql.graphQLExecutionInput
import viaduct.engine.api.FieldCheckerDispatcherRegistry
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.TypeCheckerDispatcherRegistry
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.instrumentation.ViaductModernInstrumentation
import viaduct.engine.runtime.CompositeLocalContext
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
        requiredSelectionSetRegistry: RequiredSelectionSetRegistry = RequiredSelectionSetRegistry.Empty,
        instrumentations: List<ViaductModernInstrumentation> = emptyList(),
        flagManager: FlagManager = FlagManager.default
    ): ExecutionResult {
        val schema = createSchema(sdl, resolvers, typeResolvers)
        val modernGraphQL = createViaductGraphQL(
            schema,
            requiredSelectionSetRegistry,
            instrumentations = instrumentations,
            flagManager = flagManager
        )
        return executeQuery(schema, modernGraphQL, query, variables)
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
        requiredSelectionSetRegistry: RequiredSelectionSetRegistry = RequiredSelectionSetRegistry.Empty,
        preparsedDocumentProvider: PreparsedDocumentProvider = DocumentCache(),
        instrumentations: List<ViaductModernInstrumentation> = emptyList(),
        gjInstrumentations: List<Instrumentation> = emptyList(),
        fieldCheckerDispatcherRegistry: FieldCheckerDispatcherRegistry = FieldCheckerDispatcherRegistry.Empty,
        typeCheckerDispatcherRegistry: TypeCheckerDispatcherRegistry = TypeCheckerDispatcherRegistry.Empty,
        coroutineInterop: CoroutineInterop = DefaultCoroutineInterop,
        flagManager: FlagManager = FlagManager.default
    ): GraphQL {
        val execParamFactory = ExecutionParameters.Factory(
            requiredSelectionSetRegistry,
            fieldCheckerDispatcherRegistry,
            typeCheckerDispatcherRegistry,
            flagManager
        )
        val accessCheckRunner = AccessCheckRunner(coroutineInterop)
        @Suppress("DEPRECATION")
        val executionStrategyFactory = ViaductExecutionStrategy.Factory.Impl(
            dataFetcherExceptionHandler = ExceptionHandlerWithFuture(),
            executionParametersFactory = execParamFactory,
            accessCheckRunner = accessCheckRunner,
            coroutineInterop = coroutineInterop,
            temporaryBypassAccessCheck = viaduct.engine.api.TemporaryBypassAccessCheck.Default
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
        variables: Map<String, Any?>
    ): ExecutionResult {
        // clear query plan cache
        QueryPlan.Companion.resetCache()
        val executionInput = createExecutionInput(schema, query, variables)
        return graphQL.executeAsync(executionInput).await()
    }

    fun createExecutionInput(
        schema: ViaductSchema,
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        operationName: String? = null,
        context: GraphQLContext = GraphQLContext.getDefault()
    ): ExecutionInput =
        ExecutionInput.newExecutionInput()
            .query(query)
            .operationName(operationName)
            .variables(variables)
            .localContext(createLocalContext(schema))
            .graphQLContext { b ->
                // executing large queries can trigger GJ's ddos prevention
                // Configure ParserOptions to use the sdl configuration, which has
                // no size limits on what it will parse
                // Add this first so that it can be overridden by the context argument
                b.put(ParserOptions::class.java, ParserOptions.getDefaultSdlParserOptions())

                context.stream().forEach { (k, v) -> b.put(k, v) }
            }
            .build()

    fun createLocalContext(schema: ViaductSchema): CompositeLocalContext =
        ContextMocks(
            myFullSchema = schema,
            myFlagManager = FlagManager.default,
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
            return GlobalScope.future {
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
