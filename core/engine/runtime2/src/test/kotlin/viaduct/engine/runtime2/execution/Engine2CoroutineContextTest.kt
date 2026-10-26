@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.engine.runtime2.execution

import graphql.GraphQLError
import graphql.execution.preparsed.NoOpPreparsedDocumentProvider
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.execution.preparsed.PreparsedDocumentProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.mocks.MockNodeBatchResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createEngineSelectionSetFactory
import viaduct.engine.api.mocks.createSchema
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.FieldDataLoader
import viaduct.engine.runtime.NodeDataLoader
import viaduct.engine.runtime.ResolverDispatchStrategy
import viaduct.engine.runtime.execution.CoroutineContextContract
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.DocumentProviderFactory
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

class Engine2CoroutineContextTest : CoroutineContextContract() {
    @Test
    fun `Runtime2 invokes a batching-capable field resolver in the calling coroutine`() =
        runTest {
            val callerJob = currentCoroutineContext().job
            var resolverJob: Job? = null

            loadBatchingField(dispatchStrategy = Runtime2DispatchStrategy) {
                resolverJob = currentCoroutineContext().job
            }

            assertEquals(callerJob, resolverJob)
        }

    @Test
    fun `Runtime2 invokes a batching-capable node resolver in the calling coroutine`() =
        runTest {
            val callerJob = currentCoroutineContext().job
            var resolverJob: Job? = null

            loadBatchingNode(dispatchStrategy = Runtime2DispatchStrategy) {
                resolverJob = currentCoroutineContext().job
            }

            assertEquals(callerJob, resolverJob)
        }

    @Test
    fun `engine2 uses the configured coroutine interop to capture request context`() =
        runBlocking {
            val interop = object : CoroutineInterop by DefaultCoroutineInterop {
                override fun <T> scopedFuture(block: suspend CoroutineScope.() -> T): CompletableFuture<T> =
                    DefaultCoroutineInterop.scopedFuture {
                        withContext(CoroutineName("configured-interop"), block)
                    }
            }
            val viaduct = createViaduct(
                schemaSDL =
                    """
                    extend type Query { requestIdentity: String @resolver }
                    extend type Mutation { requestIdentity: String @resolver }
                    """.trimIndent(),
                resolveField = { requireNotNull(currentCoroutineContext()[CoroutineName]).name },
                documentProvider = NoOpPreparsedDocumentProvider(),
                coroutineInterop = interop,
            )
            for (operation in listOf("query", "mutation")) {
                val result = viaduct.execute(
                    ExecutionInput.create(operationText = "$operation { requestIdentity }"),
                    SchemaId.Base,
                )
                assertEquals(emptyList<GraphQLError>(), result.errors)
                assertEquals(mapOf("requestIdentity" to "configured-interop"), result.getData())
            }
        }

    @Test
    fun `engine2 inherits the configured service dispatcher across suspension`(): Unit =
        runBlocking {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val interop = object : CoroutineInterop by DefaultCoroutineInterop {
                    override fun <T> enterThreadLocalCoroutineContext(
                        callerContext: CoroutineContext,
                        block: suspend CoroutineScope.() -> T,
                    ): CompletableFuture<T> =
                        DefaultCoroutineInterop.enterThreadLocalCoroutineContext(callerContext) {
                            withContext(dispatcher, block)
                        }
                }
                val viaduct = createViaduct(
                    schemaSDL =
                        """
                        extend type Query { requestIdentity: String @resolver }
                        extend type Mutation { requestIdentity: String @resolver }
                        """.trimIndent(),
                    resolveField = {
                        assertSame(dispatcher, currentCoroutineContext()[ContinuationInterceptor])
                        yield()
                        assertSame(dispatcher, currentCoroutineContext()[ContinuationInterceptor])
                        "resolved"
                    },
                    documentProvider = NoOpPreparsedDocumentProvider(),
                    coroutineInterop = interop,
                )
                for (operation in listOf("query", "mutation")) {
                    val result = viaduct.execute(ExecutionInput.create("$operation { requestIdentity }"), SchemaId.Base)
                    assertEquals(emptyList<GraphQLError>(), result.errors)
                    assertEquals(mapOf("requestIdentity" to "resolved"), result.getData())
                }
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["query", "mutation"])
    fun `pending document loading preserves configured dispatcher and request context`(operation: String): Unit =
        runBlocking {
            withTimeout(30_000) {
                val executor = Executors.newSingleThreadExecutor()
                executor.asCoroutineDispatcher().use { dispatcher ->
                    val requestIdentity = ThreadLocal<String>()
                    val parsedDocument = CompletableFuture<PreparsedDocumentEntry>()
                    val pendingDocument = CompletableFuture<PreparsedDocumentEntry>()
                    val interop = object : CoroutineInterop by DefaultCoroutineInterop {
                        override fun <T> enterThreadLocalCoroutineContext(
                            callerContext: CoroutineContext,
                            block: suspend CoroutineScope.() -> T,
                        ): CompletableFuture<T> =
                            DefaultCoroutineInterop.enterThreadLocalCoroutineContext(callerContext) {
                                withContext(dispatcher, block)
                            }
                    }
                    val viaduct = createViaduct(
                        schemaSDL =
                            """
                            extend type Query { requestIdentity: String @resolver }
                            extend type Mutation { requestIdentity: String @resolver }
                            """.trimIndent(),
                        resolveField = {
                            assertSame(dispatcher, currentCoroutineContext()[ContinuationInterceptor])
                            assertEquals("request-context", requestIdentity.get())
                            assertEquals("async-document-request", currentCoroutineContext()[CoroutineName]?.name)
                            yield()
                            assertSame(dispatcher, currentCoroutineContext()[ContinuationInterceptor])
                            assertEquals("request-context", requestIdentity.get())
                            assertEquals("async-document-request", currentCoroutineContext()[CoroutineName]?.name)
                            requestIdentity.get()
                        },
                        documentProvider = PreparsedDocumentProvider { input, parseAndValidate ->
                            assertEquals("request-context", requestIdentity.get())
                            parsedDocument.complete(parseAndValidate.apply(input))
                            pendingDocument
                        },
                        coroutineInterop = interop,
                    )
                    val execution = async(CoroutineName("async-document-request") + requestIdentity.asContextElement("request-context")) {
                        viaduct.execute(ExecutionInput.create("$operation { requestIdentity }"), SchemaId.Base)
                    }
                    val document = parsedDocument.await()
                    // The sentinel runs after GraphQL registers its continuation and the request suspends.
                    val requestSuspended = CompletableFuture<Unit>()
                    executor.execute { requestSuspended.complete(Unit) }
                    requestSuspended.await()
                    assertNull(requestIdentity.get())
                    pendingDocument.complete(document)

                    val result = execution.await()
                    assertEquals(emptyList<GraphQLError>(), result.errors)
                    assertEquals(mapOf("requestIdentity" to "request-context"), result.getData())
                    assertNull(requestIdentity.get())
                }
            }
        }

    override fun createViaduct(
        schemaSDL: String,
        resolveField: suspend () -> String,
        documentProvider: PreparsedDocumentProvider,
    ): Viaduct = createViaduct(schemaSDL, resolveField, documentProvider, DefaultCoroutineInterop)

    private suspend fun loadBatchingField(
        dispatchStrategy: ResolverDispatchStrategy,
        resolve: suspend () -> Unit,
    ) {
        val resolver = MockFieldBatchResolverExecutor(resolverId = "Query.value") { selectors, _ ->
            resolve()
            selectors.associateWith { Result.success(null) }
        }
        val selector = FieldResolverExecutor.Selector(
            arguments = emptyMap(),
            selections = null,
            syncObjectValueGetter = { error("No object input required") },
            syncQueryValueGetter = { error("No Query input required") },
        )
        FieldDataLoader(resolver, dispatchStrategy = dispatchStrategy)
            .loadByKey(selector, ContextMocks().engineExecutionContext).getOrThrow()
    }

    private suspend fun loadBatchingNode(
        dispatchStrategy: ResolverDispatchStrategy,
        resolve: suspend () -> Unit,
    ) {
        val schema = createSchema("type User { id: ID }")
        val node = createEngineObjectData(schema.schema.getObjectType("User"), mapOf("id" to "user-1"))
        val resolver = MockNodeBatchResolverExecutor(typeName = "User") { selectors, _ ->
            resolve()
            selectors.associateWith { Result.success(node) }
        }
        val selections = createEngineSelectionSetFactory(schema).engineSelectionSet("User", "id", emptyMap())
        NodeDataLoader(resolver, dispatchStrategy = dispatchStrategy)
            .loadByKey(NodeResolverExecutor.Selector("user-1", selections), ContextMocks().engineExecutionContext).getOrThrow()
    }

    private fun createViaduct(
        schemaSDL: String,
        resolveField: suspend () -> String,
        documentProvider: PreparsedDocumentProvider,
        coroutineInterop: CoroutineInterop,
    ): Viaduct {
        val module = EngineTestModule(schemaSDL) {
            for (type in listOf("Query", "Mutation")) {
                field(type to "requestIdentity") {
                    resolver { fn { _, _, _, _, _ -> resolveField() } }
                }
            }
        }
        return StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(module.mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(module.toModuleConfigSource()))
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(schemaSDL))
            .withDocumentProviderFactory(DocumentProviderFactory { _, _ -> documentProvider })
            .withCoroutineInterop(coroutineInterop)
            .withFlagManager(
                object : FlagManager {
                    override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
                },
            ).build()
    }
}
