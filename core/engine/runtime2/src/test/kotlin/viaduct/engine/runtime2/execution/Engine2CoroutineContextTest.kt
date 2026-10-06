@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.engine.runtime2.execution

import graphql.GraphQLError
import graphql.execution.preparsed.NoOpPreparsedDocumentProvider
import graphql.execution.preparsed.PreparsedDocumentProvider
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.execution.CoroutineContextContract
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.DocumentProviderFactory
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

class Engine2CoroutineContextTest : CoroutineContextContract() {
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
