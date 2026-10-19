@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.engine.runtime2.execution

import graphql.GraphQLError
import graphql.execution.preparsed.NoOpPreparsedDocumentProvider
import graphql.execution.preparsed.PreparsedDocumentProvider
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
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
