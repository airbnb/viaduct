package viaduct.engine.runtime.execution

import graphql.ExecutionInput as GJExecutionInput
import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.schema.DataFetcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.IncrementalEntry
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.context.CompositeLocalContext
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
    fun `non-null failure delivers initial data without waiting for a pending sibling`() {
        val requiredFetched = CompletableDeferred<Unit>()
        val slowStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val instrumentation = object : SimplePerformantInstrumentation() {
            override fun beginFieldExecution(
                parameters: InstrumentationFieldParameters,
                state: InstrumentationState?,
            ): InstrumentationContext<Any> =
                SimpleInstrumentationContext.whenCompleted { _, _ ->
                    if (parameters.executionStepInfo.path.toList() == listOf("obj", "first")) requiredFetched.complete(Unit)
                }
        }
        EngineTestModule("extend type Query { obj: Obj, later: Int } type Obj { required: Int!, slow: Int }") {
            field("Query" to "obj") {
                resolver {
                    fn { _, _, _, _, _ -> emptyMap<String, Any?>() }
                }
                checker {
                    // Make the failed field available before object completion starts.
                    fn { _, _ ->
                        requiredFetched.await()
                        slowStarted.await()
                    }
                }
            }
            fieldWithValue("Obj" to "required", null)
            field("Obj" to "slow") {
                resolver {
                    fn { _, _, _, _, _ ->
                        slowStarted.complete(Unit)
                        release.await()
                        1
                    }
                }
            }
            field("Query" to "later") {
                resolver {
                    fn { _, _, _, _, _ ->
                        release.await()
                        42
                    }
                }
            }
        }.runFeatureTest(engineConfig = incrementalConfig.copy(additionalInstrumentation = instrumentation)) {
            runExecutionTest {
                try {
                    val result = assertInstanceOf(
                        IncrementalExecutionResult::class.java,
                        withTimeout(2_000) {
                            engine.execute(ExecutionInput("{ obj { first: required slow } ... @defer { later } }", requestContext = Any()))
                        },
                    )
                    try {
                        assertEquals(mapOf("obj" to null), result.getData<Map<String, Any?>>())
                        assertEquals(listOf("obj", "first"), result.errors.single().path)
                        assertTrue(requiredFetched.isCompleted)
                        assertTrue(slowStarted.isCompleted)
                        assertFalse(release.isCompleted)
                        assertTrue(result.hasNext)

                        release.complete(Unit)
                        val updates = result.updates.toList()
                        val patch = assertInstanceOf(IncrementalEntry.Object::class.java, updates.flatMap { it.incremental }.single())
                        assertEquals(mapOf("later" to 42), patch.data)
                        assertTrue(patch.errors.isEmpty())
                        assertFalse(updates.last().hasNext)
                    } finally {
                        result.cancel()
                    }
                } finally {
                    release.complete(Unit)
                }
            }
        }
    }

    private val incrementalConfig = EngineConfiguration.featureTestDefault.copy(
        flagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION),
    )
}
