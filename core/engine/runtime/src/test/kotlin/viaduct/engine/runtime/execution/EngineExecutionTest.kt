package viaduct.engine.runtime.execution

import graphql.ExecutionInput as GJExecutionInput
import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.schema.DataFetcher
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.IncrementalEntry
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.context.DispatcherLocalContext
import viaduct.engine.runtime.context.updateCompositeLocalContext
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createSchema
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

@Timeout(10)
class EngineExecutionTest {
    @Test
    fun `wrapper supports graphql-java requests without a viaduct engine context`() {
        runExecutionTest {
            val schema = createSchema(
                "type Query { value: Int }",
                mapOf("Query" to mapOf("value" to DataFetcher { 42 })),
            )
            val graphql = GraphQL.newGraphQL(schema.schema)
                .queryExecutionStrategy(WrappedCoroutineExecutionStrategy(AsyncExecutionStrategy(), DefaultCoroutineInterop))
                .build()

            for (localContext in listOf(null, "fixture-context", CompositeLocalContext.empty)) {
                val input = GJExecutionInput.newExecutionInput()
                    .query("{ value }")
                    .localContext(localContext)
                    .build()
                val result = withTimeout(2_000) { graphql.executeAsync(input).await() }
                assertEquals(mapOf("value" to 42), result.getData<Map<String, Any?>>())
                assertTrue(result.errors.isEmpty())
            }
        }
    }

    @Test
    fun `execute returns an incremental result before deferred work completes`() {
        val release = CompletableDeferred<Unit>()
        EngineTestModule("extend type Query { value: Int }") {
            field("Query" to "value") {
                resolver {
                    fn { _, _, _, _, _ ->
                        release.await()
                        42
                    }
                }
            }
        }.runFeatureTest(engineConfig = incrementalConfig) {
            runExecutionTest {
                try {
                    val result = assertInstanceOf(
                        IncrementalExecutionResult::class.java,
                        withTimeout(2_000) {
                            engine.execute(ExecutionInput("{ __typename ... @defer { value } }", requestContext = Any()))
                        },
                    )
                    try {
                        assertEquals(mapOf("__typename" to "Query"), result.getData<Map<String, Any?>>())
                        assertTrue(result.errors.isEmpty())
                        assertTrue(result.hasNext)
                        assertEquals("0", result.pending.single().id)
                        assertFalse(release.isCompleted)
                        release.complete(Unit)
                        val update = result.updates.toList().last()
                        assertEquals(mapOf("value" to 42), (update.incremental.single() as IncrementalEntry.Object).data)
                        assertEquals("0", update.completed.single().id)
                        assertFalse(update.hasNext)
                    } finally {
                        result.cancel()
                    }
                } finally {
                    release.complete(Unit)
                }
            }
        }
    }

    @Test
    fun `caller can cancel deferred work without collecting updates`() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        EngineTestModule("extend type Query { value: Int }") {
            field("Query" to "value") {
                resolver {
                    fn { _, _, _, _, _ ->
                        started.complete(Unit)
                        try {
                            release.await()
                            42
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                }
            }
        }.runFeatureTest(engineConfig = incrementalConfig) {
            runExecutionTest {
                try {
                    val result = assertInstanceOf(
                        IncrementalExecutionResult::class.java,
                        withTimeout(2_000) {
                            engine.execute(ExecutionInput("{ ... @defer { value } }", requestContext = Any()))
                        },
                    )
                    try {
                        withTimeout(2_000) { started.await() }
                        result.cancel()
                        withTimeout(2_000) { cancelled.await() }
                        assertFalse(release.isCompleted)
                    } finally {
                        result.cancel()
                    }
                } finally {
                    release.complete(Unit)
                }
            }
        }
    }

    @Test
    fun `disabled incremental execution waits for deferred fields and returns a complete result`() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        EngineTestModule("extend type Query { value: Int }") {
            field("Query" to "value") {
                resolver {
                    fn { _, _, _, _, _ ->
                        started.complete(Unit)
                        release.await()
                        42
                    }
                }
            }
        }.runFeatureTest(engineConfig = EngineConfiguration.featureTestDefault.copy(flagManager = MockFlagManager.Disabled)) {
            runExecutionTest {
                try {
                    val request = async {
                        engine.execute(ExecutionInput("{ __typename ... @defer { value } }", requestContext = Any()))
                    }
                    withTimeout(2_000) { started.await() }
                    assertFalse(request.isCompleted)
                    release.complete(Unit)
                    val result = withTimeout(2_000) { request.await() }
                    assertFalse(result is IncrementalExecutionResult)
                    assertEquals(mapOf("__typename" to "Query", "value" to 42), result.getData<Map<String, Any?>>())
                    assertTrue(result.errors.isEmpty())
                } finally {
                    release.complete(Unit)
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["{ value }", "{ ... @defer(if: false) { value } }"])
    fun `requests without active deferred selections return a complete result`(query: String) {
        EngineTestModule("extend type Query { value: Int }") {
            fieldWithValue("Query" to "value", 42)
        }.runFeatureTest(engineConfig = incrementalConfig) {
            runExecutionTest {
                val result = withTimeout(2_000) { engine.execute(ExecutionInput(query, requestContext = Any())) }
                assertFalse(result is IncrementalExecutionResult)
                assertEquals(mapOf("value" to 42), result.getData<Map<String, Any?>>())
                assertTrue(result.errors.isEmpty())
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `initial and deferred resolvers use the configured dispatcher`(incremental: Boolean) {
        Executors.newSingleThreadExecutor { Thread(it, "engine-dispatcher-test") }.asCoroutineDispatcher().use { dispatcher ->
            val threads = ConcurrentLinkedQueue<Thread>()
            val instrumentation = object : SimplePerformantInstrumentation() {
                override fun instrumentExecutionInput(
                    executionInput: GJExecutionInput,
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?,
                ): GJExecutionInput =
                    executionInput.transform { builder ->
                        builder.localContext(executionInput.updateCompositeLocalContext<DispatcherLocalContext> { DispatcherLocalContext(dispatcher) })
                    }
            }
            val config = EngineConfiguration.featureTestDefault.copy(
                flagManager = if (incremental) incrementalConfig.flagManager else MockFlagManager.Disabled,
                additionalInstrumentation = instrumentation,
            )
            EngineTestModule("extend type Query { initial: Int, later: Int }") {
                for (fieldName in listOf("initial", "later")) {
                    field("Query" to fieldName) {
                        resolver {
                            fn { _, _, _, _, _ ->
                                threads.add(Thread.currentThread())
                                42
                            }
                        }
                    }
                }
            }.runFeatureTest(engineConfig = config) {
                runExecutionTest {
                    val executionThread = withContext(dispatcher) { Thread.currentThread() }
                    val result = withTimeout(2_000) {
                        engine.execute(ExecutionInput("{ initial ... @defer { later } }", requestContext = Any()))
                    }
                    assertTrue(result.errors.isEmpty())
                    if (incremental) {
                        val initial = assertInstanceOf(IncrementalExecutionResult::class.java, result)
                        try {
                            assertEquals(mapOf("initial" to 42), initial.getData<Map<String, Any?>>())
                            val updates = initial.updates.toList()
                            val patch = updates.flatMap { it.incremental }.single() as IncrementalEntry.Object
                            assertEquals(mapOf("later" to 42), patch.data)
                            assertTrue(patch.errors.isEmpty())
                            assertFalse(updates.last().hasNext)
                        } finally {
                            initial.cancel()
                        }
                    } else {
                        assertFalse(result is IncrementalExecutionResult)
                        assertEquals(mapOf("initial" to 42, "later" to 42), result.getData<Map<String, Any?>>())
                    }
                    assertEquals(2, threads.size)
                    threads.forEach { assertSame(executionThread, it) }
                }
            }
        }
    }

    private val incrementalConfig = EngineConfiguration.featureTestDefault.copy(
        flagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION),
    )
}
