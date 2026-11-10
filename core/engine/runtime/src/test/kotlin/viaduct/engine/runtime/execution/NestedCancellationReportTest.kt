package viaduct.engine.runtime.execution

import graphql.GraphQL
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.SimpleDataFetcherExceptionHandler
import graphql.execution.instrumentation.FieldFetchingInstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import graphql.schema.GraphQLObjectType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.instrumentation.ChainedModernGJInstrumentation
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.runtime.LazyEngineObjectData
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createSchema
import viaduct.engine.runtime.execution.ExecutionTestHelpers.executeQuery
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

class NestedCancellationReportTest {
    private class RecordingHandler : DataFetcherExceptionHandler {
        val seen = CopyOnWriteArrayList<String>()

        override fun handleException(handlerParameters: DataFetcherExceptionHandlerParameters): CompletableFuture<DataFetcherExceptionHandlerResult> {
            seen += "${handlerParameters.path}: ${handlerParameters.exception}"
            return SimpleDataFetcherExceptionHandler().handleException(handlerParameters)
        }
    }

    private class RecordingFetchInstrumentation : ViaductModernGJInstrumentation {
        val failures = CopyOnWriteArrayList<String>()

        override fun beginFieldFetching(
            parameters: InstrumentationFieldFetchParameters,
            state: InstrumentationState?,
        ): FieldFetchingInstrumentationContext =
            object : FieldFetchingInstrumentationContext {
                override fun onDispatched() {}

                override fun onCompleted(
                    result: Any?,
                    t: Throwable?,
                ) {
                    if (t != null) failures += "${parameters.executionStepInfo.path}: $t"
                }
            }
    }

    private class Reports(val handled: List<String>, val instrumented: List<String>)

    private class PendingLazyData(
        override val type: GraphQLObjectType,
        private val pending: Deferred<Unit>,
    ) : LazyEngineObjectData {
        override suspend fun fetch(selection: String): Any? = error("unresolved")

        override suspend fun fetchOrNull(selection: String): Any? = null

        override suspend fun fetchSelections(): Iterable<String> = emptyList()

        override suspend fun resolveData(
            selections: EngineSelectionSet,
            context: EngineExecutionContext,
        ): EngineObjectData? {
            pending.await()
            return null
        }
    }

    private fun graphQL(
        schema: EngineSchema,
        handler: DataFetcherExceptionHandler,
        instrumentation: RecordingFetchInstrumentation = RecordingFetchInstrumentation(),
    ): GraphQL {
        @Suppress("DEPRECATION")
        val factory = ViaductExecutionStrategy.Factory.Impl(
            dataFetcherExceptionHandler = handler,
            executionParametersFactory = ExecutionParameters.Factory(QueryPlanFactory.Default),
            accessCheckRunner = AccessCheckRunner(DefaultCoroutineInterop),
            coroutineInterop = DefaultCoroutineInterop,
            airbnbBypassPolicyCheckDuringCompletion = false,
        )
        return GraphQL.newGraphQL(schema.schema)
            .queryExecutionStrategy(factory.create(isSerial = false))
            .instrumentation(ChainedModernGJInstrumentation(listOf(instrumentation)))
            .build()
    }

    /** Mirrors a component prefetch: the caller starts a nested query, then cancels it once it is not needed. */
    private enum class Parent { EAGER, LAZY, FINISHED_COROUTINE }

    private fun reportsAfterCancellingNestedCaller(parent: Parent): Reports {
        val innerHandler = RecordingHandler()
        val innerInstrumentation = RecordingFetchInstrumentation()
        runExecutionTest {
            val innerStarted = CompletableDeferred<Unit>()
            val innerSchema = createSchema(
                "type Query { node: Node } type Node { messages: String }",
                mapOf(
                    "Query" to mapOf(
                        "node" to DataFetcher { env ->
                            when (parent) {
                                Parent.LAZY -> {
                                    val pending = scopedAsync {
                                        innerStarted.complete(Unit)
                                        awaitCancellation()
                                    }
                                    PendingLazyData(env.graphQLSchema.getObjectType("Node"), pending)
                                }
                                // The child field starts from this coroutine's completion handler.
                                Parent.FINISHED_COROUTINE -> scopedFuture {
                                    yield()
                                    emptyMap<String, Any?>()
                                }
                                Parent.EAGER -> emptyMap<String, Any?>()
                            }
                        }
                    ),
                    "Node" to mapOf(
                        "messages" to DataFetcher {
                            scopedFuture<String> {
                                innerStarted.complete(Unit)
                                awaitCancellation()
                            }
                        }
                    ),
                )
            )
            val innerGraphQL = graphQL(innerSchema, innerHandler, innerInstrumentation)
            val outerSchema = createSchema(
                "type Query { outer: String }",
                mapOf(
                    "Query" to mapOf(
                        "outer" to DataFetcher {
                            scopedFuture {
                                val prefetch = Job(coroutineContext[Job])
                                val nested = CoroutineScope(coroutineContext + prefetch).async {
                                    executeQuery(innerSchema, innerGraphQL, "{ node { messages } }", emptyMap())
                                }
                                innerStarted.await()
                                prefetch.cancel()
                                runCatching { nested.await() }
                                delay(200)
                                "done"
                            }
                        }
                    )
                )
            )

            val result = withTimeout(5000) {
                executeQuery(outerSchema, graphQL(outerSchema, RecordingHandler()), "{ outer }", emptyMap())
            }
            assertEquals(mapOf("outer" to "done"), result.getData<Map<String, Any?>>())
        }
        return Reports(innerHandler.seen.toList(), innerInstrumentation.failures.toList())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `independent future cancellation reports a field error and preserves its sibling`(preCompleted: Boolean) {
        runExecutionTest {
            val source = CompletableFuture<String>()
            val started = CompletableDeferred<Unit>()
            val handler = RecordingHandler()
            val instrumentation = RecordingFetchInstrumentation()
            val schema = createSchema(
                "type Query { cancelled: String, sibling: String }",
                mapOf(
                    "Query" to mapOf(
                        "cancelled" to DataFetcher {
                            started.complete(Unit)
                            if (preCompleted) source.cancel(false)
                            source
                        },
                        "sibling" to DataFetcher { "ok" },
                    )
                ),
            )
            withTimeout(5_000) {
                val result = async {
                    executeQuery(schema, graphQL(schema, handler, instrumentation), "{ cancelled sibling }", emptyMap())
                }
                started.await()
                yield()
                if (!preCompleted) source.cancel(false)
                val response = result.await()
                assertEquals(mapOf("cancelled" to null, "sibling" to "ok"), response.getData<Map<String, Any?>>())
                assertEquals(listOf(listOf("cancelled")), response.errors.map { it.path })
                assertEquals(1, handler.seen.size)
                assertEquals(1, instrumentation.failures.size)
            }
        }
    }

    @Test
    fun `cancelling a nested caller does not report its pending eager field`() {
        assertEquals(emptyList<String>(), reportsAfterCancellingNestedCaller(Parent.EAGER).handled)
    }

    @Test
    fun `cancelling a nested caller does not report children of its pending lazy object`() {
        assertEquals(emptyList<String>(), reportsAfterCancellingNestedCaller(Parent.LAZY).handled)
    }

    @Test
    fun `cancelling a nested caller does not report fields started from a finished coroutine`() {
        assertEquals(emptyList<String>(), reportsAfterCancellingNestedCaller(Parent.FINISHED_COROUTINE).handled)
    }

    @Test
    fun `cancelling a nested caller does not fail fetch instrumentation for its pending eager field`() {
        assertEquals(emptyList<String>(), reportsAfterCancellingNestedCaller(Parent.EAGER).instrumented)
    }

    @Test
    fun `cancelling a nested caller does not fail fetch instrumentation for children of its pending lazy object`() {
        assertEquals(emptyList<String>(), reportsAfterCancellingNestedCaller(Parent.LAZY).instrumented)
    }

    @Test
    fun `cancelling a nested caller does not fail fetch instrumentation for fields started from a finished coroutine`() {
        assertEquals(emptyList<String>(), reportsAfterCancellingNestedCaller(Parent.FINISHED_COROUTINE).instrumented)
    }
}
