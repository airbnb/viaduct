@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.engine.runtime2.execution

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.engine.api.Engine
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

class Engine2RequestLifetimeTest {
    @Test
    fun `caller cancellation reaches nested mutations without cancelling another request`() =
        runBlocking {
            val viaduct = requestLifetimeViaduct()
            val cancelled = RequestControl("cancelled", ResolveSelectionSetOptions.MUTATION)
            val survivor = RequestControl("survivor", ResolveSelectionSetOptions.MUTATION)
            val execution = async { viaduct.execute(input(cancelled), SchemaId.Base) }
            val survivingExecution = async { viaduct.execute(input(survivor), SchemaId.Base) }
            try {
                withTimeout(TIMEOUT_MILLIS) {
                    cancelled.nestedStarted.await()
                    survivor.nestedStarted.await()
                }
                execution.cancel(CancellationException("caller cancelled nested mutation"))
                withTimeout(TIMEOUT_MILLIS) {
                    cancelled.nestedCancelled.await()
                    cancelled.outerCancelled.await()
                    execution.join()
                }
                assertFalse(survivor.nestedCancelled.isCompleted)
                assertFalse(survivingExecution.isCompleted)
                survivor.allowNestedResult.complete(Unit)
                val result = withTimeout(TIMEOUT_MILLIS) { survivingExecution.await() }
                assertEquals(emptyList(), result.errors)
                assertEquals(mapOf("outer" to "survivor"), result.getData())
                assertTrue(execution.isCancelled)
            } finally {
                execution.cancel()
                survivingExecution.cancel()
            }
        }

    @Test
    fun `caller cancellation interrupts mutations while response completion waits`() =
        runBlocking {
            val viaduct = requestLifetimeViaduct()
            val control = RequestControl("mutation")
            val execution = async {
                viaduct.execute(
                    ExecutionInput.create(operationText = "mutation { failed later }", requestContext = control),
                    SchemaId.Base,
                )
            }
            try {
                withTimeout(TIMEOUT_MILLIS) { control.nestedStarted.await() }
                assertFalse(execution.isCompleted)
                execution.cancel(CancellationException("caller cancelled"))
                withTimeout(TIMEOUT_MILLIS) {
                    control.nestedCancelled.await()
                    execution.join()
                }
                assertTrue(execution.isCancelled)
                assertFalse(control.allowNestedResult.isCompleted)
            } finally {
                execution.cancel()
            }
        }

    @Test
    fun `executeAsync cancellation reaches nested resolution and is isolated by request`() =
        runBlocking {
            val viaduct = requestLifetimeViaduct()
            val cancelled = RequestControl("cancelled")
            val survivor = RequestControl("survivor")
            val executor = Executors.newFixedThreadPool(2)
            try {
                val cancelledFuture = viaduct.executeAsync(input(cancelled), SchemaId.Base, executor)
                val survivorFuture = viaduct.executeAsync(input(survivor), SchemaId.Base, executor)
                withTimeout(TIMEOUT_MILLIS) {
                    cancelled.nestedStarted.await()
                    survivor.nestedStarted.await()
                }

                assertTrue(cancelledFuture.cancel(true))
                withTimeout(TIMEOUT_MILLIS) {
                    cancelled.nestedCancelled.await()
                    cancelled.outerCancelled.await()
                }

                assertFalse(survivor.nestedCancelled.isCompleted)
                assertFalse(survivorFuture.isDone)
                survivor.allowNestedResult.complete(Unit)

                val result = survivorFuture.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                assertEquals(emptyList(), result.errors)
                assertEquals(mapOf("outer" to "survivor"), result.getData())
                assertTrue(cancelledFuture.isCancelled)
                assertFalse(cancelled.allowNestedResult.isCompleted)
            } finally {
                executor.shutdownNow()
            }
        }

    @Test
    fun `suspending execute cancellation reaches nested resolution`() =
        runBlocking {
            val viaduct = requestLifetimeViaduct()
            val cancelled = RequestControl("cancelled")
            val execution = async { viaduct.execute(input(cancelled), SchemaId.Base) }
            withTimeout(TIMEOUT_MILLIS) { cancelled.nestedStarted.await() }

            execution.cancel(CancellationException("caller cancelled"))
            withTimeout(TIMEOUT_MILLIS) {
                cancelled.nestedCancelled.await()
                cancelled.outerCancelled.await()
                execution.join()
            }

            assertTrue(execution.isCancelled)
            assertFalse(cancelled.allowNestedResult.isCompleted)
        }

    private fun requestLifetimeViaduct(): Viaduct {
        val suppliedModule =
            EngineTestModule(SCHEMA) {
                field("Mutation" to "failed") {
                    resolver { fn { _, _, _, _, _ -> error("mutation failed") } }
                }
                field("Mutation" to "later") {
                    resolver {
                        fn { _, _, _, _, context ->
                            val control = context.requestContext as RequestControl
                            control.nestedStarted.complete(Unit)
                            try {
                                control.allowNestedResult.await()
                                control.value
                            } catch (cancellation: CancellationException) {
                                control.nestedCancelled.complete(Unit)
                                throw cancellation
                            }
                        }
                    }
                }
                field("Query" to "outer") {
                    resolver {
                        fn { _, _, _, _, context ->
                            val control = context.requestContext as RequestControl
                            val (type, field) = when (control.nestedOptions.operationType) {
                                Engine.OperationType.QUERY -> "Query" to "nested"
                                Engine.OperationType.MUTATION -> "Mutation" to "later"
                            }
                            val selections =
                                context.engineSelectionSetFactory.engineSelectionSet(
                                    type,
                                    field,
                                    emptyMap(),
                                )
                            try {
                                val result =
                                    context.engine.resolveSelectionSet(
                                        requireNotNull(context.executionHandle),
                                        selections,
                                        control.nestedOptions,
                                    )
                                result.get(field)
                            } catch (cancellation: CancellationException) {
                                control.outerCancelled.complete(Unit)
                                throw cancellation
                            }
                        }
                    }
                }
                field("Query" to "nested") {
                    resolver {
                        fn { _, _, _, _, context ->
                            val control = context.requestContext as RequestControl
                            control.nestedStarted.complete(Unit)
                            try {
                                control.allowNestedResult.await()
                                control.value
                            } catch (cancellation: CancellationException) {
                                control.nestedCancelled.complete(Unit)
                                throw cancellation
                            }
                        }
                    }
                }
            }
        return StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(
                MockExecutorCodeInjector(suppliedModule.mockExecutorRegistry),
            ).withExecutorRegistryConfigSources(listOf(suppliedModule.toModuleConfigSource()))
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(SCHEMA))
            .withFlagManager(
                object : FlagManager {
                    override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
                },
            ).build()
    }

    private fun input(control: RequestControl): ExecutionInput =
        ExecutionInput.create(
            operationText = "{ outer }",
            requestContext = control,
        )

    private class RequestControl(
        val value: String,
        val nestedOptions: ResolveSelectionSetOptions = ResolveSelectionSetOptions.DEFAULT,
    ) {
        val nestedStarted = CompletableDeferred<Unit>()
        val allowNestedResult = CompletableDeferred<Unit>()
        val nestedCancelled = CompletableDeferred<Unit>()
        val outerCancelled = CompletableDeferred<Unit>()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        val SCHEMA =
            """
            extend type Query {
                outer: String @resolver
                nested: String @resolver
            }
            extend type Mutation {
                failed: Int! @resolver
                later: String! @resolver
            }
            """.trimIndent()
    }
}
